# Visa configuration and the visa applications sheet — design

Date: 2026-11-24. Status: draft for owner approval. Reviewed adversarially (bmad-review) on 2026-11-24; its findings are folded in.

## Intent

The client runs visas in one Excel sheet per country (screenshots 2026-11-21: a UAE residency sheet, a UAE tourist
sheet covering Dubai / Sharjah / Oman, a Kenya e-visa sheet). Each row is one traveller; columns are the traveller's
passport facts, the documents and steps that country needs (CONTRACT, EVISA, MEDICAL, ILOE, INSURANCE, VACCINE ...),
the agent who did it, the supplier, purchase and selling price, and remarks. They want the CRM to replace the sheets:

- the agency configures, once, the fields a visa application needs: **common fields** (every country) and
  **country fields** (one country), each with a type and a Mandatory switch;
- opening a new application for a country shows the common fields, then that country's fields, and refuses to save
  until the mandatory ones are filled;
- a **visa applications page** is a sheet: built-in columns, then the common fields, then the fields of the country
  picked on the page, with search, filters and editing in place, as they do in Excel today.

## Goals

- Settings > Visa configuration with two sections, Common fields and Country fields.
- Field types: **Text, Dropdown, Date, Yes/No, File**. The owner asked for text and file; the sheets show most columns are
  a few fixed values (RECVD / GIVEN / HOLD / NONE, YES / NO, NEW / EXTENSION 1, NORMAL / EXPRESS, 30 / 60 DAYS) and two
  are dates (passport expiry, visa expiry). Dropdown and Yes/No make filters exact; Date makes expiry sortable. (Our call.)
- One application per traveller (the sheets are per person), linked to the client and the traveller.
- Open Case asks the configured fields for the chosen country; mandatory ones must be filled.
- The list: dynamic columns, server-side search, filters, sort and paging; cells edited in place; CSV export of the
  current filter (the client lives in Excel; our call, small).
- Purchase (with currency), selling, supplier and remarks recorded on the application.

## Non-goals (this round)

- No accounting: purchase / selling do not raise an invoice, supplier bill or ledger entry and do not reach profit
  reports (owner did not ask; recorded only, our call). A later round can post them like a VISA booking.
- Lead visa services (inside a lead, per-traveller checklist) are unchanged.
- No per-visa-type field sets: fields are per country; a destination inside a country sheet (Dubai / Sharjah) is a
  country Dropdown field.
- No bulk import of the existing sheets, no multi-traveller Open Case (one traveller per case; "Open another" keeps the
  client and country).

## Anti-goals

- A field rename or removal must never lose what old applications hold.
- Mandatory added later must not block editing an older application.
- No passenger data in logs.

## Approaches considered

1. **Values as one jsonb map on the visa row, keyed by field id** (picked). One row per application, filters are
   `field_values ->> '<id>'` predicates in one `Specification`, paging stays one query. Files live in their own table
   (key, name, type) because they need storage keys and downloads.
2. Entity-attribute-value table (`visa_field_value(visa_id, field_id, value)`): typed rows, but every filter is an
   EXISTS subquery and every page needs a second query for values. More code, no gain at this size.
3. Reuse `agency_setting` rows (step 5's `VISA_DOCUMENT`): no type, options or mandatory; would need the same columns
   added anyway. Rejected.

## Data (tenant migration V6, rewritten: the old V6 was never released)

The uncommitted step 5 (PAN + per-country document ticks) is folded in: **PAN on the traveller stays**; the per-country
document ticks (`agency_setting.country`, `AgencySettingKind.VISA_DOCUMENT`, `visa.documents`, the lead checklist
`documents` map) are dropped in favour of this design. Local `voyra_crm_dev` already ran the old V6: its history row is
deleted and its two extra columns dropped by hand (local only).

`visa_field`
- `id` varchar(36) PK, `scope` COMMON | COUNTRY, `country` varchar(100) (null for COMMON; spelled as the country picker
  spells it), `label` varchar(60), `type` TEXT | DROPDOWN | DATE | YES_NO | FILE, `options` text[] (Dropdown only, 1-30,
  each 1-40 characters, unique ignoring case), `mandatory` boolean, `sort_order` int, `active` boolean,
  `created_at/by`, `updated_at/by`.
- Unique among active rows: (scope, lower(country), lower(label)). Type cannot change once the field exists (values
  would stop meaning anything); options can be added, renamed (old values are rewritten by one
  `UPDATE visa SET field_values = jsonb_set(...) WHERE field_values ->> id = old` in the same transaction, audited once on
  the field) or removed (old values stay and show as they are). At most 40 active fields per scope and country.
- Delete is soft (`active=false`): the column disappears from Open Case and from the list's column set, but an
  application that holds a value still shows it in its detail under "Removed fields". Adding a field whose label matches
  a removed one of the same scope and country offers to restore it (same id, so old values come back) instead of a new id.
- Country matching is case-insensitive and on the country picker's spelling; Settings and Open Case both use the picker.
  Old cases typed differently simply match no country fields.

`visa` (new nullable columns)
- `member_id` varchar(36), `member_name` varchar(150) (traveller; re-synced on member rename like `client_name`),
  `passport_expiry` date, `nationality` varchar(60) (copied from the traveller at opening and editable: the application
  is filed with that passport; `passport_number` exists already), `field_values` jsonb (field id -> string: text, the
  chosen option, ISO date, `"true"`/`"false"`), `purchase_price` numeric(12,2), `purchase_currency` varchar(3),
  `selling_price` numeric(12,2), `vendor_id` varchar(36), `vendor_name` varchar(150), `remarks` varchar(500),
  `case_no` bigint from a tenant sequence (the sheets' S.NO; existing rows numbered by created date).
- GIN index `jsonb_path_ops` on `field_values` (exact filters are written as containment `field_values @> {"id":"v"}`
  so the index is used; Text "contains" is an unindexed ILIKE, fine at agency sizes); index on (lower(country),
  created_date).
- `version` (optimistic lock): an in-place edit carries the row's version; a stale one is 409 `VISA_CHANGED` and the
  sheet reloads that row. Field writes merge per key (`field_values || :changes`), never replace the whole map.
- Selling price is in the agency's base currency (INR); purchase has its own currency (the Kenya sheet buys in USD).
  Nothing sums across currencies.
- `vendor_name` is re-synced on vendor rename and `member_name` on member rename, as the other snapshots are.

`visa_file`
- `id`, `visa_id`, `field_id`, `file_key`, `file_name`, `file_type`, `uploaded_by/name`, `uploaded_at`;
  unique (visa_id, field_id): one file per File field, a new upload replaces it (old file deleted after commit).
  Storage category `visa-files`, `UploadValidator`, 10 MB.

## Rules

- **Fields for a country** = active COMMON fields by `sort_order`, then active COUNTRY fields of that country by
  `sort_order`.
- **Open Case** (`POST /api/visas`, multipart: `request` JSON + one file part per File field named by field id):
  client, traveller (member of that client), country, visa type, then `values`; every active mandatory field of that
  country must have a non-blank value (File: a file part). Values are checked by type: Dropdown one of its options,
  Date ISO, Yes/No true/false (a mandatory Yes/No needs an explicit choice; blank is not "No"), Text at most 500
  characters; an unknown or inactive field id is 400. Files are validated before any is stored, stored only after the
  row is valid, and deleted again if the transaction rolls back (`TransactionSynchronizationManager`). Errors come back per
  field (`details.errors["values.<id>"]`) so the form marks the field.
- **Editing** (`PATCH /api/visas/{id}/values` `{values, purchasePrice, purchaseCurrency, sellingPrice, vendorId,
  remarks, passportNumber, passportExpiry, nationality}`, partial): only what is sent is checked; a mandatory field
  cannot be cleared; a field made mandatory after the case was opened is not required until someone edits that field.
  Inactive fields cannot be written. Every change goes to the visa's audit history (field label, from, to).
- **Files**: `PUT /api/visas/{id}/files/{fieldId}` (replace), `GET` (download), `DELETE` (refused for a mandatory field).
- **Who**: owner and agents who handle visas open and edit (agents only their own cases, as today; accountants have no visa access today and get none);
  configuration is owner-only. Endpoints gated by the VISA SKU.
- Existing applications keep working: no traveller (shown as the client), blank fields.
- The built-in Status and fixed checklist stay; Settings says so under the sections, so nobody re-creates "Approved" as
  a field. Country cannot change after opening (its fields would no longer apply); a wrong case is marked Rejected with a
  remark (there is no delete today, and this round adds none).

## API

- `GET /api/visa-fields?country=` (fields for that country, for Open Case and the list), `GET /api/visa-fields/all`
  (settings: common plus every country), `GET /api/visa-fields/countries`; owner: `POST`, `PUT /{id}`,
  `DELETE /{id}`, `PUT /order` (ids of one scope + country in the new order).
- `GET /api/visas?page&size&sort&q&country&status&agentId&openedFrom&openedTo&f.<fieldId>=<value>`
  (`q`: traveller, client, passport number, case number; `f.`: Dropdown / Yes/No exact, Text contains, Date
  `f.<id>.from` / `.to`, File `has` / `missing`); sort on built-in columns and on any field (`field.<id>`, empty
  last). Filter and sort ids are checked against the active fields first and always bound as parameters, never written
  into SQL.
- `GET /api/visas/export.csv` with the same filters (visible columns, at most 5000 rows, same scope as the list: an agent
  exports only their own). Cells starting with `=`, `+`, `-` or `@` are prefixed with `'` so Excel never runs them;
  File columns export the file name. The export is audited (who, filter, row count): it carries passport numbers.

## Frontend

- **Settings > Visa configuration** (owner, VISA): card "Common fields" and card "Country fields" with a country picker
  and the countries already set up as chips. Each field row: label, type, options (Dropdown, as tags), Mandatory switch,
  move up / down, delete (confirm). Add field inline.
- **Open Case** (both `/agency/visa` and `/agents/visa`, one shared component): client, traveller (with the traveller's
  passport number, expiry and nationality filled in and editable), country, visa type; once the country is picked, the
  common fields, then the country's, rendered by type, mandatory marked `*`; purchase price + currency, selling price,
  supplier, remarks. "Open and add another" keeps client and country.
- **Applications sheet** (replaces today's table, one shared component): country picker at the top (All countries shows
  built-ins and common fields only); columns: No., Opened, Client, Traveller, Passport no., Passport expiry,
  Nationality, Agent, Status, common fields, country fields, Purchase, Selling, Remarks, actions (checklist, history).
  Search box, Status and Agent filters, a filter per Dropdown / Yes/No field, date ranges; sort by clicking a header;
  paging. Cells of Text / Dropdown / Date / Yes/No edit in place (click, change, saves, error shown on the cell); File
  cells show upload or the file name to download. Rows with status Rejected tint red. Horizontal scroll with the first
  columns pinned. Export CSV.
- The fixed checklist (Passport collected ... Passport returned) and the status it drives stay as they are.

## Delivery

1. Step 5 clean-up (PAN only) + `visa_field` + configuration API + Settings section.
2. Visa columns, Open Case with fields and files, edit API, detail edit.
3. The sheet: dynamic columns, filters, sort, inline edit, export.

Each step: backend `verify` (100 % coverage, audit), frontend tests at 100 %, tsc, lint, build, e2e
`e2e/visa-configuration.spec.ts`; docs in root `CLAUDE.md`, `travel-crm/CLAUDE.md`, `PROGRESS.md`. Push only when asked.

## Left to the builder

Component and class names, the jsonb predicate helper, CSV escaping, exact error texts (house style).
