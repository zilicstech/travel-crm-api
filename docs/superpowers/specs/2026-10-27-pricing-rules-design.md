# Pricing rules instead of per-agency rates (design, 2026-10-27)

## Why

Today a platform admin types a discount % and a commission % for every agency and product, with a
start date. That does not scale past a handful of agencies, and an agency without a rate cannot book
at all (PRICING_NOT_CONFIGURED). The owner wants named, shared rules per product: a Default rule at
0 % that every agency gets, plus a few others ("Silver 3 %", "Standard commission 1 %") the admin
creates, edits and assigns.

## Owner's decisions (2026-10-27)

1. **Separate rules.** Discount rules and commission rules are created separately. Per product
   (FLIGHT, HOTEL) an agency is on exactly one discount rule and one commission rule.
2. **One percentage per rule.** No domestic/international split, no slabs.
3. **Edits apply immediately.** Changing a rule's % changes it for every agency on it, from their
   next booking review. Bookings already reviewed keep their stamped price; if a rule changes
   between review and confirm, confirm answers "price changed" as it does today.
4. **Existing rates become rules.** Each agency's rate in force now becomes a named rule (one per
   distinct value) and the agency is assigned to it, so no price changes. Agencies with no rate
   get the Default rules.
5. A rule an agency is on cannot be deleted; it can be edited. The Default rules can never be
   deleted.

## Model (public migration V33)

- `pricing_rule`: id, kind (DISCOUNT | COMMISSION), product (FLIGHT | HOTEL), name (1-60 chars,
  unique per kind and product ignoring case), pct (0-100, 2 decimals), allow_above_earned (only
  on a commission rule: the agency's commission may exceed Voyra's estimated earning), is_default
  (exactly one per kind and product), version (starts at 1, +1 on every edit that changes pct or
  allow_above_earned), created/updated by and at.
- `agency_pricing_assignment`: (tenant_id, product) primary key, discount_rule_id and
  commission_rule_id (foreign keys, so the database also refuses deleting a rule in use), updated
  by and at. No row means the Default rules.
- Seeded: four Default rules (Discount and Commission, Flight and Hotel) at 0 %.
- `agency_pricing` (the old per-agency rows) is kept untouched as history; nothing writes to it.

## Pricing at booking time

`AgencyPriceQuoter` resolves the agency's two rules for the product (assignment, else Default)
and prices exactly as before with discount % = the discount rule's pct and commission % / allow
above earned = the commission rule's. The `pricingId` stamped into `pricing_snapshot` becomes
`D:<ruleId>:v<version>|C:<ruleId>:v<version>`, so an edit or a reassignment between review and
confirm is caught by the existing REPRICED check. PRICING_NOT_CONFIGURED remains only as a guard
if a Default rule were missing.

## API (all SUPER_ADMIN, under /api/platform, NEVER_GATED)

Rules, `/api/platform/pricing-rules`:
- `GET ?product=FLIGHT|HOTEL` (optional) -> `PricingRuleResponse[]`, Default first, then pct, name.
  `PricingRuleResponse { id, kind, product, name, pct, allowAboveEarned, isDefault, version,
  agencyCount, updatedAt, updatedByName }`. `agencyCount` = agencies explicitly assigned (for a
  Default rule: plus agencies with no assignment for that product).
- `POST { kind, product, name, pct, allowAboveEarned? }` -> 201 rule. Duplicate name 409.
- `PUT /{id} { name, pct, allowAboveEarned? }` -> rule (kind and product never change).
- `DELETE /{id}` -> 204; 409 `{code: RULE_IN_USE}` "Used by N agencies. Move them to another rule
  first." or `{code: DEFAULT_RULE}` for a Default rule.
- `GET /{id}/impact?pct=&allowAboveEarned=` -> `{ agencyCount, lossMaking: [{ tenantId, agencyName,
  worstCaseNetPer10k }] }`: the agencies on this rule whose discount + commission pair would be
  loss-making on the reference basket with the proposed values (shown before an edit is saved).

Agency assignment, `/api/platform/agencies/{id}/pricing`:
- `GET` -> `[{ product, discountRule, commissionRule, worstCaseNetPer10k, lossMaking, assigned,
  updatedAt, updatedByName }]` for FLIGHT and HOTEL (`assigned` false = on the Default rules).
- `PUT /{product} { discountRuleId, commissionRuleId }` -> that item. Each rule must be of the
  right kind and product. Takes effect at once.
- `GET /preview?product=&discountRuleId=&commissionRuleId=` -> `PricingPreviewResponse`
  (`worstCaseNetPer10k`, `basketLines`), as today.
- The old create / approve / withdraw / history routes are removed.

Audit (`platform_audit_log`): PRICING_RULE_CREATE, PRICING_RULE_UPDATE (old and new name and pct,
agencies affected, how many become loss-making), PRICING_RULE_DELETE, PRICING_ASSIGN (agency,
product, both rules with their pct, worst case, lossMaking). Loss-making never blocks: it is
shown and recorded (owner ruling 2026-10-26: one admin decides, the audit log records it).

## Screens (platform admin)

- New sidebar item **Pricing Rules** (`/platform/pricing-rules`): Flight / Hotel tabs; two cards,
  Discount rules and Commission rules, each a table (name, %, agencies, last changed) with Add rule,
  Edit, Delete. Delete is disabled with "Used by N agencies" when in use and hidden for Default.
  The Edit modal shows how many agencies the change reaches and lists any that would become
  loss-making before Save.
- Agency detail page: the pricing card becomes **Pricing rules**: per product, the discount and
  commission rule in force with their %, the worst case per 10,000 and a loss-making badge; Change
  opens two pickers (rules of that product and kind) with a live preview, then Save.

Agencies see no change: search and booking show the displayed fare and "you earn" as before.

## Non-goals

Scheduled (future-dated) rule changes, slabs, domestic/international values, rule history screen
(the audit log has it), agency-visible rule names.
