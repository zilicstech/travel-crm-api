# B2B Platform — Slice 1: Foundation — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Agencies apply through a public form with KYC documents; a platform admin reviews, approves (which provisions the tenant), suspends or deactivates them, sets per-agency discount/commission pricing with a margin guard, and holds the one platform-level Tripjack credential that every search uses.

**Architecture:** All new state is public-schema (`agency_application`, `kyc_document`, `agency_pricing`, `platform_supplier_credential`, `platform_setting`, `platform_audit_log`) following the `Inquiry` pattern (36-char ids from `UniqueIdResolver`, `@Builder` entities, `@Data @Builder` DTOs with `@Schema` on every field). `tenant.status` becomes the single liveness truth; `TenantCache` carries it. Per-tenant Tripjack credentials are deleted and both search providers read the platform credential. No wallet, booking or SKU code in this slice (slices 2–5).

**Tech Stack:** Spring Boot 3.3 / Java 17 / Flyway / Mockito + `@WebMvcTest`; Next.js 15 / React 19 / `components/ui` primitives / Playwright.

**Spec:** `/Users/shivanshsrivastava/Projects/Voyra global/B2B_PLATFORM_DESIGN.md` (§3, §6, §7.1, §9 Platform/Public, §10, §11 slice 1, §12). This plan deviates from the spec in three documented places: wallets, subscriptions and the "Voyra Platform" vendor/ledger seeding move to slices 2, 5 and 3 respectively (each table ships with the code that uses it); KYC documents are served by an authenticated streaming endpoint (the repo's pattern) instead of signed URLs; `tenant.is_active` is kept as a derived column rather than dropped (10 readers).

**Branch:** `feat/b2b-platform` off `nitin-dev` in both repos (one branch per repo for the whole B2B batch; never push to main — PRs only).

**Path shorthand:** `BE` = `travel-crm-backend/src/main/java/com/voyra/crm`, `BT` = `travel-crm-backend/src/test/java/com/voyra/crm`, `PM` = `travel-crm-backend/src/main/resources/db/migration/public`, `TM` = `.../db/migration/tenant`, `FE` = `travel-crm`.

## Global Constraints

- Blueprint audit (`scripts/blueprint-audit.sh`, runs in `mvn verify`): every controller has `@PreAuthorize` unless listed in the exempt `case` (add `PublicAgencyApplicationController`); no entity class name inside `ResponseEntity<…>` (add new entity names to the §7.3 allowlist regex); no `Repository`/`try {` in controllers; no `@Autowired`; no JPA relationship annotations (`@ManyToOne` etc.) — ids only; no `double`/`float` fields; **every DTO field has exactly one `@Schema`**; no `SecurityContextHolder` in service/controller (use `SecurityContextUtil`).
- Money: `BigDecimal`, columns `NUMERIC(19,2)`; percentages `NUMERIC(5,2)` in 0–100.
- Ids: `VARCHAR(36)` assigned via `UniqueIdResolver.resolve(repo::existsById)`; timestamps `TIMESTAMPTZ`, Java `Instant`.
- Next public migration is `V16` (V13 is a gap, keep it); next tenant migration is `V77`.
- Encryption: `PasswordEncryptionUtil.encrypt/decrypt(value, key)` with `app.security.password-encryption-key`.
- Public endpoints live under `/api/public/**` (permitAll in `SecurityConfig`); platform endpoints under `/api/platform/**` with `@PreAuthorize("hasRole('SUPER_ADMIN')")`.
- Uploads: `UploadValidator.assertAllowed(file)` (extension + magic bytes); KYC files additionally ≤ 5 MB.
- Frontend: every new screen follows `travel-crm/UI_BLUEPRINT.md` (teal via `blue-*`, `Card`/`Table`/`Badge`/`Modal`/`EmptyState`, `.page-header`); public pages get a sibling `layout.tsx` with metadata and a `sitemap.ts` entry; API calls go through `lib/api/client.ts` and a `lib/api/services/*.ts` module; DTO mirrors go in `lib/api/types.ts`.
- Tests: service tests `@ExtendWith(MockitoExtension.class)` + `@InjectMocks`; controller tests `@WebMvcTest(X.class) @ActiveProfiles("test") @Import({SecurityConfig.class, JwtAuthenticationFilter.class, JwtService.class, RestAuthenticationEntryPoint.class})` with `@MockBean` services and `.with(user("root").roles("SUPER_ADMIN"))`; Playwright via `e2e/fixtures.ts` (`loginAs`, `apiPost`, `PLATFORM_ADMIN`, `OWNER`).
- Commits: plain prose; end with `Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>` and `Claude-Session: https://claude.ai/code/session_01Qq6uXt8Qha7QvtoJUmYRa9`.

## Review Focus

1. **Re-applying with an email that already belongs to an active tenant** — must be refused at submit with the field error on `ownerEmail`, not discovered at approve. Test in Task 4.
2. **Approve clicked twice / two admins approving the same application** — exactly one tenant and one schema; second call gets 409. Test in Task 5.
3. **Upload token reuse** — a leaked application id without its upload token must not let anyone attach files; expired token (24 h) refused. Test in Task 4.
4. **Suspended agency's agent logs in** — gets a token (read-only is enforced by the interceptor), while a deactivated agency's agent is refused at login like the owner. Test in Task 2.
5. **Pricing row made effective while a search is open** — out of scope for this slice (no booking yet) but the resolver must return the row in force *at the instant asked*, never a future row; a pricing row with `approved_at IS NULL` is never in force. Test in Task 8.

---

### Task 1: Public migration V16 — platform tables and tenant status

**Files:**
- Create: `PM/V16__b2b_platform_foundation.sql`
- Create: `TM/V77__drop_tenant_supplier_credentials.sql`
- Test: Flyway runs at app start under the `test` profile (`AbstractIntegrationTest` for Docker ITs); verify with `rtk ./mvnw test -Dtest=ApplicationContextTest` (create it if absent — a `@SpringBootTest @ActiveProfiles("test")` that only asserts the context loads is enough and exists in most slices as `VoyraCrmApplicationTests`).

**Interfaces:**
- Produces: tables below, consumed by every later task.

- [ ] **Step 1: Write `V16__b2b_platform_foundation.sql`**

```sql
-- tenant liveness: single truth is status; is_active stays as a derived mirror for existing readers
ALTER TABLE tenant ADD COLUMN status VARCHAR(12) NOT NULL DEFAULT 'ACTIVE';
UPDATE tenant SET status = CASE WHEN is_active THEN 'ACTIVE' ELSE 'DEACTIVATED' END;
ALTER TABLE tenant ADD CONSTRAINT chk_tenant_status CHECK (status IN ('ACTIVE','SUSPENDED','DEACTIVATED'));
ALTER TABLE tenant ADD COLUMN pan VARCHAR(10), ADD COLUMN cin VARCHAR(30), ADD COLUMN trade_name VARCHAR(200),
    ADD COLUMN approved_at TIMESTAMPTZ, ADD COLUMN approved_by VARCHAR(36), ADD COLUMN booking_blocked BOOLEAN NOT NULL DEFAULT false;

CREATE TABLE agency_application (
    id VARCHAR(36) PRIMARY KEY,
    status VARCHAR(14) NOT NULL,            -- DRAFT|SUBMITTED|UNDER_REVIEW|MORE_INFO|APPROVED|REJECTED
    agency_name VARCHAR(150) NOT NULL, legal_name VARCHAR(200), trade_name VARCHAR(200),
    pan VARCHAR(10) NOT NULL, gstin VARCHAR(15), cin VARCHAR(30),
    address VARCHAR(500), city VARCHAR(100), state_code VARCHAR(2), pincode VARCHAR(10),
    owner_name VARCHAR(150) NOT NULL, owner_email VARCHAR(150) NOT NULL, owner_phone VARCHAR(30) NOT NULL,
    expected_monthly_volume VARCHAR(40), base_currency_code VARCHAR(3) NOT NULL DEFAULT 'INR',
    consent_at TIMESTAMPTZ, upload_token_hash VARCHAR(64) NOT NULL, upload_token_expires_at TIMESTAMPTZ NOT NULL,
    submitted_at TIMESTAMPTZ, reviewed_by VARCHAR(36), reviewed_at TIMESTAMPTZ, review_note VARCHAR(2000),
    rejection_reason VARCHAR(2000), tenant_id VARCHAR(36), created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_agency_application_status_created ON agency_application (status, created_at DESC);
CREATE INDEX idx_agency_application_owner_email ON agency_application (LOWER(owner_email));

CREATE TABLE kyc_document (
    id VARCHAR(36) PRIMARY KEY, application_id VARCHAR(36) NOT NULL,
    type VARCHAR(20) NOT NULL,              -- PAN|GST|BUSINESS_PROOF|OWNER_ID|CANCELLED_CHEQUE|ADDRESS_PROOF|IATA|OTHER
    file_key VARCHAR(500) NOT NULL, file_name VARCHAR(255) NOT NULL, content_type VARCHAR(100), size_bytes BIGINT NOT NULL,
    status VARCHAR(10) NOT NULL DEFAULT 'PENDING',   -- PENDING|VERIFIED|REJECTED
    note VARCHAR(1000), reviewed_by VARCHAR(36), reviewed_at TIMESTAMPTZ, created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_kyc_document_application ON kyc_document (application_id);

CREATE TABLE agency_pricing (
    id VARCHAR(36) PRIMARY KEY, tenant_id VARCHAR(36) NOT NULL, product VARCHAR(10) NOT NULL,   -- FLIGHT|HOTEL
    discount_pct NUMERIC(5,2) NOT NULL, commission_pct NUMERIC(5,2) NOT NULL,
    allow_commission_above_earned BOOLEAN NOT NULL DEFAULT false,
    effective_from TIMESTAMPTZ NOT NULL, worst_case_net_per_10k NUMERIC(19,2) NOT NULL,
    requires_second_approval BOOLEAN NOT NULL, created_by VARCHAR(36) NOT NULL, created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    approved_by VARCHAR(36), approved_at TIMESTAMPTZ,
    CONSTRAINT uq_agency_pricing UNIQUE (tenant_id, product, effective_from)
);

CREATE TABLE platform_supplier_credential (
    id VARCHAR(36) PRIMARY KEY, provider VARCHAR(30) NOT NULL, environment VARCHAR(10) NOT NULL,
    base_url VARCHAR(255) NOT NULL, api_key_encrypted VARCHAR(1024) NOT NULL, user_id_encrypted VARCHAR(1024),
    is_active BOOLEAN NOT NULL DEFAULT false, updated_by VARCHAR(36), created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX uq_platform_supplier_credential_active ON platform_supplier_credential (provider) WHERE is_active;

CREATE TABLE platform_setting (key VARCHAR(60) PRIMARY KEY, value VARCHAR(4000) NOT NULL, updated_by VARCHAR(36), updated_at TIMESTAMPTZ NOT NULL DEFAULT now());
INSERT INTO platform_setting (key, value) VALUES
  ('discount_base', 'GROSS'),
  ('tds_rate', '0'),
  ('commission_estimate_pct', '5'),
  ('reference_fare_basket', '[{"label":"Domestic LCC","gross":5000,"base":3500},{"label":"Domestic FSC","gross":9000,"base":6000},{"label":"International","gross":45000,"base":22000}]');

CREATE TABLE platform_audit_log (
    id VARCHAR(36) PRIMARY KEY, actor_id VARCHAR(36) NOT NULL, actor_name VARCHAR(150) NOT NULL,
    action VARCHAR(30) NOT NULL, entity_type VARCHAR(30) NOT NULL, entity_id VARCHAR(36) NOT NULL,
    detail JSONB, created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_platform_audit_entity ON platform_audit_log (entity_type, entity_id, created_at DESC);
```

- [ ] **Step 2: Write `V77__drop_tenant_supplier_credentials.sql`**

```sql
-- Supplier credentials are platform-level from here on (B2B_PLATFORM_DESIGN.md §10); agency rows are removed, the table is left for Flyway history.
DELETE FROM supplier_credential;
```

- [ ] **Step 3: Boot the app against the local DB and confirm both migrations apply**

Run: `cd travel-crm-backend && rtk ./mvnw test -Dtest=VoyraCrmApplicationTests` (context-load test). Expected: PASS, log shows `Successfully applied 1 migration` for public V16 and the tenant catch-up runner applying V77 to each existing tenant.

- [ ] **Step 4: Commit** — `feat(platform): public tables for applications, KYC, pricing, platform credential, settings and audit; retire per-tenant supplier credentials`

---

### Task 2: `tenant.status` as the single liveness truth

**Files:**
- Modify: `BE/entity/Tenant.java` (add `status`, `pan`, `cin`, `tradeName`, `approvedAt`, `approvedBy`, `bookingBlocked`)
- Create: `BE/enums/TenantStatus.java` — `ACTIVE, SUSPENDED, DEACTIVATED`
- Modify: `BE/cache/TenantCache.java`, `BE/config/CacheLoader.java:35`
- Modify: `BE/security/JwtAuthenticationFilter.java:56-62`
- Modify: `BE/service/AuthService.java:110,128`
- Modify: `BE/service/AgencyService.java` (`updateStatus`, `toResponse`), `BE/controller/PlatformAgencyController.java:58`, `BE/dto/AgencyResponse.java`, create `BE/dto/TenantStatusUpdateRequest.java`
- Create: `BE/security/TenantStatusInterceptor.java`, `BE/config/WebMvcConfig.java`
- Test: `BT/cache/TenantCacheTest.java`, `BT/service/AuthServiceTenantStatusTest.java`, `BT/security/TenantStatusInterceptorTest.java`, modify `BT/service/AgencyServiceTest.java` if present (else create)

**Interfaces:**
- Produces: `TenantStatus`; `TenantCache.put(String tenantId, String name, TenantStatus status)`, `TenantCache.status(String tenantId): Optional<TenantStatus>`, `TenantCache.isValidTenant(String)` (= present and ≠ DEACTIVATED); `Tenant.setStatus(TenantStatus)` also sets `isActive = status != DEACTIVATED`; `AgencyService.updateStatus(String id, TenantStatus status): AgencyResponse`; `AgencyResponse.status: TenantStatus` (keep `isActive` for the frontend's existing readers); `PATCH /api/platform/agencies/{id}/status` body `{ "status": "SUSPENDED" }`.
- Consumes: Task 1 columns.

- [ ] **Step 1: Write the failing cache test** (`BT/cache/TenantCacheTest.java`)

```java
@Test void suspendedTenantIsStillValidForTheFilter() {
    TenantCache.put("t1", "A", TenantStatus.SUSPENDED);
    assertTrue(TenantCache.isValidTenant("t1"));
    assertEquals(Optional.of(TenantStatus.SUSPENDED), TenantCache.status("t1"));
}
@Test void deactivatedTenantIsInvalid() {
    TenantCache.put("t2", "B", TenantStatus.DEACTIVATED);
    assertFalse(TenantCache.isValidTenant("t2"));
}
```

- [ ] **Step 2: Run it** — `rtk ./mvnw test -Dtest=TenantCacheTest` → FAIL (no `TenantStatus`, wrong `put` signature).

- [ ] **Step 3: Implement** `TenantStatus`; `TenantCache.TenantInfo(String name, TenantStatus status)`, `put(..., TenantStatus)`, `status(id)`, `isValidTenant` = status ≠ DEACTIVATED, `TenantEntry(tenantId, name, status)`; `CacheLoader` maps `t.getStatus()`; `Tenant.status` `@Enumerated(STRING) @Column(length=12)` with a `setStatus` that mirrors `isActive`. Remove the three-arg boolean `put` and fix all callers (`AgencyService` ×2, `CacheLoader`).

- [ ] **Step 4: Write the failing auth tests** (`BT/service/AuthServiceTenantStatusTest.java`, Mockito, same mocks `AuthService` already uses)

```java
@Test void ownerOfASuspendedAgencyCanLogIn()            // tenant.status = SUSPENDED → response.success == true
@Test void ownerOfADeactivatedAgencyIsRefused()         // DEACTIVATED → success == false, message == GENERIC_FAILURE
@Test void agentOfADeactivatedAgencyIsRefused()         // agent.isActive true, tenant DEACTIVATED → success == false
@Test void agentOfASuspendedAgencyCanLogIn()
```

- [ ] **Step 5: Run** → FAIL. **Implement**: `AuthService.tryOwner` line 110 checks `tenant.getStatus() == DEACTIVATED`; `tryAgent` (line 128) additionally loads the agent's tenant via `tenantRepository.findById(agent.getTenantId())` and refuses when DEACTIVATED.

- [ ] **Step 6: Write the failing interceptor test** (`BT/security/TenantStatusInterceptorTest.java`, `@WebMvcTest` on an existing simple tenant controller such as `VendorController`, `@MockBean` its service)

```java
@Test void suspendedTenantGets423OnWrites()   // TenantCache.put("T1","A",SUSPENDED); POST /api/vendors as AGENCY_OWNER with tenant T1 → 423, body code "TENANT_SUSPENDED"
@Test void suspendedTenantCanStillRead()      // GET /api/vendors → 200
@Test void authRoutesAreNeverBlocked()        // POST /api/auth/login/owner → not 423
```
(Principal with tenant: build a JWT via `JwtService` for a `CustomUserPrincipal(userId,"owner",AGENCY_OWNER,"T1")` and send `Authorization: Bearer …`, as the filter tests do.)

- [ ] **Step 7: Run** → FAIL. **Implement** `TenantStatusInterceptor implements HandlerInterceptor`: `preHandle` returns true for GET/HEAD/OPTIONS, for paths starting `/api/auth/`, `/api/public/`, `/api/platform/`, and when `TenantContext.getTenantId()` is null; otherwise if `TenantCache.status(tenantId) == SUSPENDED` → `response.sendError(423)` with JSON `{"code":"TENANT_SUSPENDED","message":"This agency is suspended. Contact Voyra."}` (write via `ObjectMapper`). Register in `WebMvcConfig implements WebMvcConfigurer` for `/api/**`. Slice 5 extends this class into the entitlement interceptor.

- [ ] **Step 8: Status endpoint** — `TenantStatusUpdateRequest { @NotNull TenantStatus status }` (with `@Schema`); `PlatformAgencyController.updateStatus` takes it; `AgencyService.updateStatus(id, status)` sets status, `TenantCache.put` in the same method, `log.info`. `AgencyResponse` gains `status`. Keep `ActiveStatusUpdateRequest` only if another controller uses it (grep; else delete). Update `BT/controller/PlatformAgencyControllerTest` (or create) with: `PATCH /status {"status":"SUSPENDED"}` as SUPER_ADMIN → 200 with `"status":"SUSPENDED"`; as AGENCY_OWNER → 403.

- [ ] **Step 9: Run the whole suite** — `rtk ./mvnw test` → PASS; `./scripts/blueprint-audit.sh` → COMPLIANT.

- [ ] **Step 10: Commit** — `feat(platform): tenant status (ACTIVE/SUSPENDED/DEACTIVATED) replaces the boolean; suspended agencies sign in read-only`

---

### Task 3: Platform audit log

**Files:**
- Create: `BE/entity/PlatformAuditLog.java`, `BE/repository/PlatformAuditLogRepository.java`, `BE/service/PlatformAuditService.java`, `BE/enums/PlatformAuditAction.java`
- Test: `BT/service/PlatformAuditServiceTest.java`

**Interfaces:**
- Produces: `PlatformAuditService.record(PlatformAuditAction action, String entityType, String entityId, Map<String,Object> detail): void` — actor from `SecurityContextUtil.getCurrentUserOrThrow()` (`userId`, `username`); never touches `AuthorResolver`. `PlatformAuditAction`: `KYC_DOCUMENT_VIEW, APPLICATION_DECISION, APPLICATION_APPROVE, TENANT_STATUS, PRICING_CREATE, PRICING_APPROVE, CREDENTIAL_SET, CREDENTIAL_TEST, SETTING_SET`. `PlatformAuditLogRepository.findByEntityTypeAndEntityIdOrderByCreatedAtDesc(String, String): List<PlatformAuditLog>`.

- [ ] **Step 1: Failing test**

```java
@Test void recordsTheSuperAdminAsActorWithoutResolvingATenant() {
    authenticateAs("root-1", UserType.SUPER_ADMIN);           // helper as in SupplierCredentialServiceTest
    service.record(PlatformAuditAction.PRICING_CREATE, "AGENCY_PRICING", "p1", Map.of("discountPct", 10));
    ArgumentCaptor<PlatformAuditLog> c = ArgumentCaptor.forClass(PlatformAuditLog.class);
    verify(repository).save(c.capture());
    assertEquals("root-1", c.getValue().getActorId());
    assertEquals(PlatformAuditAction.PRICING_CREATE, c.getValue().getAction());
    assertEquals(10, c.getValue().getDetail().get("discountPct"));
}
```

- [ ] **Step 2: Run** → FAIL. **Implement** entity (`detail` as `Map<String,Object>` with `@JdbcTypeCode(SqlTypes.JSON)`, like `AuditLog.fieldChanges`), repository, service (`@Transactional`). **Run** → PASS. **Commit** — `feat(platform): platform audit log written by super admins`

---

### Task 4: Agency applications — public submit + KYC uploads

**Files:**
- Create: `BE/entity/AgencyApplication.java`, `BE/entity/KycDocument.java`, `BE/enums/ApplicationStatus.java`, `BE/enums/KycDocumentType.java`, `BE/enums/KycDocumentStatus.java`
- Create: `BE/repository/AgencyApplicationRepository.java`, `BE/repository/KycDocumentRepository.java`
- Create: `BE/service/AgencyApplicationService.java`, `BE/dto/AgencyApplicationCreateRequest.java`, `BE/dto/AgencyApplicationCreatedResponse.java`, `BE/dto/AgencyApplicationResponse.java`, `BE/dto/KycDocumentResponse.java`
- Create: `BE/controller/PublicAgencyApplicationController.java`
- Modify: `BE/util/UploadValidator.java` (add `assertAllowed(MultipartFile, long maxBytes)`), `scripts/blueprint-audit.sh` (exempt list + §7.3 allowlist)
- Test: `BT/service/AgencyApplicationServiceTest.java`, `BT/controller/PublicAgencyApplicationControllerTest.java`

**Interfaces:**
- Produces:
  - `POST /api/public/agency-applications` body `AgencyApplicationCreateRequest { agencyName, legalName?, tradeName?, pan (@Pattern "[A-Z]{5}[0-9]{4}[A-Z]"), gstin?, cin?, address?, city?, stateCode?, pincode?, ownerName, ownerEmail (@Email), ownerPhone (@Pattern ValidationPatterns.PHONE), expectedMonthlyVolume?, baseCurrencyCode (default INR), consent (@AssertTrue), website (honeypot) }` → `201 AgencyApplicationCreatedResponse { id, uploadToken }` (status DRAFT). Rate-limited with `InquiryRateLimiter`.
  - `POST /api/public/agency-applications/{id}/documents` multipart `file` + param `type` + header `X-Upload-Token` → `KycDocumentResponse { id, type, fileName, sizeBytes, status }`.
  - `POST /api/public/agency-applications/{id}/submit` header `X-Upload-Token` → `SimpleAckResponse`; requires ≥ 1 document of type PAN → status SUBMITTED, `submittedAt`.
  - `AgencyApplicationService`: `create(req): AgencyApplicationCreatedResponse`; `attachDocument(id, token, KycDocumentType, MultipartFile): KycDocumentResponse`; `submit(id, token): void`; `assertUploadToken(AgencyApplication, String)` (SHA-256 hex compare, expiry 24 h); `static final String FILE_SCOPE = "platform"`, `FILE_CATEGORY = "kyc"` → `fileStorageService.store(FILE_SCOPE, FILE_CATEGORY, applicationId, file)`.
  - `AgencyApplicationRepository`: `Page<AgencyApplication> findByStatusInOrderBySubmittedAtDesc(Collection<ApplicationStatus>, Pageable)`, `Optional<AgencyApplication> findFirstByOwnerEmailIgnoreCaseAndStatusIn(String, Collection<ApplicationStatus>)`, `List<AgencyApplication> findByStatusAndUpdatedAtBefore(ApplicationStatus, Instant)`.
  - `KycDocumentRepository`: `List<KycDocument> findByApplicationIdOrderByCreatedAt(String)`, `Optional<KycDocument> findByIdAndApplicationId(String, String)`, `long countByApplicationIdAndType(String, KycDocumentType)`.
- Consumes: Task 1 tables; `InquiryRateLimiter`; `FileStorageService`; `UploadValidator`; `LoginEmailService.assertFree`.

- [ ] **Step 1: Failing service tests** (`AgencyApplicationServiceTest`, mocks: repositories, `FileStorageService`, `LoginEmailService`, `PlatformAuditService`)

```java
@Test void createRefusesAnEmailAlreadyUsedByALogin()        // assertFree throws IllegalArgumentException → propagates (field ownerEmail)
@Test void createRefusesAnEmailWithAnOpenApplication()      // findFirstByOwnerEmailIgnoreCaseAndStatusIn(email, {DRAFT,SUBMITTED,UNDER_REVIEW,MORE_INFO}) present → IllegalArgumentException "An application for this email is already under review"
@Test void createReturnsARawTokenAndStoresOnlyItsHash()     // response.uploadToken length ≥ 32; saved.uploadTokenHash equals sha256Hex(token); expiresAt ≈ now+24h
@Test void honeypotFilledReturnsAFakeSuccessWithoutSaving() // website != "" → response with random id, repository.save never called
@Test void attachRefusesAWrongToken()                       // → AccessDeniedException
@Test void attachRefusesAnExpiredToken()
@Test void attachRefusesAFileOver5Mb()                      // MockMultipartFile 5*1024*1024+1 bytes → IllegalArgumentException
@Test void attachStoresUnderThePlatformScope()               // verify(fileStorageService).store("platform","kyc",appId,file)
@Test void submitNeedsAPanDocument()                        // countByApplicationIdAndType(..,PAN)==0 → IllegalStateException "Upload the PAN card first"
@Test void submitMovesDraftToSubmitted()                    // status SUBMITTED, submittedAt set
@Test void reapplyAfterRejectionReusesTheRow()              // findFirst… with {REJECTED} present → same id updated to DRAFT, new token
```

- [ ] **Step 2: Run** → FAIL. **Implement** entities (`@Builder`, `@PrePersist`/`@PreUpdate` timestamps, enums STRING), repositories, DTOs (every field `@Schema`), service (`@Transactional`; token = 32 random bytes base64url via `SecureRandom`; hash with `MessageDigest` SHA-256 hex). `UploadValidator.assertAllowed(file, maxBytes)` throws `IllegalArgumentException("File is larger than 5 MB")`.

- [ ] **Step 3: Run** → PASS.

- [ ] **Step 4: Failing controller test** (`PublicAgencyApplicationControllerTest`, `@MockBean AgencyApplicationService, InquiryRateLimiter`)

```java
@Test void createIsOpenToAnonymousAndRateLimited()   // POST without auth → 201; verify(inquiryRateLimiter).check(anyString())
@Test void createValidatesPanFormat()               // pan "abc" → 400
@Test void uploadRequiresTheTokenHeader()           // multipart without X-Upload-Token → 400
@Test void submitAcks()                              // → 200 {"success":true}
```

- [ ] **Step 5: Run** → FAIL. **Implement** controller: `@RequestMapping("/api/public/agency-applications")`, no `@PreAuthorize`; add it to the exempt `case` in `scripts/blueprint-audit.sh` line 19 and `AgencyApplication|KycDocument` to the §7.3 allowlist. **Run** `rtk ./mvnw test` + audit → PASS/COMPLIANT.

- [ ] **Step 6: Commit** — `feat(platform): agencies apply publicly with KYC documents behind an upload token`

---

### Task 5: Platform review — list, decide, approve (provision tenant)

**Files:**
- Modify: `BE/service/AgencyApplicationService.java`, `BE/service/AgencyService.java` (extract provisioning)
- Create: `BE/controller/PlatformAgencyApplicationController.java`, `BE/dto/ApplicationDecisionRequest.java`, `BE/dto/KycDocumentDecisionRequest.java`, `BE/dto/ApplicationApproveRequest.java`
- Create: `BE/scheduler/AgencyApplicationPurgeJob.java`
- Test: extend `BT/service/AgencyApplicationServiceTest.java`; `BT/controller/PlatformAgencyApplicationControllerTest.java`; `BT/service/AgencyServiceProvisionTest.java`; `BT/scheduler/AgencyApplicationPurgeJobTest.java`

**Interfaces:**
- Produces:
  - `GET /api/platform/agency-applications?status=&page=&size=` → `PagedResponse<AgencyApplicationResponse>` (default statuses SUBMITTED, UNDER_REVIEW, MORE_INFO).
  - `GET /api/platform/agency-applications/{id}` → `AgencyApplicationResponse` incl. `documents: List<KycDocumentResponse>`, `tenantId`.
  - `GET /api/platform/agency-applications/{id}/documents/{docId}/file` → streams the file (`ResponseEntity<Resource>`, `Content-Disposition: inline`) and writes `PlatformAuditAction.KYC_DOCUMENT_VIEW`.
  - `PATCH /api/platform/agency-applications/{id}/documents/{docId}` body `KycDocumentDecisionRequest { @NotNull KycDocumentStatus status, note? }`.
  - `POST /api/platform/agency-applications/{id}/decision` body `ApplicationDecisionRequest { @NotNull ApplicationStatus status ∈ {UNDER_REVIEW, MORE_INFO, REJECTED}, note?, rejectionReason? (required when REJECTED) }`.
  - `POST /api/platform/agency-applications/{id}/approve` body `ApplicationApproveRequest { @NotBlank @Size(min=8) ownerPassword }` → `AgencyResponse` (201). Allowed from SUBMITTED/UNDER_REVIEW/MORE_INFO only; second call → 409 (`IllegalStateException` → handled as conflict by `GlobalExceptionHandler`, confirm the mapping; if `IllegalStateException` maps to 400 today, throw the handler's conflict exception type instead).
  - `AgencyService.provisionFromApplication(AgencyApplication app, String ownerPassword): Tenant` — builds `Tenant` from the application (name, legal/trade name, pan, cin, gstin, address, stateCode, owner, currency, `status = ACTIVE`, `approvedAt/By`), then the same `saveAndFlush → migrate → seedDefaults → seedChartOfAccounts → TenantCache.put` sequence as `createAgency` (refactor both to share a private `provision(Tenant)`); sets `app.tenantId`, `app.status = APPROVED`.
  - `AgencyApplicationPurgeJob` (`@Scheduled(cron = "${app.applications.purge.cron:0 30 3 * * *}")`): for REJECTED or DRAFT applications with `updatedAt < now − 30 d`, delete each document's file via `fileStorageService.delete(key)` and the `kyc_document` rows; keep the application row (status unchanged, `reviewNote += "documents purged"`).
- Consumes: Task 3 `PlatformAuditService`; Task 4 service/repositories; `AgencyService` internals.

- [ ] **Step 1: Failing tests**

```java
// AgencyApplicationServiceTest
@Test void decisionRejectRequiresAReason()                       // REJECTED without rejectionReason → IllegalArgumentException
@Test void decisionWritesAnAuditRow()                             // verify(platformAuditService).record(APPLICATION_DECISION, "AGENCY_APPLICATION", id, …)
@Test void documentDecisionUpdatesStatusAndReviewer()
@Test void openDocumentWritesAViewAuditRow()                      // verify record(KYC_DOCUMENT_VIEW, "KYC_DOCUMENT", docId, …)
@Test void approveFromRejectedIsRefused()                         // IllegalStateException
@Test void approveIsOncePerApplication()                          // second approve on APPROVED → IllegalStateException; provisionFromApplication called once
@Test void approveCopiesTheApplicationOntoTheTenant()             // ArgumentCaptor<AgencyApplication> → provisionFromApplication(app, password); app.tenantId set from returned tenant; status APPROVED
// AgencyServiceProvisionTest (mocks as in AgencyService's existing tests)
@Test void provisionFromApplicationChecksTheEmailMigratesAndCaches()   // assertFree(email); tenantRepository.saveAndFlush; migrate(id); TenantCache.status(id)==ACTIVE
@Test void createAgencyStillWorksThroughTheSharedProvision()
// AgencyApplicationPurgeJobTest
@Test void purgesDocumentsOfRejectedApplicationsOlderThan30Days()  // delete(fileKey) per doc; kycDocumentRepository.deleteAll(docs)
@Test void leavesRecentRejectionsAlone()
```

- [ ] **Step 2: Run** → FAIL. **Implement** service methods (`list`, `get`, `openDocument(id, docId): KycFileContent(Resource, fileName, contentType)`, `decideDocument`, `decide`, `approve`), the `AgencyService` refactor, controller (`@PreAuthorize("hasRole('SUPER_ADMIN')")`, no try/catch), purge job (`tenantRepository` not needed — public schema only).

- [ ] **Step 3: Controller test** — SUPER_ADMIN 200s, AGENCY_OWNER 403 on list/approve; approve returns 201 with `"status":"ACTIVE"`.

- [ ] **Step 4: Run** `rtk ./mvnw test` + audit → PASS/COMPLIANT. **Commit** — `feat(platform): review KYC applications and approve them into provisioned agencies`

---

### Task 6: Platform-level Tripjack credential; providers switch; tenant endpoints retired

**Files:**
- Create: `BE/entity/PlatformSupplierCredential.java`, `BE/repository/PlatformSupplierCredentialRepository.java`, `BE/service/PlatformSupplierCredentialService.java`, `BE/controller/PlatformSupplierCredentialController.java`, `BE/dto/PlatformSupplierCredentialRequest.java`, `BE/dto/PlatformSupplierCredentialResponse.java`
- Modify: `BE/service/tripjack/TripjackFlightSearchProvider.java:58,75`, `BE/service/tripjack/TripjackHotelSearchProvider.java:105-108,203-207`
- Delete: `BE/controller/SupplierCredentialController.java`, `BE/service/SupplierCredentialService.java`, `BE/entity/SupplierCredential.java`, `BE/repository/SupplierCredentialRepository.java`, their DTOs (`SupplierCredentialUpsertRequest`, `SupplierCredentialResponse`, `SupplierCredentialRevealResponse`), `BT/service/SupplierCredentialServiceTest.java`, `BT/controller/SupplierCredentialControllerTest.java`
- Modify: `BE/service/FlightSearchService.java` (if it references `SupplierCredentialService` for UAT/PROD — grep), `BE/config/SupplierStartupLogger.java`
- Test: `BT/service/PlatformSupplierCredentialServiceTest.java`, `BT/controller/PlatformSupplierCredentialControllerTest.java`, update `BT/service/tripjack/TripjackFlightSearchProviderTest.java` and `TripjackHotelSearchProviderTest.java` mocks

**Interfaces:**
- Produces:
  - `PlatformSupplierCredentialService.resolveActive(SupplierProvider): ResolvedCredential` where `record ResolvedCredential(SupplierProvider provider, SupplierEnvironment environment, String baseUrl, String apiKey, String userId)` — the only way providers get a key; throws `IllegalStateException("Tripjack is not configured on the platform")` when none active.
  - `upsert(PlatformSupplierCredentialRequest { @NotNull provider, @NotNull environment, @NotBlank baseUrl, apiKey? (blank keeps), userId?, Boolean isActive }): PlatformSupplierCredentialResponse { id, provider, environment, baseUrl, maskedApiKey, isActive, updatedAt, updatedBy }` — writes `CREDENTIAL_SET` audit; activating one deactivates the others for the provider (partial unique index enforces it).
  - `get(provider): PlatformSupplierCredentialResponse`; `testCall(provider): SupplierTestResult { boolean ok, String message, long millis }` — flight: `POST /fms/v1/air-search-all` for DEL→BOM, +30 days, 1 adult via the provider's own client (reuse `TripjackFlightSearchProvider.search` on a fixed `FlightSearchQuery`); writes `CREDENTIAL_TEST` audit.
  - Endpoints under `/api/platform/supplier-credentials`: `PUT` upsert, `GET /{provider}`, `POST /{provider}/test`. **No reveal endpoint.**
- Consumes: `PasswordEncryptionUtil`; Task 3 audit.

- [ ] **Step 1: Failing service tests**

```java
@Test void upsertEncryptsTheKeyAndMasksIt()            // maskedApiKey "••••••" + last 4; stored apiKeyEncrypted != plain; PasswordEncryptionUtil.decrypt(stored, key) == plain
@Test void upsertWithBlankKeyKeepsTheStoredOne()
@Test void resolveActiveReturnsDecryptedKey()
@Test void resolveActiveWithoutARowThrows()
@Test void thereIsNoRevealMethod()                      // reflection: no public method named "reveal" on the service
```

- [ ] **Step 2: Run** → FAIL. **Implement** entity (copy field set from the deleted `SupplierCredential`), repository (`findByProviderAndIsActiveTrue`, `findByProviderOrderByUpdatedAtDesc`), service (`@Value("${app.security.password-encryption-key}")`), controller. Switch both Tripjack providers to inject `PlatformSupplierCredentialService` and call `resolveActive(SupplierProvider.TRIPJACK)`; update their tests' mocks. Delete the tenant-level classes and tests; grep for remaining references (`SupplierCredentialService`, `AuditEntityType.SUPPLIER_CREDENTIAL` stays in the enum for history). `SupplierStartupLogger` logs whether a platform credential is active.

- [ ] **Step 3: Controller test** — PUT as SUPER_ADMIN → 200; as AGENCY_OWNER → 403; `GET /api/supplier-credentials/TRIPJACK` → 404 (route gone).

- [ ] **Step 4: Run** `rtk ./mvnw test` + audit → PASS/COMPLIANT. **Commit** — `feat(platform): one platform-level Tripjack credential, write-only, used by every search`

---

### Task 7: Platform settings

**Files:**
- Create: `BE/entity/PlatformSetting.java`, `BE/repository/PlatformSettingRepository.java`, `BE/service/PlatformSettingService.java`, `BE/controller/PlatformSettingController.java`, `BE/dto/PlatformSettingResponse.java`, `BE/dto/PlatformSettingUpdateRequest.java`, `BE/enums/DiscountBase.java` (`GROSS, BASE`)
- Test: `BT/service/PlatformSettingServiceTest.java`

**Interfaces:**
- Produces: `PlatformSettingService.discountBase(): DiscountBase`; `tdsRate(): BigDecimal`; `commissionEstimatePct(): BigDecimal`; `referenceFareBasket(): List<ReferenceFare>` with `record ReferenceFare(String label, BigDecimal gross, BigDecimal base)`; `set(String key, String value)` (validates: `discount_base ∈ {GROSS,BASE}`, `tds_rate` 0–30, `commission_estimate_pct` 0–100, `reference_fare_basket` parses to ≥ 1 fare with `base ≤ gross`) + `SETTING_SET` audit. Endpoints `GET /api/platform/settings` → `List<PlatformSettingResponse { key, value, updatedAt }>`, `PUT /api/platform/settings/{key}` body `{ value }`.

- [ ] **Step 1: Failing tests** — `discountBaseDefaultsToGross`, `setRefusesAnUnknownKey`, `setValidatesTdsRateRange` (31 → IllegalArgumentException), `basketParsesAndRejectsBaseAboveGross`.
- [ ] **Step 2: Run → FAIL; implement; run → PASS; commit** — `feat(platform): platform settings (discount base, TDS rate, commission estimate, reference fare basket)`

---

### Task 8: Agency pricing with the margin guard

**Files:**
- Create: `BE/entity/AgencyPricing.java`, `BE/enums/PricingProduct.java` (`FLIGHT, HOTEL`), `BE/repository/AgencyPricingRepository.java`, `BE/service/AgencyPricingService.java`, `BE/util/PricingMath.java`, `BE/controller/PlatformAgencyPricingController.java`, `BE/dto/AgencyPricingCreateRequest.java`, `BE/dto/AgencyPricingResponse.java`, `BE/dto/PricingPreviewResponse.java`
- Test: `BT/util/PricingMathTest.java`, `BT/service/AgencyPricingServiceTest.java`, `BT/controller/PlatformAgencyPricingControllerTest.java`

**Interfaces:**
- Produces:
  - `PricingMath.displayed(BigDecimal gross, BigDecimal base, BigDecimal discountPct, DiscountBase db): BigDecimal` — GROSS: `gross × (1 − d/100)`; BASE: `gross − base × d/100`; `HALF_UP` to 2 dp.
  - `PricingMath.voyraNet(gross, base, discountPct, commissionPct, voyraEarnedPct, DiscountBase): BigDecimal` = `base×e/100 − (gross − displayed) − min(base×c/100, base×e/100)`.
  - `PricingMath.worstCaseNetPer10k(List<ReferenceFare> basket, discountPct, commissionPct, voyraEarnedPct, DiscountBase): BigDecimal` — min over fares of `voyraNet × 10000 / gross`.
  - `AgencyPricingService.preview(tenantId, product, discountPct, commissionPct): PricingPreviewResponse { worstCaseNetPer10k, requiresSecondApproval (= worstCase < 0), basketLines: List<{label, displayed, voyraNet}> }` using `PlatformSettingService.commissionEstimatePct()` as `voyraEarnedPct` (slice 3 replaces with API/statement figures).
  - `create(tenantId, AgencyPricingCreateRequest { @NotNull product, @DecimalMin("0") @DecimalMax("100") discountPct, commissionPct, allowCommissionAboveEarned, @NotNull effectiveFrom }): AgencyPricingResponse` — refuses `effectiveFrom < now()` ("Effective date must be now or later"), duplicate `(tenant, product, effectiveFrom)` → conflict; stamps `worstCaseNetPer10k`, `requiresSecondApproval`, `createdBy`; when not requiring approval sets `approvedAt = now, approvedBy = createdBy`; `PRICING_CREATE` audit.
  - `approve(tenantId, pricingId): AgencyPricingResponse` — refuses when `approvedBy` would equal `createdBy` ("A different platform admin must approve a loss-making rate"); `PRICING_APPROVE` audit.
  - `history(tenantId, product?): List<AgencyPricingResponse>`; `resolve(tenantId, product, Instant at): Optional<AgencyPricing>` = latest `effectiveFrom ≤ at` with `approvedAt IS NOT NULL` (used by slices 3–4).
  - Endpoints under `/api/platform/agencies/{id}/pricing`: `GET` (history), `GET /preview?product&discountPct&commissionPct`, `POST` (create), `POST /{pricingId}/approve`.
  - `AgencyPricingResponse { id, tenantId, product, discountPct, commissionPct, allowCommissionAboveEarned, effectiveFrom, worstCaseNetPer10k, requiresSecondApproval, createdBy, createdAt, approvedBy, approvedAt, inForce (bool, computed = approved ∧ effectiveFrom ≤ now ∧ is the resolve() result) }`.
- Consumes: Task 7 settings; Task 3 audit.

- [ ] **Step 1: Failing math tests** (`PricingMathTest`)

```java
@Test void grossDiscountMatchesTheOwnersExample()        // gross 5000, base 3500, d 10, GROSS → displayed 4500.00
@Test void baseDiscountLeavesTaxesAlone()                 // gross 5000, base 3500, d 10, BASE → 4650.00
@Test void voyraNetBleedsOnTaxHeavyFares()                // gross 45000, base 22000, d 3, c 4, e 5, GROSS → net = 1100 − 1350 − 880 = −1130.00
@Test void commissionIsCappedAtWhatVoyraEarned()          // c 8, e 5 → agency commission term uses 5
@Test void worstCaseIsTheMinimumAcrossTheBasket()
```

- [ ] **Step 2: Run → FAIL; implement `PricingMath`; run → PASS.**

- [ ] **Step 3: Failing service tests**

```java
@Test void createRefusesAPastEffectiveDate()
@Test void createAutoApprovesAProfitableRow()             // worstCase ≥ 0 → approvedAt == createdAt, approvedBy == createdBy
@Test void createLeavesALossMakingRowUnapproved()         // worstCase < 0 → approvedAt null, requiresSecondApproval true
@Test void approveNeedsADifferentAdmin()                  // same userId → IllegalStateException
@Test void resolveIgnoresFutureAndUnapprovedRows()        // rows: approved@t-2d, unapproved@t-1d, approved@t+1d → returns t-2d row
@Test void resolveIsEmptyWhenNothingIsConfigured()
```

- [ ] **Step 4: Run → FAIL; implement entity/repository (`findByTenantIdAndProductOrderByEffectiveFromDesc`, `existsByTenantIdAndProductAndEffectiveFrom`), service, controller; controller test (SUPER_ADMIN 200/201, OWNER 403, preview returns `requiresSecondApproval:true` for d 10 / c 4 on the seeded basket). Run suite + audit → PASS/COMPLIANT.**

- [ ] **Step 5: Commit** — `feat(platform): per-agency discount and commission rates with a worst-case margin guard and second approver`

---

### Task 9: Frontend — types, services, agency store status

**Files:**
- Modify: `FE/lib/api/types.ts` (append), `FE/lib/api/services/agencies.ts`, `FE/lib/agencyStore.ts`
- Create: `FE/lib/api/services/agencyApplications.ts`, `FE/lib/api/services/platformSupplier.ts`, `FE/lib/api/services/agencyPricing.ts`, `FE/lib/api/services/platformSettings.ts`
- Modify: `FE/lib/api/services/settings.ts` (remove `getSupplierCredential`, `upsertSupplierCredential`, `revealSupplierCredential`)
- Test: `rtk npm run build` (type errors block)

**Interfaces:**
- Produces (TS, mirroring Tasks 2–8 DTOs byte for byte in field names):
  - types: `TenantStatus`, `AgencyResponse.status`, `ApplicationStatus`, `KycDocumentType`, `KycDocumentStatus`, `AgencyApplicationCreateRequest`, `AgencyApplicationCreatedResponse`, `AgencyApplicationResponse`, `KycDocumentResponse`, `ApplicationDecisionRequest`, `KycDocumentDecisionRequest`, `ApplicationApproveRequest`, `PlatformSupplierCredentialRequest/Response`, `SupplierTestResult`, `PlatformSettingResponse`, `PricingProduct`, `AgencyPricingCreateRequest/Response`, `PricingPreviewResponse`.
  - `agencies.ts`: replace `updateAgencyStatus(id, isActive)` with `updateAgencyStatus(id: string, status: TenantStatus): Promise<AgencyResponse>`.
  - `agencyStore.ts`: `Agency.status: 'Active' | 'Suspended' | 'Inactive'`; `setAgencyStatus(id, status: TenantStatus)` replaces `setAgencyActive`.
  - `agencyApplications.ts`: `createApplication(req)`, `uploadApplicationDocument(id, token, type, file): Promise<KycDocumentResponse>` (via `apiUpload` with header `X-Upload-Token` — extend `RequestOpts` with `headers?: Record<string,string>` in `client.ts`), `submitApplication(id, token)`, `listApplications(status: ApplicationStatus | undefined, page)`, `getApplication(id)`, `decideApplication(id, req)`, `decideDocument(id, docId, req)`, `approveApplication(id, ownerPassword)`, `applicationDocumentUrl(id, docId): string` (for `apiOpenFile`).
  - `platformSupplier.ts`: `getPlatformCredential(provider)`, `upsertPlatformCredential(req)`, `testPlatformCredential(provider)`.
  - `agencyPricing.ts`: `listPricing(agencyId)`, `previewPricing(agencyId, product, discountPct, commissionPct)`, `createPricing(agencyId, req)`, `approvePricing(agencyId, pricingId)`.
  - `platformSettings.ts`: `listSettings()`, `setSetting(key, value)`.

- [ ] **Step 1: Add the types and services; fix every caller of `setAgencyActive`/`updateAgencyStatus` (`app/platform/agencies/[id]/page.tsx`, `app/platform/agencies/page.tsx`, `components/shared/AgencyDetailModal.tsx` if still used).**
- [ ] **Step 2: `rtk npm run build` → clean. Commit** — `feat(platform): API types and services for applications, pricing, platform supplier and settings`

---

### Task 10: Frontend — `/apply` public page

**Files:**
- Create: `FE/app/apply/page.tsx`, `FE/app/apply/layout.tsx`
- Modify: `FE/app/sitemap.ts` (add `/apply`), `FE/components/layout/PublicHeader.tsx:181-205,249-263` ("Sign Up" → `/apply`), `FE/components/layout/PublicFooter.tsx` ("Get Free Agent ID" → `/apply`), `FE/app/contact/page.tsx` (keep as inquiry)
- Test: `FE/e2e/agency-apply.spec.ts`

**Interfaces:**
- Consumes: Task 9 `agencyApplications.ts`; `Honeypot` from `hooks/use-inquiry.tsx`; `components/ui` `Input`, `Label`, `Select`, `Button`, `Card`.
- Produces: three-step page: (1) Agency & owner details → `createApplication` → keeps `{id, uploadToken}` in state; (2) Documents: one row per `KycDocumentType` with a file input (PAN required, others optional), each upload calls `uploadApplicationDocument` and shows the returned file name + size with a `Badge`; (3) Review & consent checkbox → `submitApplication` → "Application received — our onboarding desk will call you within 2 working days." Field errors via `fieldError(e, 'ownerEmail')` etc. Public shell (`PublicHeader`/`PublicFooter`, `bg-[#f8fbfb]`), eyebrow pill + Cinzel H1 per UI_BLUEPRINT §7.3.

- [ ] **Step 1: Write the failing Playwright spec**

```ts
test('an agency applies with a PAN document and the platform admin sees it in the queue', async ({ page, request }) => {
  await page.goto('/apply');
  await page.getByLabel('Agency name').fill('Apply Test Agency');
  await page.getByLabel('PAN').fill('ABCDE1234F');
  await page.getByLabel('Owner name').fill('Apply Owner');
  const email = `apply.${Date.now()}@example.com`;
  await page.getByLabel('Owner email').fill(email);
  await page.getByLabel('Owner phone').fill('9876543210');
  await page.getByRole('button', { name: 'Continue' }).click();
  await page.getByLabel('PAN card').setInputFiles({ name: 'pan.pdf', mimeType: 'application/pdf', buffer: Buffer.from('%PDF-1.4 test') });
  await expect(page.getByText('pan.pdf')).toBeVisible();
  await page.getByRole('button', { name: 'Continue' }).click();
  await page.getByLabel(/I confirm/).check();
  await page.getByRole('button', { name: 'Submit application' }).click();
  await expect(page.getByText('Application received')).toBeVisible();

  const admin = await loginAs(page, request, PLATFORM_ADMIN);
  const list = await apiGet<{ content: { ownerEmail: string }[] }>(request, admin.token, '/api/platform/agency-applications');
  expect(list.content.some(a => a.ownerEmail === email)).toBe(true);
});
```

- [ ] **Step 2: Run** `rtk npx playwright test e2e/agency-apply.spec.ts` → FAIL (404). **Implement** the page + layout metadata (`title: 'Apply for a Voyra B2B account'`), sitemap, header/footer links. **Run** → PASS. **Commit** (frontend repo) — `feat(public): agencies apply for a Voyra account with KYC documents`

---

### Task 11: Frontend — platform Applications queue and detail

**Files:**
- Create: `FE/app/platform/applications/page.tsx`, `FE/app/platform/applications/[id]/page.tsx`
- Modify: `FE/app/platform/layout.tsx:30-34` (nav: `{ href: '/platform/applications', label: 'Applications', icon: FileCheck2 }` after Agency Management; group the three Partners items under a `title: 'Partners'` section the way `app/agency/layout.tsx` does)
- Test: `FE/e2e/agency-apply.spec.ts` (extend), `FE/e2e/shell-and-guards.spec.ts` (update the platform nav count to 5)

**Interfaces:**
- Consumes: Task 9 services; `Table`, `Badge`, `Pagination`, `Modal`, `EmptyState`, `confirmAction`, `apiOpenFile`.
- Produces: queue page (`.page-header` "Applications", status filter `Select` SUBMITTED/UNDER_REVIEW/MORE_INFO/APPROVED/REJECTED, `Table` columns Submitted · Agency · Owner · PAN · Status `Badge` · Documents n, row → detail); detail page (`page-header` agency name + status badge; `Card` "Application" fields; `Card` "Documents" with per-row **Open** (`apiOpenFile(applicationDocumentUrl(...))`), **Verify**/**Reject** (reject asks a note); `Card` "Decision": buttons **Mark under review**, **Request more info** (note modal), **Reject** (reason modal, `danger`), **Approve** (modal with owner password input, min 8, `confirmAction` "Create this agency?"; on success `router.push('/platform/agencies/' + tenantId)`).

- [ ] **Step 1: Extend the spec** — after the API assertion: open `/platform/applications`, click the row, click **Open** on `pan.pdf` (expect a new page/tab request to `/documents/.../file` with status 200 via `page.waitForResponse`), click **Verify**, click **Approve**, fill password `ApplyPass123!`, confirm; expect URL `/platform/agencies/…` and text `Active`; then `request.post(API_BASE + '/api/auth/login/owner', { data: { email, password: 'ApplyPass123!' } })` → `success: true`.
- [ ] **Step 2: Run → FAIL; implement; run → PASS; update `shell-and-guards.spec.ts` platform count; commit** — `feat(platform): KYC application queue, document review and approval`

---

### Task 12: Frontend — agency detail: status actions, KYC and Pricing

**Files:**
- Modify: `FE/app/platform/agencies/[id]/page.tsx` (hero actions at 267-322; banner at 323; add two cards in the right column after "Super Admin Actions")
- Create: `FE/components/platform/AgencyPricingCard.tsx`, `FE/components/platform/AddPricingModal.tsx`
- Modify: `FE/e2e/platform-agency-admin.spec.ts`
- Test: `FE/e2e/agency-pricing.spec.ts`

**Interfaces:**
- Consumes: Task 9 `agencyPricing.ts`, `setAgencyStatus`; `AgencyResponse.status`.
- Produces: hero buttons **Suspend Agency** / **Lift Suspension** / **Deactivate Agency** / **Reactivate Agency** (existing inline confirm banner, `Yes, deactivate` / `Yes, reactivate` kept, add `Yes, suspend` / `Yes, lift`); status pill `Active` (green) / `Suspended` (amber) / `Inactive` (red); `Card` "KYC" showing `pan`, `cin`, `approvedAt` and a link "View application" when the agency came from one; `AgencyPricingCard` — table per product of history rows (Effective · Discount % · Commission % · Worst-case net / ₹10k · Status `Badge` In force / Scheduled / Awaiting approval / Superseded · **Approve** button when awaiting and `approvedBy` would differ — the server decides, the button just calls and surfaces the error) + **Add rate** → `AddPricingModal` (product, discount %, commission %, effective from `datetime-local` default now, live preview via `previewPricing` debounced 300 ms showing basket lines and the badge: green "Voyra keeps ₹X per ₹10,000" / red "Voyra pays ₹X per ₹10,000 — a second admin must approve", checkbox "Allow commission above what Voyra earns").

- [ ] **Step 1: Failing spec** (`agency-pricing.spec.ts`)

```ts
test('admin adds a loss-making flight rate and it waits for a second admin', async ({ page, request }) => {
  await loginAs(page, request, PLATFORM_ADMIN);
  await page.goto('/platform/agencies');
  await page.locator('tr', { has: page.getByText('Dummy QA Agency') }).click();
  await page.getByRole('button', { name: 'Add rate' }).click();
  await page.getByLabel('Discount %').fill('10');
  await page.getByLabel('Commission %').fill('4');
  await expect(page.getByText(/Voyra pays ₹/)).toBeVisible();
  await page.getByRole('button', { name: 'Save rate' }).click();
  await expect(page.getByText('Awaiting approval')).toBeVisible();
  await page.getByRole('button', { name: 'Approve' }).first().click();
  await expect(page.getByText(/different platform admin/)).toBeVisible();
});
```

- [ ] **Step 2: Update `platform-agency-admin.spec.ts`** — keep the deactivate/reactivate flow; add: click **Suspend Agency** → **Yes, suspend** → expect `Suspended`; `apiLogin(request, OWNER)` succeeds; `request.post(API_BASE + '/api/vendors', …)` with the owner token → 423; **Lift Suspension** → `Active`.
- [ ] **Step 3: Run → FAIL; implement; run both specs → PASS; commit** — `feat(platform): suspend/lift agencies; per-agency pricing with live margin preview`

---

### Task 13: Frontend — platform Supplier page, settings, remove the agency tab

**Files:**
- Create: `FE/app/platform/supplier/page.tsx`
- Modify: `FE/app/platform/layout.tsx` (nav `{ href: '/platform/supplier', label: 'Supplier', icon: Plug }` in a `Platform` section), `FE/app/agency/settings/page.tsx:39,384,416,956-1060,83-125,329-372` (remove the `supplier` section, its state, handlers and imports), `FE/lib/api/types.ts` (drop `SupplierCredential*` types)
- Delete: `FE/e2e/supplier-settings.spec.ts`
- Test: `FE/e2e/platform-supplier.spec.ts`

**Interfaces:**
- Consumes: Task 9 `platformSupplier.ts`, `platformSettings.ts`.
- Produces: page with `Card` "Tripjack credential" (Environment `Select` UAT/PROD, Base URL, API key password input, User id, Active checkbox, **Save** with `confirmAction`, masked key display, **Test call** button showing `ok/message/millis` in a `Badge`); `Card` "Platform settings" listing `discount_base` (Select GROSS/BASE), `tds_rate`, `commission_estimate_pct`, `reference_fare_basket` (textarea JSON) with per-row **Save**.

- [ ] **Step 1: Failing spec**

```ts
test('platform admin saves the Tripjack credential masked, with no reveal, and the owner has no supplier tab', async ({ page, request }) => {
  await loginAs(page, request, PLATFORM_ADMIN);
  await page.goto('/platform/supplier');
  await page.getByLabel('Base URL').fill('https://apitest.tripjack.com');
  await page.getByLabel('API key').fill('tj_e2e_platform_key_9876');
  await page.getByRole('button', { name: 'Save credential' }).click();
  const confirm = page.getByRole('button', { name: 'Confirm', exact: true });
  if (await confirm.isVisible({ timeout: 2000 }).catch(() => false)) await confirm.click();
  await expect(page.getByText('••••••9876')).toBeVisible();
  await expect(page.getByRole('button', { name: /Reveal/ })).toHaveCount(0);

  const owner = await loginAs(page, request, OWNER);
  await page.goto('/agency/settings');
  await page.getByRole('button', { name: 'Settings section' }).click();
  await expect(page.getByRole('option', { name: /Supplier Integration/ })).toHaveCount(0);
  const gone = await request.get(API_BASE + '/api/supplier-credentials/TRIPJACK', { headers: { Authorization: `Bearer ${owner.token}` } });
  expect(gone.status()).toBe(404);
});
```

- [ ] **Step 2: Run → FAIL; implement; delete `supplier-settings.spec.ts`; run → PASS; `rtk npm run build` clean; commit** — `feat(platform): supplier credential and platform settings pages; agency supplier tab removed`

---

### Task 14: Docs, regression and PR texts

**Files:**
- Modify: root `CLAUDE.md` (travel-crm-backend section: platform tables, tenant status, platform credential), `FE/CLAUDE.md` (Layout: `apply/`, `platform/applications`, `platform/supplier`; "Where the frontend and backend disagree": supplier credentials are platform-level), `FE/UI_BLUEPRINT.md` §7.1 (Platform nav now: Platform Overview · Partners [Applications, Agency Management, B2B Inquiries] · Platform [Supplier]), `BACKEND_BLUEPRINT.md` (short addendum: public-schema platform entities, `platform_audit_log` for super admins, `TenantStatus`)
- Create: `pr-descriptions/b2b-01-foundation-{backend,frontend}-PR.md`

- [ ] **Step 1: Run the gates** — `cd travel-crm-backend && rtk ./mvnw verify` → tests PASS, audit COMPLIANT; `cd travel-crm && rtk npm run build && rtk npx playwright test` → all green (the Dummy QA tenant must be seeded; `scripts`: `.claude/run/rebuild-and-test.sh`).
- [ ] **Step 2: Run the `regression-tester` agent in `pre-merge` mode; fix what it reports; re-run.**
- [ ] **Step 3: Update the four docs; write both PR descriptions (What changed / Why / Compatibility / Testing / Not covered). Commit** — `docs: slice 1 foundation — platform tables, tenant status, applications, pricing`
- [ ] **Step 4: Hand the branch names and PR texts to the owner. Do not push to main.**

---

## Self-review notes

- Spec coverage for slice 1 (§7.1 steps 1–5, §6 public tables except wallet/subscription, §9 Platform + `/apply`, §10 rows "Applications vs tenants", "Tenant liveness", "Tripjack credentials", "Discount guard", "Audit"): each has a task. Deferred by design: wallets (slice 2), vendor/ledger seeding and mirror (slice 3), subscriptions + entitlement interceptor (slice 5 — Task 2's `TenantStatusInterceptor` is its seed), `/apply` CAPTCHA (left to the builder: the rate limiter + honeypot + upload token ship now; add a CAPTCHA provider in slice 6 hardening if spam appears).
- Names used consistently: `TenantStatus`, `TenantCache.status`, `PlatformAuditService.record`, `AgencyApplicationService.{create,attachDocument,submit,list,get,openDocument,decideDocument,decide,approve}`, `AgencyService.provisionFromApplication`, `PlatformSupplierCredentialService.resolveActive → ResolvedCredential`, `PlatformSettingService.{discountBase,tdsRate,commissionEstimatePct,referenceFareBasket,set}`, `AgencyPricingService.{preview,create,approve,history,resolve}`, `PricingMath.{displayed,voyraNet,worstCaseNetPer10k}`.
- Review Focus items 1–5 each have a named test (Tasks 4, 5, 4, 2, 8).
