# LLD — Lead Management Module

**Status:** Proposed, awaiting sign-off. Nothing in this document is implemented yet.
**Author:** Winston (System Architect)
**Scope:** The Lead Management module end to end — lead, travellers, multi-instance services,
follow-ups, costing, vouchers, invoices, notes, timeline, and the public proposal surface.
**Targets:** `travel-crm-backend` (Spring Boot 3.3 / Java 17 / Postgres, schema-per-tenant),
consumed by `travel-crm` (Next.js).

---

## 0. Sources of truth for this design

Every field below traces to something the UI already renders. The three inputs were:

| Input | What it fixed |
|---|---|
| `travel-crm/lib/mockData.ts` | The entity shapes. This file is the de-facto schema the UI was built against. |
| `travel-crm/lib/leadActions.ts` | The write surface — every mutation the UI can perform, and what each one must record. |
| `travel-crm/lib/session.ts` | The access model (`serviceAccess`, `leadAccess`, `canManageService`). |
| `travel-crm-backend/.../V1__init_tenant_schema.sql` | What already exists. This design **extends** V1 rather than restating it. |

Anything the UI does not render is not in this design. That is deliberate.

---

## 1. Design principles

These are the rules the rest of the document obeys. If a later change violates one of these,
it is a change to the design, not an implementation detail.

**P1 — One screen, one table, zero joins.**
Every list view in the product must be answerable by a single `SELECT` against a single table
with a `WHERE` and an `ORDER BY`. No joins, no `IN (SELECT …)`, no application-side stitching.
Section 9 proves this per screen.

**P2 — Denormalize names, never facts.**
A row carries a `*_name` / `*_label` snapshot of anything it displays but does not own
(`client_name`, `assigned_agent_name`, `service_label`). Snapshots are **live-synced by a bulk
`UPDATE` in the same transaction** as the rename. This is already the house pattern in V1 —
we extend it, we do not invent it.

**P3 — Money is recomputed, never trusted.**
Stored totals (`lead.quoted_*_total`, `lead_service.*_total`, `client_invoice.gst`) exist for
query performance only. Every write recomputes them from the current proposal lines. A stored
value is never read back as an input to a calculation. Same rule as `booking.profit` in V1.

**P4 — One table per concept, wide, not one table per variant.**
The four service types (Flight, Hotel, Visa, Transfer) live in **one** `lead_service` table with
nullable per-type columns. Four tables, a `service` supertype, or single-table-inheritance with a
JPA discriminator all cost a join or a polymorphic dispatch on every read of the lead detail
page — the single most-loaded screen in the product.

**P5 — JSONB only where the shape genuinely repeats.**
Exactly two JSONB columns exist in this design: flight sectors (0..n per flight) and per-traveller
visa ticks (0..n per visa, keyed by member). Everything else is a real, typed column. JSONB is an
escape hatch, not the default; using it for scalar fields would trade a join for a parse.

**P6 — Derived data is computed, not stored, unless a list screen needs it.**
The Trip Schedule, the visa roll-up, per-service totals shown inside an open service — all
computed in the service layer on read. The exceptions are listed explicitly in §7 and each one
earns its place by removing a join from a list query.

**P7 — Nothing is deleted that a customer could dispute.**
Travellers drop (`status = DROPPED` + reason), follow-ups complete (`status = DONE` +
`completed_at`), services cancel (`status = CANCELLED`). Hard deletes exist only for
quoted lines, vouchers and invoices, which are correctable clerical entries.

**P8 — Detail fields are all optional.**
The sales agent takes an enquiry on a phone call and knows *which* services are wanted, rarely
more. Every type-specific column is nullable. Completeness is gated at proposal and booking time
in the service layer, never by `NOT NULL`.

---

## 2. Entity map

```mermaid
erDiagram
    CLIENT      ||--o{ MEMBER        : "roster"
    CLIENT      ||--o{ LEAD          : "enquiries"
    LEAD        ||--o{ LEAD_MEMBERS  : "traveller manifest"
    MEMBER      ||--o{ LEAD_MEMBERS  : "travels on"
    LEAD        ||--o{ LEAD_SERVICE  : "0..n per type"
    LEAD        ||--o{ LEAD_FOLLOW_UP: "type-scoped"
    LEAD        ||--o{ LEAD_PROPOSAL : "quoted lines"
    LEAD        ||--o{ LEAD_VOUCHER  : "confirmations"
    LEAD        ||--o{ CLIENT_INVOICE: "billing"
    LEAD        ||--o{ LEAD_NOTES    : "human log"
    LEAD        ||--o{ LEAD_TIMELINE : "system log"
    LEAD_SERVICE ..o{ LEAD_PROPOSAL  : "service_id (nullable)"
    LEAD_SERVICE ..o{ LEAD_VOUCHER   : "service_id (nullable)"
    LEAD_SERVICE ..o{ CLIENT_INVOICE : "service_id (nullable)"
    LEAD_SERVICE ..o{ LEAD_TIMELINE  : "service_id (nullable)"
```

Read the dotted lines as: *this row may belong to one service, or to the trip as a whole.*
That nullability is load-bearing — an agency fee, a courier charge or an invoice covering
flight-plus-hotel together has no single owning service, and forcing one would either invent a
fake service or lose the charge.

**Ownership rule.** `lead_id` is on every child row, always. `service_id` is a *refinement*, never
a replacement: cascade-delete, agent-scoping and tenant queries all go through `lead_id`, so a
child row is never orphaned by a service being removed.

### Field mapping — UI type to column

What the frontend calls a field today, and where it lands. The left column is the TypeScript name in
`travel-crm/lib/mockData.ts` that components already use, so the API contract can keep those names and
the component layer need not change.

| UI type · field | Column | Note |
|---|---|---|
| `Lead.travellers[]` | `lead_members` rows | Array on the lead becomes rows keyed by `lead_id`. |
| `Lead.services[]` | `lead_service` rows | Same. Optional in TS only because the seed predates services. |
| `Lead.specialNotes` | `lead.special_notes` | Direct. |
| `Lead.travelPreferences[]` | `lead.travel_preferences` | Native `text[]`, not a join table. |
| `Lead.travelDateFrom/To` | `lead.travel_date_from/to` | Derived from services' date windows, excluding cancelled. |
| `Lead.date` | `lead.created_at` | Rename at the DTO boundary. |
| `Traveller.memberId` | `lead_members.member_id` | Direct. |
| `Traveller.fareClass` | `lead_members.fare_class` | Recomputed from `dob` on every write; stored for list speed. |
| `Traveller.visaChecklist` | *removed* | Moved to `lead_service.visa_checklists`. Already deprecated in the UI. See Q2. |
| `LeadService.details.sectors[]` | `flight_sectors` (jsonb) | Shape preserved exactly: `{from, to, date}`. |
| `HotelDetails.city/checkIn/checkOut/nights/rooms` | `hotel_*` | Flat columns, one per field. |
| `VisaDetails.travellerChecklists` | `visa_checklists` (jsonb) | Same `{memberId → ticks}` map. Read through one accessor, never directly. |
| `TransferDetails.time` | `transfer_time varchar(5)` | Local clock `HH:mm`, deliberately not a timestamp. |
| `LeadService.preferences[]` | `lead_service.preferences` | By **name**, not id — a deleted preference still reads on old leads. |
| `FollowUp.serviceType` | `lead_follow_up.service_type` | Was `serviceId`. Type-scoped is the current design. |
| `ProposalItem.serviceId` | `lead_proposal.service_id` | Nullable = trip-level charge. |
| `BookingVoucher.*` | `lead_voucher.*` | `referenceNumber → reference_number`, `fileUrl → file_key`. |
| `LeadInvoice.*` | `client_invoice.*` | Folded into the existing invoice table. See Q1. |
| `TimelineEvent.actor / actorId` | `actor_name / actor_agent_id` | Direct. |
| `Agent.manageableServices[]` | `public.agent.manageable_services` | The entire access model lives in this one array. |
| `Itinerary` / Trip Schedule | *computed* | No table. Derived from the services' dated events on read. |

### The three levels that already exist (unchanged)

`client → member → lead_members` is V1 and stays exactly as designed. Three things about it are
load-bearing and must not be "fixed":

- `member` has **no age column**. Pax type is derived from `dob` against the travel date. A stored
  age mis-fares a child who turns 12 between enquiry and departure.
- `lead_members` is a **real entity, not a link table**. A traveller leaves by moving to `DROPPED`
  with a reason, never by deleting the row — so the documents collected for them survive.
- Contact details are **not** duplicated onto `lead`. They resolve through `client` → the primary
  member (`type = CLIENT`).

---

## 3. `lead` — changes to the existing table

V1's `lead` is close. Six additive columns and one redefinition.

```sql
-- V2__lead_management.sql  (tenant schema)

ALTER TABLE lead ADD COLUMN special_notes        TEXT;
ALTER TABLE lead ADD COLUMN travel_preferences   TEXT[]        NOT NULL DEFAULT '{}';
ALTER TABLE lead ADD COLUMN quoted_net_total     NUMERIC(19,2) NOT NULL DEFAULT 0;
ALTER TABLE lead ADD COLUMN quoted_selling_total NUMERIC(19,2) NOT NULL DEFAULT 0;
ALTER TABLE lead ADD COLUMN open_follow_ups      INTEGER       NOT NULL DEFAULT 0;
ALTER TABLE lead ADD COLUMN service_count        INTEGER       NOT NULL DEFAULT 0;
```

| Column | Why |
|---|---|
| `special_notes` | Trip-wide remarks captured on the create-lead wizard, editable on Trip Details. Services no longer carry their own requirements text — one remark visible to everyone replaced it. |
| `travel_preferences` | Trip-level preferences (as distinct from the per-service ones on `lead_service.preferences`). Native `TEXT[]`, same as the existing `categories` and `kid_ages`. |
| `quoted_net_total` / `quoted_selling_total` | P3 snapshot. Lets the leads list show trip value with no join to `lead_proposal`. Recomputed on **every** proposal-line write and on every service status change (a cancelled service's lines stop counting). |
| `open_follow_ups` | Lets the leads list badge overdue work without a join. Recomputed on every follow-up write. |
| `service_count` | Same, for the "3 services" chip. |

**Redefinition — `follow_up_date`.** V1 declares it as a plain date. It becomes a derived cache:
*the earliest `due_date` among this lead's `OPEN` follow-ups, or `NULL`.* Re-synced in the same
transaction as any follow-up insert, complete or delete. The UI has no way to write it directly and
must not be given one — a lead now holds many follow-ups and one date cannot represent them.

**`categories`** stays as-is, and continues to be the union of the service types present on the
lead plus any package preset chosen at enquiry. It is written by the service layer, not the client.

---

## 4. `lead_service` — the multi-instance service table

This is the centre of the design. One table, one row per service instance, any number of rows
per type per lead.

```sql
CREATE TABLE lead_service (
    id                  VARCHAR(36)  NOT NULL PRIMARY KEY,
    lead_id             VARCHAR(36)  NOT NULL,

    -- ── Denormalized lead context (P2) ───────────────────────────────────────
    -- Present so the cross-lead "Services" board is a single flat SELECT.
    client_id           VARCHAR(36)  NOT NULL,
    client_name         VARCHAR(150) NOT NULL,
    lead_destination    VARCHAR(150) NOT NULL,
    lead_status         VARCHAR(20)  NOT NULL,

    -- ── Identity ─────────────────────────────────────────────────────────────
    type                VARCHAR(20)  NOT NULL,   -- FLIGHT | HOTEL | VISA | TRANSFER
    status              VARCHAR(20)  NOT NULL,   -- NOT_STARTED | IN_PROGRESS | AWAITING_CLIENT | CONFIRMED | CANCELLED
    label               VARCHAR(200) NOT NULL,   -- derived instance name, see §4.2
    sort_order          INTEGER      NOT NULL DEFAULT 0,

    -- ── Assignment ───────────────────────────────────────────────────────────
    assigned_agent_id   VARCHAR(36),
    assigned_agent_name VARCHAR(150),

    -- ── Common ───────────────────────────────────────────────────────────────
    preferences         TEXT[]       NOT NULL DEFAULT '{}',
    due_date            DATE,

    -- ── Derived date window (§7) ─────────────────────────────────────────────
    date_from           DATE,
    date_to             DATE,

    -- ── Money snapshot (P3) ──────────────────────────────────────────────────
    net_total           NUMERIC(19,2) NOT NULL DEFAULT 0,
    selling_total       NUMERIC(19,2) NOT NULL DEFAULT 0,

    -- ── FLIGHT ───────────────────────────────────────────────────────────────
    flight_trip_type    VARCHAR(20),             -- ONE_WAY | ROUND_TRIP
    flight_cabin        VARCHAR(20),             -- ECONOMY | PREMIUM_ECONOMY | BUSINESS | FIRST
    flight_sectors      JSONB        NOT NULL DEFAULT '[]',

    -- ── HOTEL ────────────────────────────────────────────────────────────────
    hotel_city          VARCHAR(150),
    hotel_check_in      DATE,
    hotel_check_out     DATE,
    hotel_nights        INTEGER,
    hotel_rooms         INTEGER,

    -- ── VISA ─────────────────────────────────────────────────────────────────
    visa_source_city         VARCHAR(150),
    visa_source_country      VARCHAR(100),
    visa_country             VARCHAR(100),
    visa_intended_travel_date DATE,
    visa_appointment_date    DATE,
    visa_checklists          JSONB   NOT NULL DEFAULT '{}',

    -- ── TRANSFER ─────────────────────────────────────────────────────────────
    transfer_vehicle_type VARCHAR(50),
    transfer_pickup       VARCHAR(150),
    transfer_dropoff      VARCHAR(150),
    transfer_date         DATE,
    transfer_time         VARCHAR(5),            -- 'HH:mm', local clock time, never a timestamp
    transfer_passengers   INTEGER,

    created_at  TIMESTAMP,
    created_by  VARCHAR(36),
    updated_at  TIMESTAMP,
    updated_by  VARCHAR(36)
);

CREATE INDEX idx_lead_service_lead        ON lead_service (lead_id, sort_order);
CREATE INDEX idx_lead_service_agent       ON lead_service (assigned_agent_id);
CREATE INDEX idx_lead_service_type_status ON lead_service (type, status);
CREATE INDEX idx_lead_service_dates       ON lead_service (date_from);
```

### 4.1 Why one wide table

The alternative shapes and what each costs:

| Shape | Cost |
|---|---|
| Four tables (`lead_flight`, `lead_hotel`, …) | The lead detail page needs 4 queries or 4 `LEFT JOIN`s. The cross-lead Services board needs a 4-way `UNION`. Adding a fifth type touches every query. Violates P1. |
| Supertype + subtype tables | Every read is a join. Worst of both. |
| One table, all details in one JSONB blob | No indexing on `hotel_check_in` or `visa_appointment_date`, no DB-level typing on dates, and reporting becomes string-matching inside JSON. Violates P5. |
| **One wide table, typed columns + 2 JSONB (chosen)** | ~24 nullable columns, most `NULL` on any given row. Postgres stores a `NULL` in the row header bitmap at roughly one bit each — the space cost is negligible. One JPA entity, one query, no dispatch. |

The sparseness is the price, and it is the cheap side of this trade. A `NULL` column costs a bit;
a join costs a query plan.

### 4.2 `label` — the instance name

A lead holding two hotels must call them different things everywhere: the service tab strip, the
Trip Details list, the Costing table, the timeline entry, and a deep link pasted to a colleague.
The rule, ported verbatim from `serviceInstanceLabel` in the frontend:

1. If the lead has only one service of this type → the plain type label (`Hotel`).
2. Otherwise, use the type's natural naming field:
   Flight → `from–to` of the first sector with both ends set; Hotel → `hotel_city`;
   Visa → `visa_country`; Transfer → `transfer_pickup`. Result: `Hotel — Phuket`.
3. If two siblings resolve to the *same* source → append an ordinal: `Hotel — Phuket (2)`.
4. If the source field is blank → positional fallback: `Hotel 2`.

**Stored, not computed on read.** Computing it needs the sibling set, which the cross-lead Services
board does not load. So it is a P2 snapshot: **any write that adds, removes, or renames a service
re-derives and bulk-updates `label` for every service on that lead in the same transaction.** That
is one extra `UPDATE` on a set that is realistically under ten rows.

### 4.3 `flight_sectors` (JSONB)

```json
[{"from": "BOM", "to": "BKK", "date": "2026-10-01"},
 {"from": "BKK", "to": "HKT", "date": "2026-10-03"}]
```

A flight has 1..n sectors. The only join-free alternatives are a child table (a join — violates P1)
or parallel Postgres arrays (`sector_from TEXT[]`, `sector_to TEXT[]`, `sector_date DATE[]`), which
can silently desynchronise in length. JSONB keeps the tuple intact. Sectors are always read and
written as a whole block; nothing queries an individual sector.

**Validation belongs in the service layer**, not the DB: at most 8 sectors, `date` must parse as
`YYYY-MM-DD`, airport codes upper-cased and trimmed. The DB stores what the service validated.

### 4.4 `visa_checklists` (JSONB)

```json
{"M5":  {"passportCollected": true, "photosCollected": true, "formsFilled": false,
         "submittedToEmbassy": false, "approved": false, "passportReturned": false},
 "M12": {"passportCollected": true, "photosCollected": false, "formsFilled": false,
         "submittedToEmbassy": false, "approved": false, "passportReturned": false}}
```

Keyed by `member_id`, **never by array index** — the traveller list is filtered and sorted in the UI
and an index would silently retarget the ticks.

This is per-visa-per-traveller: a trip carrying a Thailand visa and a Japan visa needs two fully
independent sets of ticks. A lead-wide checklist (V1's five booleans on `lead_members`) made both
applications share and overwrite each other. A child table `lead_service_traveller` would be
correct and would cost a join on the single most-viewed panel; the whole map is read and written
as one block, so JSONB is the right call here.

**Known limitation, accepted:** `passportCollected` and `passportReturned` are physically one fact
per traveller per trip, not per application — you collect the passport once even if you file two
visas with it. The UI shows a note to that effect when a lead holds more than one visa. Splitting
these two keys onto `lead_members` later is purely additive and does not change this table.

### 4.5 `date_from` / `date_to`

A P6 exception. Derived from the type-specific columns:

| Type | `date_from` | `date_to` |
|---|---|---|
| Flight | earliest sector date | latest sector date |
| Hotel | `hotel_check_in` | `hotel_check_out`, else `check_in + hotel_nights`, else `check_in` |
| Visa | `visa_intended_travel_date` | same |
| Transfer | `transfer_date` | same |

Recomputed on every service write, in the service layer, before the row is saved. It exists so that
(a) `lead.travel_date_from/to` can be re-derived from a simple `MIN`/`MAX` over the lead's services,
and (b) the Trip Schedule can be range-queried. Cancelled services are **excluded** from the
lead-level window — the window is a commitment — but **included** in the Trip Schedule, which is a
record. That asymmetry is intentional; do not unify it.

### 4.6 Service status and assignment rules

```
NOT_STARTED ──accept──▶ IN_PROGRESS ──▶ AWAITING_CLIENT ──▶ CONFIRMED
     │                       │                 │                 │
     └───────────────────────┴─────────────────┴─────────────────┴──▶ CANCELLED
```

Two rules the service layer enforces, both ported from the UI:

1. **`acceptService`** — an unclaimed, non-terminal service (`assigned_agent_id IS NULL` and status
   not `CONFIRMED`/`CANCELLED`) can be claimed by any agent whose role covers its type. Claiming
   sets `assigned_agent_id` **and** moves status to `IN_PROGRESS` in one step. Claiming a job and
   leaving it `NOT_STARTED` tells the agency nothing.
2. **Status changes require an assignee.** `assigned_agent_id IS NULL` ⇒ status is not editable.
   An unclaimed "Confirmed" is a status nobody can stand behind.

Reassignment to *another* agent is owner-only. An agent may claim, never hand off.

---

## 5. `lead_follow_up` — type-scoped commitments

```sql
CREATE TABLE lead_follow_up (
    id                  VARCHAR(36)  NOT NULL PRIMARY KEY,
    lead_id             VARCHAR(36)  NOT NULL,

    -- Denormalized so the agent's "My follow-ups" dashboard is one flat SELECT (P2).
    client_name         VARCHAR(150) NOT NULL,
    lead_destination    VARCHAR(150) NOT NULL,

    -- NULL = a commitment about the trip as a whole, not about one kind of service.
    service_type        VARCHAR(20),

    due_date            DATE         NOT NULL,
    note                VARCHAR(500) NOT NULL,
    assigned_agent_id   VARCHAR(36)  NOT NULL,
    assigned_agent_name VARCHAR(150) NOT NULL,
    status              VARCHAR(10)  NOT NULL,   -- OPEN | DONE
    completed_at        TIMESTAMP,
    created_at          TIMESTAMP,
    created_by          VARCHAR(36)
);

CREATE INDEX idx_lead_follow_up_lead  ON lead_follow_up (lead_id, status, due_date);
CREATE INDEX idx_lead_follow_up_agent ON lead_follow_up (assigned_agent_id, status, due_date);
```

**`service_type`, not `service_id`.** This is the decision to read carefully, because it is the one
that changed most recently and it looks like a mistake if you assume the obvious.

"Chase the embassy on Monday" is Visa work whichever visa it is about. A trip holding three flights
should not carry three separate follow-up lists that all read "nothing scheduled". Scoping to the
*type* means one list per type tab: at most five lists on a lead (four types plus trip-level),
regardless of how many instances exist. Scoping to the *instance* meant the count grew with the
number of services and every one of them was empty.

`NULL` means trip-level ("call the client Friday"). The UI renders exactly one list per active
service-type tab, plus the trip-level list on Trip Details.

**Completed, not deleted.** "We said we would call on the 20th and we did" is precisely the fact a
customer disputes. `status = DONE` + `completed_at` (P7). Delete exists for a follow-up entered in
error, and writes a timeline entry when used.

**Sync obligation:** every insert / complete / delete recomputes `lead.open_follow_ups` and
`lead.follow_up_date` in the same transaction.

---

## 6. Money, confirmations, and the paper trail

### 6.1 `lead_proposal` — quoted lines (extends V1)

```sql
ALTER TABLE lead_proposal ADD COLUMN service_id    VARCHAR(36);
ALTER TABLE lead_proposal ADD COLUMN service_label VARCHAR(200);

CREATE INDEX idx_lead_proposal_service ON lead_proposal (service_id);
```

Lines stay a **flat list on the lead**, not nested inside each service. A single invoice routinely
covers flight and hotel together — that is how a trip is actually billed — and nesting would make
that unrepresentable. `service_id NULL` = a trip-level charge (agency fee, courier) shown on the
Costing tab and on no service.

**Cancellation rule.** A quoted line has no status of its own. Lines belonging to a `CANCELLED`
service are excluded from every total. Filtering must therefore run *through the owning service* —
a line whose service was called off would otherwise keep quietly counting. This is why
`lead.quoted_*_total` must be recomputed on service status change, not only on proposal writes.

### 6.2 `lead_voucher` — booking confirmations

```sql
CREATE TABLE lead_voucher (
    id               VARCHAR(36)  NOT NULL PRIMARY KEY,
    lead_id          VARCHAR(36)  NOT NULL,
    service_id       VARCHAR(36),
    service_label    VARCHAR(200),
    supplier         VARCHAR(150) NOT NULL,
    reference_number VARCHAR(100) NOT NULL,   -- PNR, hotel confirmation, visa file number
    voucher_date     DATE         NOT NULL,
    file_key         VARCHAR(500),            -- opaque, from FileStorageService; never a filesystem path
    notes            TEXT,
    created_at       TIMESTAMP,
    created_by       VARCHAR(36)
);

CREATE INDEX idx_lead_voucher_lead    ON lead_voucher (lead_id);
CREATE INDEX idx_lead_voucher_service ON lead_voucher (service_id);
```

A PNR is a fact about a flight and a confirmation number is a fact about a hotel, so
`service_id` is normally set — but it stays nullable for the booking that legitimately covers two
services at once.

### 6.3 Invoices — extend `client_invoice`, do not add a second table

```sql
ALTER TABLE client_invoice ADD COLUMN lead_id       VARCHAR(36);
ALTER TABLE client_invoice ADD COLUMN service_id    VARCHAR(36);
ALTER TABLE client_invoice ADD COLUMN service_label VARCHAR(200);
ALTER TABLE client_invoice ADD COLUMN description   VARCHAR(255);

CREATE INDEX idx_client_invoice_lead ON client_invoice (lead_id);
```

The frontend has a lead-scoped `LeadInvoice` and the backend has an agency-wide `client_invoice`.
**Recommendation: one table.** Two invoice tables means two answers to "what does this client owe
us", and the finance screens would have to union them. A lead invoice is a client invoice that
happens to name its lead. `lead_id NULL` is a direct client invoice raised outside any lead — which
already exists today and must keep working.

`status` (`DRAFT | SENT | PARTIALLY_PAID | PAID | OVERDUE`), `gst` and `total_with_gst` remain
server-derived on every write (P3). `amount_paid` stays cumulative — total paid to date, never a
single receipt.

*Trade-off, stated plainly:* the cost is that `client_invoice` grows four nullable columns and its
list query must not accidentally leak lead-scoped invoices into a screen that means to show only
direct ones. The alternative cost is a permanent reconciliation problem between two ledgers. I
recommend the four columns.

### 6.4 `lead_notes` and `lead_timeline` (V1, one addition)

```sql
ALTER TABLE lead_timeline ADD COLUMN service_id VARCHAR(36);
CREATE INDEX idx_lead_timeline_service ON lead_timeline (service_id, created_at DESC);
```

Keep these two distinct, as V1 already does:

- **`lead_notes`** — human-authored, free text, editable by the agent who wrote it.
- **`lead_timeline`** — system-written, append-only, **no client-facing write endpoint**. Emitted by
  the service layer on every state change.

`service_id` lets a service card show its own history without scanning the whole trip's timeline.
`NULL` = a lead-level event.

**Event types** (`event_type`), matching what the UI already emits:
`CREATED · STATUS_CHANGE · REASSIGNED · SERVICE_ADDED · SERVICE_UPDATED · TRAVELLER_ADDED ·
TRAVELLER_DROPPED · FOLLOW_UP · PROPOSAL_ITEM · VOUCHER_ADDED · INVOICE_ADDED · NOTE_ADDED ·
LINK_SHARED`

Every mutation in §11 writes exactly one timeline row, in the same transaction. This is not
optional — a state change with no timeline entry is a bug, and the reason all writes live behind
the service layer rather than being spread across controllers.

---

## 7. Derived data — what is computed, and what is stored

**Computed on read, never stored:**

| Thing | Derived from |
|---|---|
| **Trip Schedule** (day-by-day) | All services' dated events, grouped by day. A flight yields one event per dated sector; a hotel yields check-in and check-out; a visa yields its appointment; a transfer yields one. Within a day, fixed order: check-out, flight, transfer, appointment, check-in — the shape of a real travel day, deterministic without times. **Never invent a time for a flight.** Services with zero dated events fall into a trailing "not scheduled yet" bucket. |
| **Visa roll-up** on the lead detail | Aggregate of every visa service's `visa_checklists`. Read-only. |
| Pax type / fare class | `member.dob` vs `lead.travel_date_from` (`PaxTypeCalculator`). |
| Service-level net/selling shown inside an open service | Its own `lead_proposal` rows. |
| Trip margin % | `(selling − net) / selling`. |
| Instance label at *display* time | Recomputed and compared against the stored `label` in dev builds as a consistency check. |

**Stored, with the list screen that justifies it:**

| Column | Justified by |
|---|---|
| `lead.quoted_net_total` / `quoted_selling_total` | Leads list value column |
| `lead.open_follow_ups`, `lead.follow_up_date` | Leads list overdue badge |
| `lead.service_count` | Leads list service chip |
| `lead.travel_date_from` / `to` | Leads list date column + fare-class calc |
| `lead_service.label` | Cross-lead Services board |
| `lead_service.date_from` / `date_to` | Trip Schedule range query, lead window derivation |
| `lead_service.net_total` / `selling_total` | Services board value column |
| every `*_name` / `*_label` snapshot | P2 |

If a proposed stored column cannot name a list screen, it does not go in.

---

## 8. Access control

Two independent axes. Both are already implemented in the frontend and must be mirrored server-side
in `@PreAuthorize` plus service-layer checks — the frontend's version is a UX affordance, not a
security boundary.

**Axis 1 — the lead belongs to the sales agent who took it.**
Lead-level fields (destination, status, travellers, dates, notes) are editable by the owning agent
(`lead.assigned_to`) or by the Agency Owner. Nobody else.

**Axis 2 — a service belongs to whoever handles that *type*.**
An agent may edit a service when its type appears in their `manageable_services`, **on any lead,
whoever owns it**. This is the whole point of the model: a visa specialist works the visa on every
lead in the agency.

```sql
-- public schema, V3
ALTER TABLE agent ADD COLUMN manageable_services TEXT[] NOT NULL DEFAULT '{}';
```

Plus one clause that is easy to drop and expensive to lose:

> An agent may also edit a service they are **personally assigned to**, even if its type is no
> longer in their `manageable_services`.

Without it, an owner unticking "Visa" on an agent's profile instantly yanks that agent off work they
are named on and half-way through. `manageable_services` is editable in the agent form, so this
happens in practice.

Denied access carries a **reason and an owner label**, never a bare boolean — the UI is required to
tell the agent who *can* do it.

**Role matrix**

| Action | Owner | Agent (type matches) | Agent (assigned) | Agent (neither) |
|---|---|---|---|---|
| View lead | ✅ | ✅ | ✅ | ❌ |
| Edit lead fields / travellers | ✅ | only if lead owner | only if lead owner | ❌ |
| Add service to a lead | ✅ | only if lead owner | only if lead owner | ❌ |
| Edit service details / preferences | ✅ | ✅ | ✅ | ❌ |
| Claim unassigned service | ✅ | ✅ | — | ❌ |
| Change service status | ✅ | only if assigned | ✅ | ❌ |
| Reassign service to another agent | ✅ | ❌ | ❌ | ❌ |
| Add follow-up | ✅ | ✅ | ✅ | ❌ |
| Add / edit quoted lines | ✅ | ✅ (own service) | ✅ | ❌ |
| See `net_cost` and margin | ✅ | ✅ | ✅ | ❌ |
| Raise an invoice | ✅ | ❌ | ❌ | ❌ |

`net_cost` and margin **never** cross to the public proposal surface — see §10.

---

## 9. Query catalog — the P1 proof

Each of these is one table, one `WHERE`, no join.

```sql
-- Leads list (owner). Value, follow-up badge and service count all inline.
SELECT id, client_name, destination, current_status, priority, assigned_agent_name,
       travel_date_from, travel_date_to, categories, quoted_selling_total,
       follow_up_date, open_follow_ups, service_count
FROM   lead
WHERE  is_active
ORDER  BY created_at DESC;

-- Leads list (agent) — only leads they own outright.
… WHERE is_active AND assigned_to = :agentId;

-- Cross-lead Services board: "every visa I handle, oldest first."
SELECT id, label, type, status, client_name, lead_destination, lead_id,
       assigned_agent_name, date_from, due_date, selling_total
FROM   lead_service
WHERE  type = ANY(:manageableTypes)
ORDER  BY COALESCE(due_date, date_from) NULLS LAST;

-- Unclaimed work anyone qualified can pick up.
… WHERE type = ANY(:manageableTypes)
    AND assigned_agent_id IS NULL
    AND status NOT IN ('CONFIRMED','CANCELLED');

-- My follow-ups, overdue first.
SELECT id, lead_id, client_name, lead_destination, service_type, due_date, note
FROM   lead_follow_up
WHERE  assigned_agent_id = :agentId AND status = 'OPEN'
ORDER  BY due_date;

-- Lead detail: 7 single-table reads by lead_id, issued in parallel.
SELECT * FROM lead           WHERE id      = :id;
SELECT * FROM lead_service   WHERE lead_id = :id ORDER BY sort_order;
SELECT * FROM lead_members   WHERE lead_id = :id;
SELECT * FROM lead_follow_up WHERE lead_id = :id ORDER BY status, due_date;
SELECT * FROM lead_proposal  WHERE lead_id = :id;
SELECT * FROM lead_voucher   WHERE lead_id = :id;
SELECT * FROM lead_timeline  WHERE lead_id = :id ORDER BY created_at DESC LIMIT 50;
```

The lead detail page is 7 indexed single-table reads with no joins between them, assembled into one
`LeadDetailResponse` in the service layer. That is the price of the design, and it is cheap: seven
index seeks beat one seven-way join, and each one is independently cacheable.

Note what is *absent*: nothing in this catalog filters on `tenant_id`. Tenant isolation is the
`search_path` set at connection-borrow time. Manual tenant filtering on a tenant-schema query is a
bug, not a safety net.

---

## 10. Public proposal surface

One deliberately unauthenticated endpoint: `GET /api/public/proposals/{token}`.

- Resolves a **high-entropy token**, never the lead's real id, via `public.proposal_link`.
- Reads the owning tenant schema through a `REQUIRES_NEW` cross-tenant transaction, restoring the
  previous `TenantContext` in a `finally` block.
- Returns a **hand-built DTO that is structurally incapable** of carrying `net_cost`, margin,
  `assigned_agent_id`, internal notes, timeline, or invoices. Not a filtered entity — a separate
  class with only the fields the customer may see.
- A controller-slice test asserts those keys are **absent from the raw JSON**, not merely null.

What the customer sees: destination, dates, traveller count, the service list by `label`, quoted
lines with `selling_price` only, and the total. Nothing else.

---

## 11. Service layer — transactional obligations

Every mutation is one `@Transactional` method that performs *all* of the following before it
returns. Skipping any one of them is what makes a screen show a stale number.

| Mutation | Must also do |
|---|---|
| `createLead` | Insert `lead_members` for each traveller · timeline `CREATED` |
| `updateLead` | Re-sync `client_name` snapshots on children if the client changed · timeline |
| `addServices(leadId, drafts[])` | Re-derive `label` for **all** services on the lead · re-derive each `date_from/to` · widen `lead.travel_date_from/to` · union into `lead.categories` · bump `service_count` · timeline `SERVICE_ADDED` |
| `saveService` | Re-derive that service's `label` + `date_from/to` · re-derive the lead window · timeline `SERVICE_UPDATED` |
| `setServiceStatus` | Reject if `assigned_agent_id IS NULL` · **recompute `lead.quoted_*_total`** (cancellation changes the total) · timeline |
| `acceptService` | Reject unless unclaimed and non-terminal · set agent **and** `IN_PROGRESS` · timeline |
| `assignService` | Owner-only · snapshot `assigned_agent_name` · timeline |
| `toggleVisaStep` | Merge one key into `visa_checklists[memberId]` · **must not touch `lead_members`** · timeline naming which visa and which traveller |
| `toggleServicePreference` | Write immediately, no edit-mode round trip · timeline |
| `addFollowUp` / `complete` / `delete` | Recompute `lead.open_follow_ups` + `lead.follow_up_date` · timeline |
| `addProposalItem` / `update` / `delete` | Recompute `lead_service.*_total` and `lead.quoted_*_total`, excluding cancelled services · timeline |
| `addVoucher` / `deleteVoucher` | Snapshot `service_label` · timeline |
| `addInvoice` | Owner-only · derive `gst` + `total_with_gst` · derive `status` from `amount_paid` · timeline |
| `addTraveller` / `dropTraveller` | Drop = `status = DROPPED` + reason, **never a delete** · re-derive fare classes · timeline |
| `renameClient` / `renameMember` / `renameAgent` | Bulk `UPDATE` every `*_name` snapshot in the tenant schema, same transaction (P2) |

**Cross-tenant caution:** any code that switches tenant mid-request must do the tenant-scoped work
in a separate `REQUIRES_NEW` bean — self-invocation bypasses the proxy — and restore the previous
`TenantContext` in a `finally`.

---

## 12. API surface

```
# Leads
GET    /api/leads                       ?status=&agentId=&q=&page=
POST   /api/leads
GET    /api/leads/{id}                  → LeadDetailResponse (the 7-read assembly)
PUT    /api/leads/{id}
PATCH  /api/leads/{id}/status           { status, lostReason? }
PATCH  /api/leads/{id}/assign           { agentId }            # owner only

# Travellers
POST   /api/leads/{id}/travellers       { memberId, status }
PATCH  /api/leads/{id}/travellers/{memberId}
DELETE /api/leads/{id}/travellers/{memberId}   # → DROPPED + reason, not a row delete

# Services  (multi-instance: POST always creates a NEW instance)
GET    /api/services                    ?type=&status=&unassigned=  # cross-lead board
POST   /api/leads/{id}/services         [ ServiceDraft, … ]         # batch, matches the UI
PUT    /api/leads/{id}/services/{sid}
PATCH  /api/leads/{id}/services/{sid}/status
POST   /api/leads/{id}/services/{sid}/accept
PATCH  /api/leads/{id}/services/{sid}/assign                        # owner only
PATCH  /api/leads/{id}/services/{sid}/preferences  { name, on }
PATCH  /api/leads/{id}/services/{sid}/visa-checklist { memberId, key, value }
DELETE /api/leads/{id}/services/{sid}

# Follow-ups
GET    /api/follow-ups                  ?agentId=&status=&dueBefore=
POST   /api/leads/{id}/follow-ups       { dueDate, note, assignedAgentId, serviceType? }
PATCH  /api/leads/{id}/follow-ups/{fid}/complete
DELETE /api/leads/{id}/follow-ups/{fid}

# Costing / vouchers / invoices / notes
POST   /api/leads/{id}/proposal-items      { …, serviceId? }
PUT    /api/leads/{id}/proposal-items/{pid}
DELETE /api/leads/{id}/proposal-items/{pid}
POST   /api/leads/{id}/vouchers            { …, serviceId? }
DELETE /api/leads/{id}/vouchers/{vid}
POST   /api/leads/{id}/invoices            { …, serviceId? }        # owner only
POST   /api/leads/{id}/notes
GET    /api/leads/{id}/timeline            ?serviceId=

# Derived
GET    /api/leads/{id}/schedule            → Trip Schedule, computed, never stored

# Public
GET    /api/public/proposals/{token}       # unauthenticated, §10
POST   /api/leads/{id}/proposal-link       # mint/rotate token
```

Layering is unchanged: **Controller → Service → Repository → DB.** Controllers do HTTP, `@Valid` and
`@PreAuthorize`, nothing else — no business logic, no repository access, no try/catch, and never
return a JPA entity. Services own every `@Transactional` boundary and all entity↔DTO mapping.

---

## 13. Migration plan

Flyway runs from two independent locations with independent version sequences. Never edit an
applied migration.

```
src/main/resources/db/migration/tenant/V2__lead_management.sql
    ALTER  lead            (+6 columns, follow_up_date redefined)
    ALTER  lead_members    (+fare_class; visa booleans deprecated — see §14 Q2)
    CREATE lead_service
    CREATE lead_follow_up
    CREATE lead_voucher
    ALTER  lead_proposal   (+service_id, +service_label)
    ALTER  lead_timeline   (+service_id)
    ALTER  client_invoice  (+lead_id, +service_id, +service_label, +description)
    CREATE agency_setting

src/main/resources/db/migration/public/V3__agent_manageable_services.sql
    ALTER  agent           (+manageable_services TEXT[])
```

`agency_setting` replaces the frontend's `localStorage` configuration — lead sources, travel
categories, document types, and the per-service preference catalog:

```sql
CREATE TABLE agency_setting (
    id           VARCHAR(36)  NOT NULL PRIMARY KEY,
    kind         VARCHAR(30)  NOT NULL,  -- LEAD_SOURCE | TRAVEL_CATEGORY | DOCUMENT_TYPE | SERVICE_PREFERENCE
    service_type VARCHAR(20),            -- set only for SERVICE_PREFERENCE ("Window Seat" is a flight question)
    name         VARCHAR(150) NOT NULL,
    is_active    BOOLEAN      NOT NULL DEFAULT TRUE,
    is_default   BOOLEAN      NOT NULL DEFAULT FALSE,
    sort_order   INTEGER      NOT NULL DEFAULT 0,
    created_at   TIMESTAMP
);
CREATE INDEX idx_agency_setting_kind ON agency_setting (kind, is_active);
```

Preferences are stored on `lead_service.preferences` **by name, not by id**, so a preference the
owner later deletes still reads correctly on leads that already chose it.

`TenantFlywayMigrator` applies the tenant sequence on tenant creation, and the startup catch-up
runner applies it to existing tenants. `scripts/blueprint-audit.sh` runs in `mvn verify` and a
failing audit blocks the build — new packages and classes must land in their designated boundaries.

**Order of implementation** (each step independently shippable and testable):

1. `V2` + `V3` migrations, entities, repositories. No behaviour change.
2. Lead CRUD + travellers + timeline. The spine.
3. `lead_service`: create, edit, label derivation, date derivation, status, accept, assign.
4. Follow-ups + the `lead` counter sync.
5. Costing: proposal lines, per-service and trip totals, cancellation exclusion.
6. Vouchers + invoices.
7. Trip Schedule endpoint.
8. Public proposal DTO + the absence test.
9. Frontend wiring: replace `lib/mockData.ts` reads with an API client, keep `leadActions.ts` as
   the call surface so component code does not change.

---

## 14. Open questions — I need your call before implementation

**Q1 — Invoices: one table or two?**
Recommendation: extend `client_invoice` (§6.3). The alternative is a separate `lead_invoice`, which
keeps the finance module untouched but permanently splits the answer to "what does this client
owe". *Cost of my recommendation:* four nullable columns on an existing table and one careful list
query. *Cost of the alternative:* a reconciliation problem that never goes away.

**Q2 — `lead_members`' five visa boolean columns.**
V1 has `passport_collected`, `photos_collected`, `forms_filled`, `submitted_to_embassy`,
`visa_approved` on the traveller row. This design moves them onto `lead_service.visa_checklists`.
Since nothing is deployed, my recommendation is to **drop them in V2**. Keeping them means a second
place where the same tick could be written, and one of them will drift. If you prefer additive-only,
we keep them and I will document them as read-only-deprecated, matching how the frontend currently
treats the legacy field.

**Q3 — Does the standalone `visa` table survive?**
V1 has a `visa` table for visa cases raised outside a lead, and the UI has an `/agency/visa` screen
reading it. Once a Visa *service* exists on a lead, these overlap. Options: (a) keep both, with the
`/agency/visa` screen reading a union — costs a union query on one screen; (b) fold standalone
cases into a lead-less `lead_service` row — cleaner model, but `lead_service.lead_id` becomes
nullable and every query in §9 has to think about it. I lean towards (a): the screens genuinely
serve different jobs, and (b) weakens the strongest invariant in the design.

**Q4 — Same question for `booking`.**
V1's `booking` is single-traveller with no `lead_id`, so a multi-pax group lead cannot convert into
one booking. Out of scope for this LLD, but it will surface the moment a lead reaches `BOOKED`.
Flagging it now so it is a decision rather than a surprise.

**Q5 — Soft delete on `lead_service`?**
Currently `CANCELLED` is the terminal state and there is no delete endpoint semantics beyond removing
a service added in error. Confirm that a hard `DELETE /services/{sid}` is acceptable for genuine
mistakes, given that cancelled work is expressed by status rather than deletion (P7).

---

## 15. Explicitly out of scope

Not in this design, and each absent for a reason:

- **Flight and hotel *search*.** The UI has search screens; they are supplier-integration surfaces,
  not lead-management persistence. No schema here.
- **Agent performance metrics** (`bookings`, `conversionPercent`, `revenueGenerated` on the agent).
  These are reporting aggregates and should be computed, not stored on the agent row.
- **File upload internals.** `file_key` is opaque and comes from `FileStorageService`. No filesystem
  path is ever baked into business logic.
- **Package presets.** Currently a category name; if they gain structure they become their own
  module, not a column here.
- **Notifications and reminders** on follow-up due dates. The data supports it
  (`idx_lead_follow_up_agent` on `(agent, status, due_date)`); the delivery mechanism is separate work.
