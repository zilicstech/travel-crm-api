# Client Credit Limit – Design

**Date:** 2026-10-18
**Branch:** `feat/soft-delete-and-accounts` (both repos)
**Status:** reviewed, awaiting the user's approval

## Why

The agency gives its clients credit: both B2C clients and Corporate clients. Each client can be booked up to an agreed amount (for example ₹10 lakh) before they have to pay. Today nothing stops an agent from booking far beyond what a client is good for, and nobody can see at booking time how much room a client has left. The agency wants:

1. a limit set once, when the client is created;
2. every booking, payment, refund and cancellation to move the client's position on its own;
3. the agent to see the available limit while booking, and to be stopped before going over it.

**Guiding rule from the user:** as simple and intuitive as possible, with the fewest actions. The only new thing a person types is the limit itself. Everything else is worked out from records the system already keeps.

## What already exists (and is reused, not rebuilt)

- **Customer ledger.** `customer_ledger_entry` and `CustomerLedgerService` already keep a per-client debit/credit statement:
  - invoice raised: debit;
  - payment or deposit received: credit;
  - credit note: credit;
  - refund paid: debit;
  - reversal and opening balance.
  - `summary(clientId)` already returns the outstanding and advance amounts from the running balance.
  - The statement has PDF/XLSX export.
- **Customer wallet.** Advance deposits and refunds kept with the agency live in the client's wallet and post a ledger credit (`PaymentReceiptService.recordDeposit`, `creditWallet`).
- **Supplier side.** The supplier ledger, supplier wallet, bills, payments and credit notes are already per supplier and per booking. **No change is needed there** for this feature.
- **Booking money.** Vouchers carry the cost and selling price. A booking's `sellingPrice` is the running total of its vouchers. `BookingService.invoicedPrice(bookingId)` gives the pre-GST amount already invoiced.

## The model

One number is stored and two are computed. There is no separate wallet balance to keep in sync.

| Figure | Where it comes from |
|---|---|
| **Credit limit** | New column `client.credit_limit NUMERIC(14,2)`, nullable. `NULL` means no limit (unlimited). |
| **Used** | The sum of three parts:<br>1. the client's ledger running balance (invoices − payments − credit notes + refunds; negative when they hold an advance);<br>2. the not-yet-invoiced value of their live bookings (Σ `sellingPrice − invoicedPrice` over bookings that are not CANCELLED);<br>3. **cancellation charges kept on bookings cancelled before invoicing** (Σ `cancellationSupplierCharge + cancellationAgencyFee` over CANCELLED bookings with no live tax invoice). |
| **Available** | Credit limit − Used. It can be negative when the owner has allowed a booking over the limit. |

**Why "used" includes uninvoiced bookings.** Bookings are often invoiced days after they are made. Counting only the ledger would let an agent book ₹15 lakh on a ₹10 lakh client because nothing had been invoiced yet.

**What moves the figures, with no extra action:**
- **Voucher added or edited:** the booking price changes, so Used changes.
- **Invoice raised:** the amount moves from "uninvoiced" to "ledger". Used stays the same, apart from GST (see below).
- **Payment or deposit received:** Used goes down. An advance makes Used negative, so Available is above the limit.
- **Booking cancelled:**
  - before it is invoiced, it drops out of the uninvoiced sum, but the charges the agency keeps stay in Used (user decision, 2026-10-18);
  - after it is invoiced, the credit note credits the ledger for the refundable part;
  - in both cases only the refundable part of the limit is freed.
- **Refund paid out to the client:** Used goes up (the ledger debit). A refund kept in the client's wallet leaves it unchanged.

**GST.** The ledger holds GST-inclusive invoice totals, while the uninvoiced part is pre-GST. So "used" steps up by the GST at invoicing. This is accepted for simplicity: the limit is a control, not a statement. *(Builder's call; noted.)*

## Rules

1. **Setting the limit.**
   - "Credit limit (₹)" is a **required** field when a client is created: Add client modal, the inline new-client form in Add lead, and Quick book or New booking's Add client.
   - The agent fills it with any amount. The user chose to **trust the agent**: there is no cap and no approval step. The owner can review limits on the client list.
   - `0` means "no credit – pays upfront": they can only be booked against money they have deposited.
   - The **owner** may leave it blank, which means no limit. An agent must enter a number.
2. **Changing the limit later** is **owner only** (client edit screen), including clearing it back to "No limit". *(My call: limits are set freely at create time, but raising one after an agent has been blocked is the owner's decision.)*
3. **Existing clients** start with no limit (`NULL`), so nothing is blocked on the day this ships. The owner sets limits client by client. The client list shows "No limit" so these are easy to spot.
4. **Over-limit block.** Any save that **raises a booking's selling price** checks: if the new Used would exceed the limit, then:
   - an **agent** is refused with 409: "This takes {client} ₹1,20,000 over their credit limit (₹2,40,000 available). Ask the owner to raise the limit.";
   - the **owner** sees the same message as a confirmation and can save anyway ("Book over limit"). The override is recorded in the audit log.
   - **Paths checked:**
     - add voucher;
     - edit voucher (when its price goes up);
     - Log booking on a lead (it carries a price);
     - extra vouchers after invoicing.
   - New booking and Quick book create PENDING bookings at ₹0, so they never trip the block, but they **show** the available amount.
   - Lowering a price, cancelling, and payments are never blocked.
5. **Warning.** At 80% or more of the limit used, the chip turns amber: "₹1.6L left of ₹10L". Over the limit, it turns red. *(The 80% level is my call.)*

## Where people see it

A single small chip, `Available ₹2,40,000 of ₹10,00,000`, with a 3-colour state (normal / amber / red), shown:
- next to the client once one is picked, in New booking, Quick book and Log booking;
- in the voucher form above the cost and markup fields, with a live preview "After this voucher: ₹1,40,000 left" that turns red, and disables Save for agents, when it would go over;
- in the client page header, with a **Credit** card underneath:
  - Limit;
  - "Invoiced, unpaid" (the ledger outstanding);
  - "Booked, not invoiced";
  - "Cancellation charges" (shown only when above 0);
  - "Advance held";
  - Available.
- in the client list, as an extra column showing "Available" or "No limit";
- on the Accounts › Customers screen, as an extra column; over-limit clients are sorted to the top and shown in red.

**The ledger statement stays as it is: a financial statement of invoices, payments, credit notes and refunds,** which can be exported and sent to the client. Booking-level exposure is shown on the Credit card, not as fake ledger rows. *(Changed from my first proposal: putting non-posting booking rows into the statement would confuse the accountant and the client.)*

## API

- `ClientCreateRequest.creditLimit`: `@DecimalMin("0")`. The service requires it unless the caller is the owner, for whom null means no limit.
- `ClientUpdateRequest`:
  - `creditLimit` (optional) sets a new number;
  - `noCreditLimit: true` clears the limit, because a missing field can't tell "unchanged" from "clear";
  - both are owner-only, and anyone else gets 403.
- `ClientResponse` gains `creditLimit`, `creditUsed` and `creditAvailable`. These are computed, with `null` when there is no limit.
- `GET /api/clients/{id}/credit` returns `{limit, invoicedUnpaid, bookedNotInvoiced, advanceHeld, used, available}` for the Credit card and the chip.
- Booking and voucher writes:
  - a new optional `overrideCreditLimit: boolean` on the voucher create/update and log requests;
  - it is honoured only for the owner.
- **Where the check lives:** a single service-layer check, `ClientCreditService.assertWithinLimit(clientId, increase, override)`. It runs on **every** path that raises a booking's price or brings a booking back from CANCELLED, not only the UI paths:
  - voucher add/edit;
  - Log booking;
  - the API's `sellingPrice` on booking create/update;
  - `PATCH /{id}/status` moving a booking out of CANCELLED, which is refused outright as it is not a supported transition.
  - The check runs under the booking row lock already taken on these paths. Two agents booking the same client at the same moment can still both pass; that is accepted, as the limit is a control and not a ledger.
- **Currency:** limits and all three parts of Used are in INR. Invoiced amounts are converted with the invoice's `fxRateToInr` before subtracting, and the ledger's `*_inr` columns are used.
- **Soft delete:** the totals queries must exclude `deleted_at IS NOT NULL` rows. JPQL gets this automatically; the aggregate queries must be JPQL or carry the filter.
- The list endpoints use one aggregate query per page (the ledger balance grouped by client, plus the uninvoiced amount grouped by client), not N+1 calls.

## Non-goals
- No separate prepaid "wallet top-up" flow. Deposits already exist and already raise Available.
- No supplier credit limits.
- No automatic limit expiry, approval workflow, or notifications to the owner. The owner sees over-limit clients on the Customers screen.
- No multi-currency limits. Limits are in INR, the same as the ledger's INR columns.

## Anti-goals (a failure even if it works)
- An agent having to enter or adjust a debit or credit by hand to keep the limit right.
- A number on screen that disagrees with the client's statement for reasons nobody can explain. The Credit card breakdown must add up visibly.
- Blocking work that never increases what the client owes: payments, cancellations, editing details.

## Verification
- **Unit tests for "used":**
  - cancellation charges on a pre-invoice cancellation stay counted;
  - ledger only;
  - uninvoiced only;
  - an advance making it negative;
  - a cancelled booking dropping out;
  - a credit note freeing the limit.
- **Block tests:**
  - an agent is refused over the limit;
  - the owner override passes and is audited;
  - lowering a price or a `NULL` limit is never blocked.
- **e2e:** create a client with a ₹50,000 limit, add a ₹40,000 voucher (amber), then a ₹20,000 voucher as the agent (blocked), then the owner override.
- Pre-merge regression-tester run.
