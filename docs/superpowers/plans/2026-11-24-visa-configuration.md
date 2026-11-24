# Visa configuration and applications sheet Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Agencies configure common and per-country visa fields once; Open Case asks them; the visa page becomes a
filterable, editable sheet with dynamic columns.

**Architecture:** `visa_field` rows (scope COMMON | COUNTRY) define fields; a visa stores values in a jsonb map keyed by
field id plus a `visa_file` table for File fields. List filters and sorts are one JPA `Specification` with jsonb
predicates. Frontend: one settings panel and two shared visa components used by `/agency/visa` and `/agents/visa`.

**Tech Stack:** Spring Boot 3.3 / Java 17 / Postgres (Flyway tenant migration), Next.js 15 / React 19, Vitest,
Playwright.

**Spec:** `travel-crm-backend/docs/superpowers/specs/2026-11-24-visa-configuration-design.md`

## Global Constraints

- Field types `TEXT | DROPDOWN | DATE | YES_NO | FILE`; scope `COMMON | COUNTRY`.
- Label 1-60 characters; Dropdown 1-30 options, each 1-40 characters, unique ignoring case; at most 40 active fields per
  scope and country; type never changes after create.
- Text value at most 500 characters; Date ISO `yyyy-MM-dd`; Yes/No `"true"`/`"false"`; Dropdown one of its options.
- Files: `UploadValidator.assertAllowed`, 10 MB, storage category `visa-files`, one file per (visa, field).
- Remarks at most 500 characters; purchase currency 3 letters; selling in base currency.
- Export at most 5000 rows; cells starting `=`, `+`, `-`, `@` prefixed with `'`.
- Controllers: `VisaController` and new `VisaFieldController` gated `VISA` in `ControllerSkuTable`; config writes owner
  only; visa reads/writes owner + agent (agent only own cases); no accountant access.
- Backend `./mvnw clean verify` must stay at 100 % coverage and blueprint COMPLIANT; frontend `npx vitest run --coverage`
  100 %, `tsc`, lint (no new errors), `npm run build`.
- No passenger data (names, passport numbers) in log lines.

## Review Focus

1. A field renamed / removed / re-added after cases hold values: old values must still show (restore reuses the id).
2. Two people editing the same row: second save is 409 `VISA_CHANGED`, never a silent overwrite.
3. Mandatory field added after cases exist: editing another field of an old case still saves.
4. Filter or sort with an unknown / inactive field id: 400, never SQL built from the id.
5. File upload in Open Case when the row fails validation: no file left in storage.

---

### Task 0: Branch prep and step 5 clean-up

**Files:**
- Revert (both repos, `git checkout -- <file>`): `AgencySettingController`, `AgencySettingCreateRequest`,
  `AgencySettingResponse`, `AgencySetting`, `AgencySettingKind`, `AgencySettingRepository`, `AgencySettingService`,
  `VisaChecklistEntry`, `VisaChecklistToggleRequest`, `VisaChecklistUpdateRequest`, `VisaResponse`, `Visa`,
  `ServiceInstanceService`, `VisaService` and their tests `AgencyControllersWebTest`, `AgencySettingServiceTest`,
  `ServiceInstanceServiceCoverageTest`, `VisaServiceCoverageTest`.
- Keep: PAN (`Member`, `MemberMapper`, `MemberService`, `LeadService`, member DTOs, `LeadMemberResponse`,
  `MemberServiceTest`).
- Rewrite: `src/main/resources/db/migration/tenant/V6__visa_pan_and_checklists.sql` → rename to
  `V6__visa_configuration.sql` (content in Task 1).

- [ ] Revert the listed files; `./mvnw test` passes.
- [ ] Commit PAN on `shivansh_dev` ("Traveller PAN"), then `git rebase origin/main` (backend); frontend
  `git merge --ff-only origin/main`.
- [ ] Local DB: `DELETE FROM <schema>.flyway_schema_history WHERE version='6'` and
  `ALTER TABLE visa DROP COLUMN IF EXISTS documents; ALTER TABLE agency_setting DROP COLUMN IF EXISTS country;
  ALTER TABLE member DROP COLUMN IF EXISTS pan_number;` on every tenant schema of `voyra_crm_dev` (local only).

### Task 1: Field configuration (backend)

**Files:**
- Create: `db/migration/tenant/V6__visa_configuration.sql`, `enums/VisaFieldScope`, `enums/VisaFieldType`,
  `entity/VisaField`, `repository/VisaFieldRepository`, `dto/VisaFieldRequest`, `dto/VisaFieldResponse`,
  `dto/VisaFieldOrderRequest`, `service/VisaFieldService`, `controller/VisaFieldController`
- Modify: `security/ControllerSkuTable` (`entry(VisaFieldController.class, VISA)`), `enums/AuditEntityType` (+`VISA_FIELD`)
- Test: `service/VisaFieldServiceTest`, `controller/VisaFieldControllerWebTest`

Migration V6 (all of it, used by later tasks too):
`member.pan_number varchar(10)`; table `visa_field` (spec columns, `options text[]`, partial unique index on
`(scope, lower(coalesce(country,'')), lower(label)) WHERE active`); `visa` columns `member_id, member_name,
passport_expiry, nationality, field_values jsonb NOT NULL DEFAULT '{}', purchase_price, purchase_currency,
selling_price, vendor_id, vendor_name, remarks, case_no bigint, version bigint NOT NULL DEFAULT 0`; sequence
`visa_case_no_seq`, backfill `case_no` by `created_date, id`, then `case_no` NOT NULL DEFAULT nextval; GIN
`jsonb_path_ops` on `field_values`; index `(lower(country), created_date)`; table `visa_file` (spec columns, unique
`(visa_id, field_id)`).

**Interfaces (produces):**
- `VisaFieldResponse(id, scope, country, label, type, options: List<String>, mandatory, sortOrder, active)`
- `VisaFieldRequest(scope, country, label, type, options, mandatory)`; `VisaFieldOrderRequest(scope, country, ids)`
- `VisaFieldService`: `List<VisaFieldResponse> forCountry(String country)` (active common then country, by sortOrder;
  country null/blank = common only), `List<VisaFieldResponse> all()` (active), `List<String> countries()`,
  `create(req)`, `update(id, req)`, `delete(id)`, `reorder(req)`, and for Task 3:
  `List<VisaField> activeFor(String country)`, `Map<String, VisaField> byIds(Collection<String>)`.
- Endpoints `GET /api/visa-fields?country=`, `GET /api/visa-fields/all`, `GET /api/visa-fields/countries`,
  `POST`, `PUT /{id}`, `DELETE /{id}`, `PUT /order`. Class `@PreAuthorize` owner+agent, writes `hasRole('AGENCY_OWNER')`.

- [ ] Tests first (`VisaFieldServiceTest`): `createsACommonAndACountryField`; `countryFieldNeedsACountry` ("Pick the
  country"); `commonFieldTakesNoCountry` (country stored null); `dropdownNeedsOptions` (0 → 400, 31 → 400, duplicate
  ignoring case → 400, 41 chars → 400); `onlyDropdownTakesOptions`; `labelIsUniquePerScopeAndCountryIgnoringCase` (409
  `VISA_FIELD_EXISTS`); `reAddingARemovedLabelRestoresIt` (same id, active again, new type refused 400); `atMost40`;
  `typeNeverChanges` (400 "A field's type cannot change"); `renamingAnOptionRewritesValues` (verifies repository
  `renameOption(fieldId, old, new)` called; rename detected as same index changed); `deleteIsSoft`;
  `reorderSetsSortOrderInGivenOrder` (ids of another scope/country → 400); `forCountryListsCommonThenCountryCaseInsensitive`;
  every write audited `VISA_FIELD`.
- [ ] Implement; `VisaRepository.renameOption` as native `UPDATE visa SET field_values = jsonb_set(field_values,
  ARRAY[:id], to_jsonb(:new)) WHERE field_values ->> :id = :old`.
- [ ] Web test: agent POST → 403; owner POST → 200; validation 400 shape.
- [ ] `./mvnw test -Dtest='VisaField*'` passes; commit.

### Task 2: Visa configuration settings (frontend)

**Files:**
- Create: `lib/api/services/visaFields.ts`, `components/visa/VisaFieldsSettings.tsx` (+ test)
- Modify: `lib/api/types.ts` (`VisaFieldScope`, `VisaFieldType`, `VisaFieldResponse`, `VisaFieldRequest`),
  `app/agency/settings/page.tsx` (section `visafields` "Visa configuration", shown with VISA)

**Interfaces (produces):** `listVisaFields(country?)`, `listAllVisaFields()`, `listVisaFieldCountries()`,
`createVisaField`, `updateVisaField`, `deleteVisaField`, `reorderVisaFields`.

- [ ] Tests: renders two cards "Common fields" / "Country fields"; add a Dropdown with options (tags), mandatory switch;
  country card needs a country picked (CountryPicker) and lists set-up countries as chips; move up/down calls reorder
  with new id order; delete confirms then calls delete; server error shown inline; note text "Status and the checklist
  are built in" present.
- [ ] Implement, `vitest` passes; commit.

### Task 3: Open Case with fields, edits and files (backend)

**Files:**
- Create: `util/VisaFieldValues` (validation), `entity/VisaFile`, `repository/VisaFileRepository`,
  `dto/VisaValuesUpdateRequest`, `dto/VisaFileResponse`
- Modify: `entity/Visa` (Task 1 columns, `@Version`), `dto/VisaCreateRequest` (`memberId` required, `passportExpiry`,
  `nationality`, `values: Map<String,String>`, `purchasePrice`, `purchaseCurrency`, `sellingPrice`, `vendorId`,
  `remarks`), `dto/VisaResponse` (+ those, `caseNo`, `memberId/Name`, `values`, `files: List<VisaFileResponse>`,
  `version`), `service/VisaService`, `controller/VisaController`, `service/MemberService` (+member rename resync),
  vendor rename path (+`visaRepository.updateVendorName`), `repository/VisaRepository`
- Test: `util/VisaFieldValuesTest`, `service/VisaServiceFieldsTest`, `controller/VisaControllerWebTest`

**Interfaces:**
- `VisaFieldValues.check(Map<String,String> values, Map<String,VisaField> fields, Set<String> filesGiven, boolean
  creating, Collection<VisaField> mandatory)` → throws `FieldValidationException` with keys `values.<id>`.
- `POST /api/visas` multipart: part `request` (JSON) + file parts named by field id; JSON-only POST stays accepted
  (no files) for old clients.
- `PATCH /api/visas/{id}/values` `VisaValuesUpdateRequest(version, values, passportNumber, passportExpiry, nationality,
  purchasePrice, purchaseCurrency, sellingPrice, vendorId, remarks)`; null = untouched, `""` value = clear.
- `PUT /api/visas/{id}/files/{fieldId}` (multipart `file`), `GET` (download), `DELETE`.

- [ ] Tests: `VisaFieldValuesTest` per type (bad option, bad date, bad yes/no, 501 text, unknown id, inactive id,
  mandatory missing on create incl. FILE without a part, mandatory blank Yes/No refused);
  `VisaServiceFieldsTest`: create copies member passport facts when not given, member must belong to client (400),
  values merged per key on patch, stale version → 409 `VISA_CHANGED`, clearing a mandatory → 400, mandatory added later
  not required when patching another field, audit change lists field label from→to, files stored only after validation
  and deleted on rollback (assert `fileStorage.delete` registered via synchronization), file replace deletes old after
  commit, DELETE of a mandatory file → 400, agent cannot touch another agent's case (403), rename member/vendor
  resyncs snapshots.
- [ ] Implement; commit.

### Task 4: Open Case form (frontend)

**Files:**
- Create: `components/visa/VisaFieldInput.tsx` (one input per type), `components/visa/OpenVisaCaseModal.tsx` (+ tests)
- Modify: `lib/api/services/visas.ts` (`createVisa(body, files)` via `apiUpload`, `updateVisaValues`,
  `uploadVisaFile`, `deleteVisaFile`, `visaFileUrl`), types, `app/agency/visa/page.tsx`, `app/agents/visa/page.tsx`
  (use the modal), `test/fe-7/visas.ts`

- [ ] Tests: traveller select lists the client's members (`listMembers`) and fills passport no., expiry, nationality;
  picking a country loads `listVisaFields(country)` and renders common then country fields with `*` on mandatory;
  missing mandatory shows per-field errors from the server's `values.<id>`; File field sends the file under its id;
  "Open and add another" keeps client and country and clears the rest; purchase currency defaults INR.
- [ ] Implement; commit.

### Task 5: List, filters, sort and export (backend)

**Files:**
- Create: `repository/spec/VisaSpecifications`, `models/VisaListFilter`, `service/VisaExportService`
- Modify: `VisaController` (list params, `GET /export.csv`), `VisaService.listVisas(filter, pageable)`
- Test: `repository/spec/VisaSpecificationsTest` (CriteriaRecorder), `service/VisaExportServiceTest`, web test

**Interfaces:** `VisaListFilter(q, country, status, agentId, openedFrom, openedTo, Map<String,String> fieldFilters)`;
query params `f.<id>`, `f.<id>.from`, `f.<id>.to`; sort `caseNo, createdDate, clientName, memberName, status,
passportExpiry, field.<id>` (`field.<id>` sorted via `jsonb_extract_path_text`, nulls last).

- [ ] Tests: q matches member name, client name, passport number, case number; Dropdown/Yes-No filter uses
  containment; Text contains ignores case; Date range; File `has`/`missing` (EXISTS on visa_file); unknown/inactive
  field id in filter or sort → 400; agent scope always applied; export CSV header = built-ins + common + country fields,
  formula cells prefixed `'`, 5001 rows → only 5000 and header `X-Truncated: true`, export audited.
- [ ] Implement; commit.

### Task 6: The applications sheet (frontend)

**Files:**
- Create: `components/visa/VisaSheet.tsx`, `components/visa/VisaCell.tsx` (+ tests)
- Modify: `lib/api/services/visas.ts` (`listVisasPage(params)`, `exportVisasCsv(params)`), both visa pages (render
  `VisaSheet`), keep `VisaChecklistModal` and history actions

- [ ] Tests: country picker switches columns (common always, country after); header click sorts; status, agent and
  per-Dropdown filters build `f.<id>` params; search debounced; paging; a Dropdown cell edits in place and sends
  `version`; 409 shows "Changed by someone else" and reloads the row; File cell uploads / downloads; Rejected rows tinted
  red; Export calls `apiDownloadCsv` with the current params; agents page hides the Agent filter.
- [ ] Implement; commit.

### Task 7: E2e, docs, gates

- [ ] `e2e/visa-configuration.spec.ts`: owner adds common Text (mandatory) + UAE Dropdown MEDICAL (RECVD, PENDING);
  agent opens a UAE case, mandatory blocks, then saves; sheet shows both columns; filter MEDICAL=PENDING; edit cell to
  RECVD; export downloads.
- [ ] Docs: root `CLAUDE.md` bullet, `travel-crm/CLAUDE.md`, `PROGRESS.md` section "Visa configuration 2026-11-24".
- [ ] Gates: backend `./mvnw clean verify`; frontend coverage, tsc, lint, build; related e2e. Commit; push only when asked.
