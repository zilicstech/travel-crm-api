# Voyra Travel CRM Backend — Remediation Plan to 100% Blueprint Compliance

**Target:** close every gap between the current codebase and `BACKEND_BLUEPRINT.md`, plus the
operational gaps the original `BACKEND_PLAN.md` never covered.

**Audience:** an AI coding agent (Sonnet or Haiku) executing tasks one at a time.
**Baseline audited:** 166 Java files, 8,009 LOC, 68 endpoints, compiles clean.

---

## HOW TO USE THIS DOCUMENT — READ FIRST

You are executing a remediation plan. Follow these rules exactly.

1. **Work one task at a time, in order.** Task IDs are `T<phase>.<n>`. Do not skip ahead. Do not
   batch tasks. Later tasks assume earlier ones are done.
2. **Every task ends with its VERIFY block.** Run those commands. If any fails, fix it before
   moving on. Never move to the next task with a failing verify.
3. **Every task ends with a git commit** using the exact message given. This is non-negotiable —
   it is the rollback point.
4. **Do not make design decisions.** Every decision is already made below. If something is genuinely
   ambiguous and not covered, STOP and ask the user. Do not improvise.
5. **Do not refactor anything not named in the task.** No "while I'm here" changes. No renaming.
   No reformatting untouched files.
6. **Do not delete or rewrite working code** to make a task easier. If a task seems to require
   that, you have misread it.
7. **Preserve all existing javadoc comments.** They encode blueprint rationale. Add to them; never
   strip them.
8. **The blueprint is at** `/Users/shivanshsrivastava/Projects/Voyra global/BACKEND_BLUEPRINT.md`.
   Section references like "§8.5" point there. Read the cited section before doing a task.

### Standard environment setup (run at the start of every session)

```bash
cd "/Users/shivanshsrivastava/Projects/Voyra global/travel-crm-backend"
export JAVA_HOME="/opt/homebrew/opt/openjdk@17"
export PATH="$JAVA_HOME/bin:$PATH"
```

### Standard verify commands

```bash
# BUILD — must pass after every task
./mvnw -q -o compile

# TESTS — must pass after every task from Phase 5 onward
./mvnw -q -o test

# RUN — for manual verification tasks
set -a; source .env; set +a
./mvnw spring-boot:run
```

### Definition of done for every task

- [ ] Code change matches the task description exactly
- [ ] `./mvnw -q -o compile` exits 0
- [ ] `./mvnw -q -o test` exits 0 (from Phase 5 onward)
- [ ] The task's own VERIFY block passes
- [ ] Committed with the exact message given

---

## PHASE 0 — SAFETY NET

**Nothing else happens until this phase is complete.** There are currently zero git commits and
8,000 lines of untracked work.

### T0.1 — Create the baseline commit

**Why:** `git rev-list --all --count` returns a branch with no commits. All 166 files are untracked.
The original plan promised "history exists from commit 1". There is no recovery point today.

**Steps:**

1. Confirm `.env` will NOT be committed:
   ```bash
   git check-ignore -v .env
   ```
   This MUST print a match against `.gitignore:2`. If it does not, STOP and tell the user.

2. Confirm no secrets are about to be staged:
   ```bash
   git add -A -n | grep -iE '\.env$|secret|credential|\.pem$|\.key$'
   ```
   This must print nothing except `.env.example`. If anything else appears, STOP.

3. Confirm `uploads/` content is ignored but the directory is preserved:
   ```bash
   ls uploads/.gitkeep || touch uploads/.gitkeep
   git check-ignore -v uploads/somefile.txt
   ```

4. Commit:
   ```bash
   git add -A
   git commit -m "$(cat <<'EOF'
   Initial commit: Voyra Travel CRM backend v1

   Multi-tenant Spring Boot 3.3 + PostgreSQL CRM backend built against
   BACKEND_BLUEPRINT.md. 16 entities, 19 services, 13 controllers,
   68 endpoints across auth, platform, agency, and public surfaces.

   Schema-per-tenant routed by search_path, stateless JWT with three
   principal types, and a pricing-safe unauthenticated proposal surface.

   Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
   EOF
   )"
   ```

**VERIFY:**
```bash
test "$(git rev-list --all --count)" -ge 1 && echo "OK: history exists"
git ls-files | grep -c '\.java$'   # must print 166
git ls-files | grep -x '.env' && echo "FAIL: .env committed" || echo "OK: .env not tracked"
```

---

### T0.2 — Add the mechanical compliance audit script

**Why:** the original plan's "Blueprint-compliance checklist" was written with `[x]` boxes *before
the code existed*. At least one item ("reports are a single GROUP BY query per report") turned out
false. Compliance must be machine-checked, not asserted.

**Create** `scripts/blueprint-audit.sh`:

```bash
#!/usr/bin/env bash
# Mechanical BACKEND_BLUEPRINT.md compliance audit. Exit 0 = compliant.
# Run from the project root. Every check maps to a numbered blueprint section.
set -uo pipefail
cd "$(dirname "$0")/.."
SRC=src/main/java/com/voyra/crm
FAIL=0

check() { # name, expected, actual
  if [ "$2" = "$3" ]; then printf "  PASS  %-58s %s\n" "$1" "$3"
  else printf "  FAIL  %-58s expected=%s actual=%s\n" "$1" "$2" "$3"; FAIL=1; fi
}

echo "=== BACKEND_BLUEPRINT.md compliance audit ==="

# §5.3 - every controller class or method carries @PreAuthorize (auth + public are exempt)
UNGUARDED=0
for f in $SRC/controller/*.java; do
  case "$(basename "$f")" in AuthController.java|PublicProposalController.java) continue ;; esac
  grep -q '@PreAuthorize' "$f" || { echo "    unguarded controller: $f"; UNGUARDED=$((UNGUARDED+1)); }
done
check "§5.3 all controllers guarded by @PreAuthorize" 0 "$UNGUARDED"

# §7.3 - controllers never return JPA entities
check "§7.3 no entities returned from controllers" 0 \
  "$(grep -rlE 'ResponseEntity<(List<)?(Lead|Customer|Booking|Visa|Agent|Tenant|ClientInvoice|SupplierInvoice|ProposalItem|LeadNote|PlatformAdmin|CustomerDocument|FamilyMember)[>,]' $SRC/controller/ 2>/dev/null | wc -l | tr -d ' ')"

# §8.1 - controllers hold no repository access and no try/catch
check "§8.1 no repository access in controllers" 0 \
  "$(grep -rl 'Repository' $SRC/controller/ 2>/dev/null | wc -l | tr -d ' ')"
check "§8.1 no try/catch in controllers" 0 \
  "$(grep -rl 'try {' $SRC/controller/ 2>/dev/null | wc -l | tr -d ' ')"

# §8.3 - constructor injection only
check "§8.3 no @Autowired field injection" 0 \
  "$(grep -rl '@Autowired' $SRC 2>/dev/null | wc -l | tr -d ' ')"

# §8.4 - enums persisted as STRING, money is BigDecimal, secrets are @JsonIgnore
check "§8.4 no ordinal enum persistence" 0 \
  "$(grep -rl '@Enumerated(EnumType.ORDINAL)' $SRC/entity/ 2>/dev/null | wc -l | tr -d ' ')"
check "§8.4 no double/float money fields" 0 \
  "$(grep -rhE 'private (double|Double|float|Float) ' $SRC/entity/ $SRC/dto/ 2>/dev/null | wc -l | tr -d ' ')"
check "§8.4 no JPA associations (flat FK columns only)" 0 \
  "$(grep -rhE '@(OneToMany|ManyToOne|ManyToMany|OneToOne|JoinColumn)' $SRC/entity/ 2>/dev/null | wc -l | tr -d ' ')"
PW_TOTAL=$(grep -rh 'private String password;' $SRC/entity/ | wc -l | tr -d ' ')
PW_IGNORED=$(grep -rh -B1 'private String password;' $SRC/entity/ | grep -c '@JsonIgnore' | tr -d ' ')
check "§8.4 all entity password fields @JsonIgnore" "$PW_TOTAL" "$PW_IGNORED"

# §8.5 - every entity primary key is collision-checked, never a raw generate6()
check "§8.5 no raw IdGenerator.generate6() in .id() builder calls" 0 \
  "$(grep -rh '\.id(IdGenerator\.generate6())' $SRC/service/ 2>/dev/null | wc -l | tr -d ' ')"

# §5.3 - services read the principal only via SecurityContextUtil
check "§5.3 SecurityContextHolder only in security package" 0 \
  "$(grep -rl 'SecurityContextHolder' $SRC/service/ $SRC/controller/ 2>/dev/null | wc -l | tr -d ' ')"

# §8.13 - every DTO field documented with @Schema
DTO_FIELDS=$(grep -rh '^    private ' $SRC/dto/*.java | wc -l | tr -d ' ')
DTO_SCHEMAS=$(grep -rh '^    @Schema' $SRC/dto/*.java | wc -l | tr -d ' ')
check "§8.13 @Schema on every DTO field" "$DTO_FIELDS" "$DTO_SCHEMAS"

# §2.2 - Flyway never allowed to clean
check "§2.2 flyway clean-disabled=true" 1 \
  "$(grep -c '^spring.flyway.clean-disabled=true' src/main/resources/application.properties | tr -d ' ')"

# §10 - test sources exist
check "§10 test sources present (>=20 test files)" "yes" \
  "$([ "$(find src/test -name '*Test.java' -o -name '*IT.java' 2>/dev/null | wc -l | tr -d ' ')" -ge 20 ] && echo yes || echo no)"

echo
[ "$FAIL" = 0 ] && echo "RESULT: COMPLIANT" || echo "RESULT: NON-COMPLIANT"
exit $FAIL
```

Then:
```bash
chmod +x scripts/blueprint-audit.sh
./scripts/blueprint-audit.sh
```

It WILL fail right now. That is the point — it is the definition of done for this whole plan.
Record the current failures; the last task in this document is making it print `COMPLIANT`.

**VERIFY:**
```bash
./scripts/blueprint-audit.sh; echo "exit=$? (non-zero expected at this stage)"
git add -A && git commit -m "Add mechanical blueprint compliance audit script

Replaces the pre-checked compliance checklist in BACKEND_PLAN.md with a
script that verifies each rule against the source. Currently failing;
becomes the definition of done for the remediation plan.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

## PHASE 1 — BLOCKERS

Ship-stoppers. Nothing goes to any shared environment until this phase is done.

### T1.1 — Guard the demo seed runner and stop logging credentials

**Blueprint:** §6.4, §8.9 ("never log credentials"), §10 ("hardcoded fallback password literal in
the create-admin path → generate a random password; never a constant").

**Problem:** `config/DemoDataSeedRunner.java` has no `@Profile` and no conditional. It runs in every
environment, creating `admin@travelos.com` / `Passw0rd!` as a live `SUPER_ADMIN` — the
highest-privilege principal in the system — and then logs the password at INFO on lines 68, 84, 97.

**File:** `src/main/java/com/voyra/crm/config/DemoDataSeedRunner.java`

**Change 1** — add the conditional. Add this import:
```java
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
```
and add this annotation to the class, directly above `@Component`:
```java
@ConditionalOnProperty(name = "app.seed.demo-data", havingValue = "true")
```

**Change 2** — remove the password from all three log statements. Replace each:

| Line | Replace | With |
|---|---|---|
| ~68 | `log.info("Seeded demo platform admin: email={}, password={}", PLATFORM_ADMIN_EMAIL, DEMO_PASSWORD);` | `log.info("Seeded demo platform admin: email={}", PLATFORM_ADMIN_EMAIL);` |
| ~84 | `log.info("Seeded demo agency '{}' (tenantId={}): owner email={}, password={}", tenant.getAgencyName(), tenant.getId(), OWNER_EMAIL, DEMO_PASSWORD);` | `log.info("Seeded demo agency '{}' (tenantId={}): owner email={}", tenant.getAgencyName(), tenant.getId(), OWNER_EMAIL);` |
| ~97 | `log.info("Seeded demo agent '{}' (agentId={}): email={}, password={}", agent.getName(), agent.getId(), AGENT_EMAIL, DEMO_PASSWORD);` | `log.info("Seeded demo agent '{}' (agentId={}): email={}", agent.getName(), agent.getId(), AGENT_EMAIL);` |

**Change 3** — update the class javadoc. Add this sentence to the end of the existing javadoc block:
```
 * Gated behind app.seed.demo-data=true so it can never run in a deployed environment; the
 * shared demo password is acceptable only because that flag is local-only.
```

**Change 4** — `src/main/resources/application.properties`, add above `server.port`:
```properties
# --- Demo seeding (LOCAL ONLY) ---
# Creates a known-password SUPER_ADMIN. Must never be true outside local development.
app.seed.demo-data=${SEED_DEMO_DATA:false}
```

**Change 5** — `.env` (local, git-ignored) — add `SEED_DEMO_DATA=true` so local dev keeps working.

**Change 6** — `.env.example`, add:
```
# LOCAL ONLY - seeds a demo agency/owner/agent with a known password. Never true in a deployed env.
SEED_DEMO_DATA=true
```

**VERIFY:**
```bash
./mvnw -q -o compile
grep -c 'password={}' src/main/java/com/voyra/crm/config/DemoDataSeedRunner.java   # must be 0
grep -c 'ConditionalOnProperty' src/main/java/com/voyra/crm/config/DemoDataSeedRunner.java  # must be 1
# Boot WITHOUT the flag and confirm no seeding occurs:
set -a; source .env; set +a
SEED_DEMO_DATA=false ./mvnw spring-boot:run 2>&1 | grep -i 'Seeded demo' && echo "FAIL: seeded anyway" || echo "OK: no seeding"
```
(Stop the app with Ctrl-C after startup completes.)

```bash
git add -A && git commit -m "Gate demo seed runner behind a flag and stop logging credentials

DemoDataSeedRunner ran unconditionally in every environment, creating a
known-password SUPER_ADMIN and logging the password at INFO. Now requires
app.seed.demo-data=true and logs only the email.

Blueprint §6.4, §8.9, §10.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### T1.2 — Make supplier invoices owner-only and split the invoice summary

**Blueprint:** §5.3 (audience-split controllers where data scope differs), §5.2 Layer 3.

**Problem:** `InvoiceService.listSupplierInvoices()` is a bare `findAll()` and
`updateSupplierInvoiceStatus()` has no ownership check, both under
`hasAnyRole('AGENCY_OWNER','AGENT')`. Any agent can read and mutate the agency's entire
accounts-payable ledger. Separately, `getSummary()` mixes an **agent-scoped** client-invoice figure
with an **agency-wide** supplier figure in one response — a correctness bug, not just a permissions
one.

**Decision (already made):** supplier invoices become `AGENCY_OWNER`-only. No migration.
`getSummary` stays open to both roles but returns supplier figures only for the Owner.

**File 1:** `src/main/java/com/voyra/crm/controller/InvoiceController.java`

Add the import if absent:
```java
import org.springframework.security.access.prepost.PreAuthorize;
```

Add a **method-level** `@PreAuthorize("hasRole('AGENCY_OWNER')")` directly beneath the mapping
annotation of these three methods (the class-level annotation stays as-is; the method-level one
overrides it):

- `@PostMapping("/supplier")` → `createSupplierInvoice`
- `@GetMapping("/supplier")` → `listSupplierInvoices`
- `@PatchMapping("/supplier/{id}/status")` → `updateSupplierInvoiceStatus`

Update each of those three methods' `@Operation` description to begin with `"Owner-only. "`.

**File 2:** `src/main/java/com/voyra/crm/service/InvoiceService.java`

In `getSummary()`, replace the unconditional supplier read:
```java
List<SupplierInvoice> supplierInvoices = supplierInvoiceRepository.findAll();
```
with a role-gated one:
```java
// Supplier invoices are accounts-payable data: Owner-only, matching the three
// supplier endpoints. An Agent's summary reports on their own client invoices only.
boolean isOwner = !SecurityContextUtil.getCurrentUserOrThrow().isAgent();
List<SupplierInvoice> supplierInvoices = isOwner ? supplierInvoiceRepository.findAll() : List.of();
```
Ensure `java.util.List` is imported (it already is).

Add a javadoc block above `getSummary()`:
```java
/**
 * Owner sees agency-wide client + supplier figures. Agent sees only their own client
 * invoices, and zero supplier figures - payables are not agent-scoped data. Mixing the
 * two scopes in one response would report agency-wide payables against an agent's own
 * receivables.
 */
```

**VERIFY:**
```bash
./mvnw -q -o compile
grep -c "hasRole('AGENCY_OWNER')" src/main/java/com/voyra/crm/controller/InvoiceController.java  # must be 3
```
Manual check (app running, `SEED_DEMO_DATA=true`):
```bash
AGENT_TOKEN=$(curl -s -X POST localhost:8080/api/auth/login/agent \
  -H 'Content-Type: application/json' \
  -d '{"email":"liam@globalexplorer.com","password":"Passw0rd!"}' | python3 -c 'import sys,json;print(json.load(sys.stdin)["token"])')

# must return 403
curl -s -o /dev/null -w '%{http_code}\n' localhost:8080/api/invoices/supplier -H "Authorization: Bearer $AGENT_TOKEN"

# must return 200 with totalPaidToSuppliers=0 and totalPendingToPay=0
curl -s localhost:8080/api/invoices/summary -H "Authorization: Bearer $AGENT_TOKEN"
```

```bash
git add -A && git commit -m "Restrict supplier invoices to AGENCY_OWNER and scope invoice summary

Supplier invoices were readable and mutable by any AGENT via an unscoped
findAll(). getSummary() also mixed agent-scoped client figures with
agency-wide supplier figures in one response.

Blueprint §5.3.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

## PHASE 2 — CORRECTNESS

### T2.1 — Enforce proposal-link expiry

**Problem:** `proposal_link.expires_at` exists in the migration and on the entity.
`PublicProposalService.resolveLink()` does a bare `findById(token)` and never checks it. The class
javadoc and `BACKEND_PLAN.md` both claim expired tokens return the generic not-found. The control is
advertised and absent.

**File 1:** `src/main/java/com/voyra/crm/service/PublicProposalService.java`

Replace `resolveLink` entirely:
```java
    /**
     * Resolves the opaque token, treating unknown and expired identically. The caller must
     * never be able to distinguish "no such token" from "token expired" - both are the same
     * generic failure, so a guessed token reveals nothing about whether it ever existed.
     */
    private ProposalLink resolveLink(String token) {
        ProposalLink link = proposalLinkRepository.findById(token)
                .orElseThrow(() -> new IllegalArgumentException(NOT_FOUND_MESSAGE));
        if (link.getExpiresAt() != null && link.getExpiresAt().isBefore(LocalDateTime.now())) {
            throw new IllegalArgumentException(NOT_FOUND_MESSAGE);
        }
        return link;
    }
```
Add the import `java.time.LocalDateTime`.

**File 2:** `src/main/java/com/voyra/crm/service/ProposalLinkService.java`

Add a configurable validity period. Add the field alongside the existing `@Value`:
```java
    @Value("${app.public-proposal.validity-days:90}")
    private int validityDays;
```
In `generateLink`, set the expiry when building the `ProposalLink`:
```java
                    .leadId(leadId)
                    .expiresAt(LocalDateTime.now().plusDays(validityDays))
                    .build());
```
Add the import `java.time.LocalDateTime`.

**File 3:** `src/main/resources/application.properties`, under the existing
`# --- Public proposal share links ---` block:
```properties
# Share links expire after this many days. Enforced in PublicProposalService.resolveLink().
app.public-proposal.validity-days=${PUBLIC_PROPOSAL_VALIDITY_DAYS:90}
```

**File 4:** `.env.example`, beneath `PUBLIC_PROPOSAL_BASE_URL`:
```
PUBLIC_PROPOSAL_VALIDITY_DAYS=90
```

**Note:** existing `proposal_link` rows have `expires_at = NULL` and stay valid forever. That is the
correct backward-compatible behaviour — the null check preserves it. Do not backfill.

**VERIFY:**
```bash
./mvnw -q -o compile
grep -c 'getExpiresAt' src/main/java/com/voyra/crm/service/PublicProposalService.java  # must be >=1
```
```bash
git add -A && git commit -m "Enforce proposal-link expiry

expires_at was schema'd, documented, and never checked. Links now expire
after a configurable window (default 90 days) and expired tokens return
the same generic not-found as unknown ones. Pre-existing NULL rows stay
valid.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### T2.2 — Add collision-checked ID generation to all 8 remaining insert paths

**Blueprint:** §8.5 — "Always retry-with-existence-check, and fail loudly rather than looping
forever."

**Problem:** 8 entity insert paths call `IdGenerator.generate6()` raw. At ~10k child rows per tenant
the birthday-collision probability is roughly 2%, surfacing as a confusing 409 on insert.

**Step 1 — create the shared helper.** New file
`src/main/java/com/voyra/crm/util/UniqueIdResolver.java`:

```java
package com.voyra.crm.util;

import java.util.function.Predicate;

/**
 * Blueprint §8.5: entity primary keys are short base-36 strings, so every insert must
 * retry against an existence check and fail loudly rather than loop forever. Centralised
 * here so no insert path can silently skip the check.
 */
public final class UniqueIdResolver {

    private static final int MAX_ATTEMPTS = 10;

    private UniqueIdResolver() {
    }

    /** @param existsById typically {@code repository::existsById} */
    public static String resolve(Predicate<String> existsById) {
        for (int i = 0; i < MAX_ATTEMPTS; i++) {
            String id = IdGenerator.generate6();
            if (!existsById.test(id)) {
                return id;
            }
        }
        throw new IllegalStateException("Unable to generate a unique id after " + MAX_ATTEMPTS + " attempts");
    }
}
```

**Step 2 — replace the 8 raw call sites.** In each, change `.id(IdGenerator.generate6())` to
`.id(UniqueIdResolver.resolve(<repo>::existsById))` and add the import
`com.voyra.crm.util.UniqueIdResolver`.

| File | Approx. line | Entity | Repository to use |
|---|---|---|---|
| `service/VisaService.java` | 42 | `Visa` | `visaRepository` |
| `service/LeadService.java` | 160 | `LeadNote` | `leadNoteRepository` |
| `service/LeadService.java` | 180 | `ProposalItem` | `proposalItemRepository` |
| `service/CustomerService.java` | 80 | `CustomerInteraction` | `interactionRepository` |
| `service/CustomerSubResourceService.java` | 49 | `FamilyMember` | `familyMemberRepository` |
| `service/CustomerSubResourceService.java` | 69 | `CustomerDocument` | `customerDocumentRepository` |
| `service/CustomerSubResourceService.java` | 105 | `FamilyMemberDocument` | `familyMemberDocumentRepository` |
| `service/CustomerSubResourceService.java` | 124 | `CustomerInteraction` | `interactionRepository` |

If a service does not already inject the repository it needs, add it as a
`private final` field (constructor injection is automatic via `@RequiredArgsConstructor`).

**DO NOT CHANGE** `service/LocalFileStorageService.java:45`. That `generate6()` is a filename
component inside a path, not a primary key. Leave it exactly as it is.

**Step 3 — collapse the 8 existing private duplicates.** These already implement the retry loop
correctly but duplicate it. Replace each method body with a delegation, keeping the method so call
sites are untouched:

| File | Method | New body |
|---|---|---|
| `service/BookingService.java` | `generateUniqueBookingId()` | `return UniqueIdResolver.resolve(bookingRepository::existsById);` |
| `service/AgencyService.java` | `generateUniqueTenantId()` | `return UniqueIdResolver.resolve(tenantRepository::existsById);` |
| `service/LeadService.java` | `generateUniqueLeadId()` | `return UniqueIdResolver.resolve(leadRepository::existsById);` |
| `service/CustomerService.java` | `generateUniqueCustomerId()` | `return UniqueIdResolver.resolve(customerRepository::existsById);` |
| `service/AgentService.java` | `generateUniqueAgentId()` | `return UniqueIdResolver.resolve(agentRepository::existsById);` |
| `service/InvoiceService.java` | `generateUniqueId(Predicate)` | `return UniqueIdResolver.resolve(existsById);` |
| `config/DemoDataSeedRunner.java` | `generateUniqueId(Predicate)` | `return UniqueIdResolver.resolve(existsById);` |

Leave `ProposalLinkService.generateUniqueToken()` alone — it uses `generateToken32()`, a different
generator, and is already correct.

**VERIFY:**
```bash
./mvnw -q -o compile
# must print 0:
grep -rh '\.id(IdGenerator\.generate6())' src/main/java/com/voyra/crm/service/ | wc -l
# must print 1 (LocalFileStorageService only):
grep -rh 'IdGenerator\.generate6()' src/main/java/com/voyra/crm/service/ | wc -l
```
```bash
git add -A && git commit -m "Collision-check every entity primary key (blueprint §8.5)

Eight insert paths (Visa, LeadNote, ProposalItem, CustomerInteraction x2,
FamilyMember, CustomerDocument, FamilyMemberDocument) generated raw 6-char
IDs with no existence check. Centralised the retry loop in UniqueIdResolver
and collapsed seven duplicated private implementations into it.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### T2.3 — Add `@JsonIgnore` to entity password fields

**Blueprint:** §8.4 — "Never expose password/secret fields; annotate entity secrets with
`@JsonIgnore`."

No live leak exists (nothing returns these entities), but this is the defence-in-depth the blueprint
requires and it is three annotations.

In each of `entity/Agent.java`, `entity/PlatformAdmin.java`, `entity/Tenant.java`:

Add the import:
```java
import com.fasterxml.jackson.annotation.JsonIgnore;
```
and add `@JsonIgnore` directly above `private String password;` (below the existing `@Column`).

**VERIFY:**
```bash
./mvnw -q -o compile
grep -rh -B1 'private String password;' src/main/java/com/voyra/crm/entity/ | grep -c '@JsonIgnore'  # must be 3
```
```bash
git add -A && git commit -m "Annotate entity password fields @JsonIgnore (blueprint §8.4)

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### T2.4 — Add `created_date` to `family_member` and `proposal_item`

**Blueprint:** §8.4 — every table carries `created_date`, set by `@PrePersist` only when null.

`family_member` and `proposal_item` are the only two tenant tables missing it.

**Step 1 — new tenant migration.** Create
`src/main/resources/db/migration/tenant/V2__add_created_date_to_family_member_and_proposal_item.sql`:

```sql
-- Blueprint §8.4: every table carries created_date. These two were the only tenant
-- tables missing it. Additive and nullable, so existing rows are untouched.
ALTER TABLE family_member ADD COLUMN created_date TIMESTAMP;
ALTER TABLE proposal_item ADD COLUMN created_date TIMESTAMP;
```

**DO NOT EDIT** `V1__init_tenant_schema.sql`. Blueprint §2.5: never edit an applied migration.

**Step 2 — update both entities.** In `entity/FamilyMember.java` and `entity/ProposalItem.java`, add
these imports:
```java
import jakarta.persistence.PrePersist;
import java.time.LocalDateTime;
```
and add, as the last field and method in each class:
```java
    @Column(name = "created_date")
    private LocalDateTime createdDate;

    @PrePersist
    protected void onCreate() {
        if (createdDate == null) {
            createdDate = LocalDateTime.now();
        }
    }
```

**Step 3 — set it explicitly at construction.** The original build hit a real bug where create
responses returned `null` for timestamps because they read the entity before `@PrePersist` fired.
Avoid repeating it: in `LeadService.addProposalItem` (~line 180) and
`CustomerSubResourceService.addFamilyMember` (~line 49), add `.createdDate(LocalDateTime.now())` to
the builder chain, matching what `LeadNote` and the other entities already do.

**VERIFY:**
```bash
./mvnw -q -o compile
ls src/main/resources/db/migration/tenant/   # must show V1 and V2
# Boot the app - the startup runner applies V2 to every existing tenant schema:
set -a; source .env; set +a
./mvnw spring-boot:run 2>&1 | grep -i 'Tenant migrations completed'
```
```bash
psql -U voyra -d voyra_crm_dev -c "\d tenant_<id>.proposal_item" | grep created_date
```
(substitute the real tenant id; find it with `psql -U voyra -d voyra_crm_dev -c 'SELECT id FROM tenant;'`)

```bash
git add -A && git commit -m "Add created_date to family_member and proposal_item (blueprint §8.4)

New tenant migration V2, additive and nullable. Both entities get the
@PrePersist hook, and both create paths set the timestamp explicitly at
construction so the create response never returns null for it.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### T2.5 — Fix the misleading `readOnly` on the proposal approve path, and delete dead code

**Blueprint:** §8.6 (`@Transactional(readOnly = true)` on reads, `@Transactional` on writes),
§10 ("unused private helpers left in services → delete dead code").

**Change 1** — `service/PublicProposalService.java`. `approveProposal` is annotated
`@Transactional(readOnly = true)` but its whole purpose is a write. It works (the inner
`REQUIRES_NEW` transaction is not read-only) but it signals the wrong thing. Change:
```java
    @Transactional(readOnly = true)
    public void approveProposal(String token) {
```
to:
```java
    /**
     * Not readOnly: the tenant-scoped work this delegates to is a write. The outer
     * transaction only resolves the public-schema token.
     */
    @Transactional
    public void approveProposal(String token) {
```

**Change 2** — `service/TenantScopedReadService.java`. Delete the entire
`findLeadByPublicProposalToken` method — it has zero call sites. After deleting it, remove the now
unused imports: `com.voyra.crm.entity.Lead`, `com.voyra.crm.repository.LeadRepository`,
`java.util.Optional`, and the `private final LeadRepository leadRepository;` field.

**VERIFY:**
```bash
./mvnw -q -o compile
grep -c 'findLeadByPublicProposalToken' -r src/main/java/  # must be 0
```
```bash
git add -A && git commit -m "Correct approve-path transaction semantics and remove dead code

approveProposal was marked readOnly despite being a write path.
TenantScopedReadService.findLeadByPublicProposalToken had zero call sites.

Blueprint §8.6, §10.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

## PHASE 3 — PERFORMANCE

Do this phase **before** Phase 4. Pagination (T4.3) is built on the derived queries added here.

### T3.1 — Replace in-memory filtering with derived queries

**Problem:** `listLeads`, `listBookings`, `listVisas` load the full table then `.filter()` in Java —
while `idx_lead_status` and `idx_booking_status` sit unused in the migration.

**File 1:** `repository/LeadRepository.java` — add:
```java
    List<Lead> findByStatus(LeadStatus status);

    List<Lead> findByAssignedToAndStatus(String assignedTo, LeadStatus status);
```
(`findByAssignedTo` already exists. Import `com.voyra.crm.enums.LeadStatus` if absent.)

**File 2:** `service/LeadService.java` — replace the body of `listLeads`:
```java
    @Transactional(readOnly = true)
    public List<LeadResponse> listLeads(LeadStatus statusFilter) {
        return scopedLeads(statusFilter).stream().map(this::toResponse).toList();
    }

    /**
     * Every combination resolves to an indexed derived query - never a full table read
     * filtered in Java. Agent callers are structurally confined to their own assigned rows.
     */
    private List<Lead> scopedLeads(LeadStatus statusFilter) {
        CustomUserPrincipal principal = SecurityContextUtil.getCurrentUserOrThrow();
        if (principal.isAgent()) {
            return statusFilter == null
                    ? leadRepository.findByAssignedTo(principal.userId())
                    : leadRepository.findByAssignedToAndStatus(principal.userId(), statusFilter);
        }
        return statusFilter == null
                ? leadRepository.findAll()
                : leadRepository.findByStatus(statusFilter);
    }
```

**File 3:** `repository/BookingRepository.java` — add:
```java
    List<Booking> findByAgentIdAndType(String agentId, BookingType type);

    List<Booking> findByAgentIdAndBookingStatus(String agentId, BookingStatus bookingStatus);

    List<Booking> findByTypeAndBookingStatus(BookingType type, BookingStatus bookingStatus);

    List<Booking> findByAgentIdAndTypeAndBookingStatus(String agentId, BookingType type, BookingStatus bookingStatus);
```

**File 4:** `service/BookingService.java` — replace `listBookings`'s in-memory filtering with a
`scopedBookings(typeFilter, statusFilter)` private method following the identical shape as
`scopedLeads` above: branch on `principal.isAgent()`, then on which of the two filters are non-null,
selecting the matching derived query. Eight combinations total.

**File 5:** `service/VisaService.java` — `listVisas()` and `getDashboardSummary()` both call
`findAll()` for owners. Leave those as-is (there is no filter parameter to push down) but confirm the
agent branch uses `visaRepository.findByAgentId(...)`. No change needed if it already does.

**VERIFY:**
```bash
./mvnw -q -o compile
# no stream .filter() on a status field left in list methods:
grep -n 'filter(l -> statusFilter' src/main/java/com/voyra/crm/service/LeadService.java   # must print nothing
```
Manual: with the app running, `GET /api/leads?status=NEW` as owner and as agent must return the same
rows they returned before this change.

```bash
git add -A && git commit -m "Push list filtering down to indexed derived queries

listLeads and listBookings loaded the full table and filtered in Java,
leaving idx_lead_status and idx_booking_status unused. Both now resolve
every filter combination to a derived query.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### T3.2 — Eliminate the agent-stats N+1

**Problem:** `AgentService.toPerformanceResponse` runs 8 queries per agent and is called in a loop by
`listAgents()` and again by `ReportService.exportAgentsCsv()`. Ten agents = 80 queries per page load.
This sits in the module whose plan section is titled "Denormalization strategy (avoiding N+1)".

**Design:** three batch queries regardless of agent count, results keyed by agent id. Uses the
currently-empty `models/` package exactly as blueprint §1 intends (internal, non-API, non-entity
computation models).

**Step 1 — lead stats projection.** In `repository/LeadRepository.java` add:
```java
    /** One grouped row per agent - replaces five per-agent count queries. */
    @Query("""
            SELECT l.assignedTo AS agentId,
                   COUNT(l) AS leadsAssigned,
                   SUM(CASE WHEN l.status = :booked THEN 1L ELSE 0L END) AS bookedLeads,
                   SUM(CASE WHEN l.status NOT IN :terminal THEN 1L ELSE 0L END) AS activeLeads,
                   SUM(CASE WHEN l.status IN :proposalStage THEN 1L ELSE 0L END) AS quotationsSent,
                   SUM(CASE WHEN l.followUpDate <= :today AND l.status NOT IN :terminal
                            THEN 1L ELSE 0L END) AS pendingFollowUps
            FROM Lead l
            WHERE l.assignedTo IN :agentIds
            GROUP BY l.assignedTo
            """)
    List<AgentLeadStatsProjection> aggregateLeadStatsByAgent(
            @Param("agentIds") Collection<String> agentIds,
            @Param("booked") LeadStatus booked,
            @Param("terminal") Collection<LeadStatus> terminal,
            @Param("proposalStage") Collection<LeadStatus> proposalStage,
            @Param("today") LocalDate today);

    interface AgentLeadStatsProjection {
        String getAgentId();
        long getLeadsAssigned();
        long getBookedLeads();
        long getActiveLeads();
        long getQuotationsSent();
        long getPendingFollowUps();
    }
```
Imports needed: `java.util.Collection`, `java.time.LocalDate`,
`org.springframework.data.repository.query.Param`, `org.springframework.data.jpa.repository.Query`.

**Step 2 — booking stats projection.** In `repository/BookingRepository.java` add:
```java
    @Query("""
            SELECT b.agentId AS agentId,
                   COUNT(b) AS bookingsCount,
                   COALESCE(SUM(b.sellingPrice), 0) AS totalRevenue,
                   COALESCE(SUM(b.profit), 0) AS totalProfit
            FROM Booking b
            WHERE b.agentId IN :agentIds
            GROUP BY b.agentId
            """)
    List<AgentBookingStatsProjection> aggregateBookingStatsByAgent(@Param("agentIds") Collection<String> agentIds);

    interface AgentBookingStatsProjection {
        String getAgentId();
        long getBookingsCount();
        java.math.BigDecimal getTotalRevenue();
        java.math.BigDecimal getTotalProfit();
    }
```

**Step 3 — note counts.** In `repository/LeadNoteRepository.java` add:
```java
    @Query("""
            SELECT n.authorAgentId AS agentId, COUNT(n) AS noteCount
            FROM LeadNote n
            WHERE n.authorAgentId IN :agentIds
            GROUP BY n.authorAgentId
            """)
    List<AgentNoteCountProjection> aggregateNoteCountsByAgent(@Param("agentIds") Collection<String> agentIds);

    interface AgentNoteCountProjection {
        String getAgentId();
        long getNoteCount();
    }
```

**Step 4 — the internal model.** New file `src/main/java/com/voyra/crm/models/AgentStats.java`:
```java
package com.voyra.crm.models;

import java.math.BigDecimal;

/**
 * Pre-aggregated per-agent metrics, loaded for a whole set of agents in three grouped
 * queries rather than eight queries per agent. Internal computation model - never
 * serialized, never persisted (blueprint §1, models/).
 */
public record AgentStats(
        long leadsAssigned,
        long bookedLeads,
        long activeLeads,
        long quotationsSent,
        long pendingFollowUps,
        long notesLogged,
        long bookingsCount,
        BigDecimal totalRevenue,
        BigDecimal totalProfit
) {
    public static AgentStats empty() {
        return new AgentStats(0, 0, 0, 0, 0, 0, 0, BigDecimal.ZERO, BigDecimal.ZERO);
    }
}
```

**Step 5 — rewire `AgentService`.** Add:
```java
    /**
     * Loads metrics for every supplied agent in three grouped queries, replacing the
     * previous eight-queries-per-agent loop. Agents with no rows in a table are absent
     * from that projection, so every lookup falls back to zero.
     */
    private Map<String, AgentStats> loadStats(List<String> agentIds) {
        if (agentIds.isEmpty()) {
            return Map.of();
        }
        Map<String, LeadRepository.AgentLeadStatsProjection> leads =
                leadRepository.aggregateLeadStatsByAgent(agentIds, LeadStatus.BOOKED, TERMINAL_STATUSES,
                                PROPOSAL_STAGE_OR_LATER, LocalDate.now())
                        .stream().collect(Collectors.toMap(p -> p.getAgentId(), p -> p));
        Map<String, BookingRepository.AgentBookingStatsProjection> bookings =
                bookingRepository.aggregateBookingStatsByAgent(agentIds)
                        .stream().collect(Collectors.toMap(p -> p.getAgentId(), p -> p));
        Map<String, Long> notes = leadNoteRepository.aggregateNoteCountsByAgent(agentIds)
                .stream().collect(Collectors.toMap(p -> p.getAgentId(), p -> p.getNoteCount()));

        Map<String, AgentStats> result = new HashMap<>();
        for (String id : agentIds) {
            var l = leads.get(id);
            var b = bookings.get(id);
            result.put(id, new AgentStats(
                    l != null ? l.getLeadsAssigned() : 0,
                    l != null ? l.getBookedLeads() : 0,
                    l != null ? l.getActiveLeads() : 0,
                    l != null ? l.getQuotationsSent() : 0,
                    l != null ? l.getPendingFollowUps() : 0,
                    notes.getOrDefault(id, 0L),
                    b != null ? b.getBookingsCount() : 0,
                    b != null && b.getTotalRevenue() != null ? b.getTotalRevenue() : BigDecimal.ZERO,
                    b != null && b.getTotalProfit() != null ? b.getTotalProfit() : BigDecimal.ZERO));
        }
        return result;
    }
```

Change the signature of `toPerformanceResponse` to take the pre-loaded stats:
```java
    private AgentPerformanceResponse toPerformanceResponse(Agent agent, AgentStats stats) {
```
and inside it, delete every `leadRepository.countBy*` / `leadNoteRepository.countBy*` /
`bookingRepository.countByAgentId` / `bookingRepository.sumRevenueByAgentId` call, reading from
`stats` instead. Keep the commission and conversion maths exactly as they are, sourcing
`totalProfit` / `bookedLeads` / `leadsAssigned` from `stats`.

Update the four callers:
- `listAgents()`:
  ```java
  List<Agent> agents = agentRepository.findByTenantId(ownerTenantId());
  Map<String, AgentStats> stats = loadStats(agents.stream().map(Agent::getId).toList());
  return agents.stream()
          .map(a -> toPerformanceResponse(a, stats.getOrDefault(a.getId(), AgentStats.empty())))
          .toList();
  ```
- `getAgent(id)`, `updateAgent(...)`, `updateStatus(...)`: each becomes
  ```java
  Agent agent = /* existing lookup */;
  return toPerformanceResponse(agent, loadStats(List.of(agent.getId()))
          .getOrDefault(agent.getId(), AgentStats.empty()));
  ```

Add imports: `com.voyra.crm.models.AgentStats`, `java.util.HashMap`, `java.util.Map`,
`java.util.stream.Collectors`.

**Note:** `bookingRepository.sumRevenueByAgentId` is still used by `DashboardService`. Do not delete
it.

**VERIFY:**
```bash
./mvnw -q -o compile
```
Query-count check — add `spring.jpa.properties.hibernate.generate_statistics=true` to
`application.properties` temporarily, boot the app, call `GET /api/agents` as the owner, and confirm
the logged statement count is a small constant (≈4) rather than scaling with agent count. **Remove
that property afterwards.**

```bash
git add -A && git commit -m "Eliminate the agent-stats N+1

AgentService.toPerformanceResponse ran eight queries per agent inside a
loop over all agents, hit again by exportAgentsCsv. Replaced with three
grouped projection queries loaded once into an AgentStats map, so cost is
constant in agent count.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### T3.3 — Convert the four `findAll()` reports to GROUP BY

**Problem:** `BACKEND_PLAN.md` claims "Reports/aggregates: implemented as repository projection
interfaces with explicit `@Query` joins... a single `GROUP BY` query per report." Four of six reports
actually load the full table and aggregate in Java streams. Make the claim true.

**Step 1** — `repository/LeadRepository.java`:
```java
    @Query("SELECT l.source AS category, COUNT(l) AS count FROM Lead l GROUP BY l.source")
    List<CategoryCountProjection> countGroupedBySource();

    @Query("SELECT l.status AS category, COUNT(l) AS count FROM Lead l WHERE l.status <> :excluded GROUP BY l.status")
    List<CategoryCountProjection> countGroupedByStatusExcluding(@Param("excluded") LeadStatus excluded);

    interface CategoryCountProjection {
        String getCategory();
        long getCount();
    }
```

**Step 2** — `repository/BookingRepository.java`:
```java
    @Query("SELECT b.type AS category, COUNT(b) AS count FROM Booking b GROUP BY b.type")
    List<LeadRepository.CategoryCountProjection> countGroupedByType();

    @Query("""
            SELECT YEAR(b.bookingDate) AS year, MONTH(b.bookingDate) AS month,
                   COALESCE(SUM(b.sellingPrice), 0) AS revenue,
                   COALESCE(SUM(b.profit), 0) AS profit
            FROM Booking b
            WHERE b.bookingDate >= :from
            GROUP BY YEAR(b.bookingDate), MONTH(b.bookingDate)
            """)
    List<MonthlyRevenueProjection> aggregateMonthlyRevenueSince(@Param("from") LocalDate from);

    interface MonthlyRevenueProjection {
        int getYear();
        int getMonth();
        java.math.BigDecimal getRevenue();
        java.math.BigDecimal getProfit();
    }
```

**Step 3** — `service/ReportService.java`, rewrite four methods:

- `getLeadSourceDistribution()` → map `leadRepository.countGroupedBySource()` straight to
  `CategoryCountResponse`. Delete the `countBy` helper's use here.
- `getBookingTypeDistribution()` → map `bookingRepository.countGroupedByType()`.
- `getRevenueTrend(months)` → call `aggregateMonthlyRevenueSince(YearMonth.now().minusMonths(months - 1L).atDay(1))`,
  index the results by `YearMonth`, then walk the requested month window filling gaps with
  `BigDecimal.ZERO`. Gap-filling stays in Java — it is O(months), not O(rows).
- `getConversionFunnel()` → call `countGroupedByStatusExcluding(LeadStatus.LOST)`, build a
  `Map<LeadStatus, Long>`, then compute the cumulative "reached this stage or beyond" counts over the
  ≤6 `FUNNEL_STAGES` buckets in Java. **Preserve the existing cumulative semantics exactly** — each
  stage counts leads at that stage *or later*, so counts are monotonically non-increasing.

Delete the now-unused private `countBy` helper if nothing else calls it.

**Step 4** — `service/DashboardService.java`, `getOwnerSummary()`. Replace the `findAll()` +
stream for `pendingPayments` with a repository aggregate. Add to
`repository/ClientInvoiceRepository.java`:
```java
    @Query("SELECT COALESCE(SUM(c.totalWithGst - c.amountPaid), 0) FROM ClientInvoice c WHERE c.status <> :paid")
    BigDecimal sumOutstanding(@Param("paid") InvoiceStatus paid);
```
and call `clientInvoiceRepository.sumOutstanding(InvoiceStatus.PAID)`.

**Step 5** — `service/AgencyService.java`, `toResponse(...)`. Replace
`agentRepository.findByTenantId(tenant.getId()).size()` (loads full entities to count them, inside a
loop over all tenants) with a real count. Add to `repository/AgentRepository.java`:
```java
    long countByTenantId(String tenantId);
```
and call `agentRepository.countByTenantId(tenant.getId())`.

**VERIFY:**
```bash
./mvnw -q -o compile
# only DashboardService/InvoiceService/VisaService/AgencyService owner-branch findAll() should remain:
grep -n 'findAll()' src/main/java/com/voyra/crm/service/ReportService.java   # must print nothing
```
Manual: with seeded data, each of the six report endpoints must return **byte-identical** JSON to
what it returned before this task. Capture the responses before starting and diff them after.

```bash
git add -A && git commit -m "Convert the four findAll() reports to GROUP BY aggregates

revenue-trend, lead-source-distribution, booking-type-distribution and
conversion-funnel each loaded the full table and aggregated in Java,
contradicting BACKEND_PLAN.md's own compliance claim. Also replaced the
dashboard's pendingPayments scan and AgencyService's findByTenantId().size().

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

## PHASE 4 — API CONTRACT

### T4.1 — Add validation to the five patch DTOs

**Blueprint:** §7.4 / §8.7 — structural validation on the DTO, with messages written for an end user.

**Problem:** `AgentUpdateRequest`, `BookingUpdateRequest`, `CustomerUpdateRequest`,
`VisaChecklistUpdateRequest`, `VisaTrackerUpdateRequest` carry zero constraints. Nullable-for-patch
is correct, but bounds are still needed: a 200-character name currently reaches the `VARCHAR(150)`
column and surfaces as a `DataIntegrityViolationException` → generic 400, instead of a clean
field-level error.

**Rule:** never add `@NotNull`/`@NotBlank` to a patch DTO — null means "not supplied". Only add
`@Size`, `@Email`, `@DecimalMin`/`@DecimalMax`, and `@PositiveOrZero`.

Column lengths come from `V1__init_tenant_schema.sql` and `V1__init_public_schema.sql`.

**`dto/AgentUpdateRequest.java`:**
```java
    @Size(max = 150, message = "Name must be 150 characters or fewer")
    private String name;

    @Size(max = 20, message = "Phone must be 20 characters or fewer")
    private String phone;

    private AgentDepartment department;

    @DecimalMin(value = "0.00", message = "Commission rate cannot be negative")
    @DecimalMax(value = "100.00", message = "Commission rate cannot exceed 100")
    @Schema(description = "Percentage of booking profit paid as commission", example = "5.00")
    private BigDecimal commissionRate;
```

**`dto/CustomerUpdateRequest.java`:** `name` 150, `email` `@Email` + 150, `countryCode` 6,
`phone` 20, `gender` 30, `city` 100, `country` 100, `nationality` 100, `passportNumber` 20,
`preferredAirline` 100, `preferredCabin` 30.

**`dto/BookingUpdateRequest.java`:** `pnr` 20, `ticketNo` 50, `airline` 100, `supplier` 150,
`tripType` 20; `netCost` and `sellingPrice` get
`@DecimalMin(value = "0.00", message = "Net cost cannot be negative")` (and the selling-price
equivalent).

**`dto/VisaChecklistUpdateRequest.java`:** `visaValidity` `@Size(max = 50, ...)`. The booleans and
dates need no constraint.

**`dto/VisaTrackerUpdateRequest.java`:** all five fields are `Boolean` — **no constraints are
appropriate**. Leave the file unchanged apart from the `@Schema` work in T4.2.

Imports as needed: `jakarta.validation.constraints.Size`, `.Email`, `.DecimalMin`, `.DecimalMax`.

**Also:** confirm every controller method taking one of these DTOs has `@Valid` on the parameter.
`BookingController.updateBooking` and `CustomerController.updateCustomer` already do; check
`OwnerAgentController.updateAgent`, `VisaController.updateChecklist`, and
`LeadController.updateVisaTracker` and add `@Valid` where missing.

**VERIFY:**
```bash
./mvnw -q -o compile
for f in AgentUpdateRequest BookingUpdateRequest CustomerUpdateRequest VisaChecklistUpdateRequest; do
  printf "%-30s %s\n" "$f" "$(grep -cE '@Size|@Email|@DecimalMin|@DecimalMax' src/main/java/com/voyra/crm/dto/$f.java)"
done
```
Manual: `PUT /api/agents/{id}` with a 200-character `name` must return **400 with
`details.errors.name`**, not a 400 with a database message.

```bash
git add -A && git commit -m "Add field bounds to the five patch DTOs (blueprint §7.4, §8.7)

Update requests carried zero constraints, so over-length values reached the
column and surfaced as DataIntegrityViolationException rather than a clean
field-level validation error. Nullable-for-patch semantics preserved: only
size/format/range constraints added, never @NotNull.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### T4.2 — `@Schema` on every DTO field

**Blueprint:** §8.13 — "`@Schema(description, example)` on every DTO field."

**Problem:** 59 annotations across 452 fields (~13%). 37 of 60 DTOs have none. Swagger UI is the
stated primary verification tool and the contract the frontend will integrate against.

**This is the largest mechanical task in the plan. Do it in six commits, ten DTOs each**, so context
stays manageable and each commit is reviewable.

**Rules:**
- Every `private` field gets `@Schema(description = "...", example = "...")`.
- Descriptions are written for an API consumer, not a developer: "Total invoice amount before GST",
  not "the amount field".
- `example` is mandatory for `String`, numeric, and date fields. It may be omitted for `Boolean`
  (self-evident) and for nested-object/`List<NestedDto>` fields.
- Enum fields: give a real member as the example, e.g. `example = "PROPOSAL_SENT"`.
- Money: `example = "45000.00"`. Dates: `example = "2026-08-13"`. Timestamps:
  `example = "2026-08-13T09:15:22"`. IDs: `example = "A1B2C3"`.
- Every DTO class also gets a class-level `@Schema(description = "...")` if it lacks one.
- **Never** put a real credential in an `example`. For `CredentialsResponse.password` use
  `example = "********"`.
- Add `import io.swagger.v3.oas.annotations.media.Schema;` where missing.
- **Do not change any field name, type, or order.** This task adds annotations only.

**Batches** (alphabetical, from `ls src/main/java/com/voyra/crm/dto/`):

| Batch | DTOs |
|---|---|
| 1 | ActiveStatusUpdateRequest, AgencyCreateRequest, AgencyCreateResponse, AgencyResponse, AgentCreateRequest, AgentCreateResponse, AgentDashboardSummaryResponse, AgentPerformanceResponse, AgentUpdateRequest, ApiErrorResponse |
| 2 | BookingCreateRequest, BookingPaymentStatusUpdateRequest, BookingResponse, BookingStatusUpdateRequest, BookingSummaryResponse, BookingUpdateRequest, CategoryCountResponse, ClientInvoiceCreateRequest, ClientInvoicePaymentRequest, ClientInvoiceResponse |
| 3 | CredentialsResponse, CustomerCreateRequest, CustomerDetailResponse, CustomerLookupResponse, CustomerResponse, CustomerUpdateRequest, DocumentResponse, FamilyMemberCreateRequest, FamilyMemberResponse, GuestDetails |
| 4 | InteractionCreateRequest, InteractionResponse, InvoiceSummaryResponse, LeadAssignRequest, LeadCreateRequest, LeadDetailResponse, LeadFollowUpUpdateRequest, LeadNoteCreateRequest, LeadNoteResponse, LeadResponse |
| 5 | LeadStatusUpdateRequest, LoginRequest, LoginResponse, MonthlyRevenuePoint, OwnerDashboardSummaryResponse, ProposalItemCreateRequest, ProposalItemResponse, ProposalLinkResponse, PublicProposalItemResponse, PublicProposalResponse |
| 6 | SimpleAckResponse, SupplierInvoiceCreateRequest, SupplierInvoiceResponse, SupplierInvoiceStatusUpdateRequest, VisaChecklistUpdateRequest, VisaCreateRequest, VisaDashboardSummaryResponse, VisaResponse, VisaTrackerResponse, VisaTrackerUpdateRequest |

`ApiErrorResponse` already has full `@Schema` coverage — verify and skip if so.

**Special care — `PublicProposalResponse`:** this DTO's javadoc records that `netCost`, `margin`,
`status`, `priority`, `source`, `assignedTo`, `notes`, `visaTracker`, `phone`, `email`, `budget`,
`lostReason`, and `customerId` are deliberately absent. **Preserve that javadoc verbatim and add no
new fields.**

**VERIFY** (after each batch):
```bash
./mvnw -q -o compile
```
**VERIFY** (after batch 6 — this is the §8.13 audit check going green):
```bash
F=$(grep -rh '^    private ' src/main/java/com/voyra/crm/dto/*.java | wc -l | tr -d ' ')
S=$(grep -rh '^    @Schema' src/main/java/com/voyra/crm/dto/*.java | wc -l | tr -d ' ')
echo "fields=$F schemas=$S"; [ "$F" = "$S" ] && echo "OK" || echo "FAIL: $((F-S)) fields undocumented"
```
Then boot the app and open `http://localhost:8080/swagger-ui/index.html` — spot-check that request
bodies show example values and every field has a description.

Commit after each batch:
```bash
git add -A && git commit -m "Document DTO fields with @Schema (batch N of 6)

Blueprint §8.13.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### T4.3 — Opt-in pagination on list endpoints

**Decision (already made):** optional `page`/`size` query parameters. When absent, the response is
byte-identical to today (a bare JSON array) so nothing breaks. When present, the response is a paged
envelope. The frontend adopts it per-screen.

**Step 1 — the envelope.** New file `src/main/java/com/voyra/crm/dto/PagedResponse.java`:
```java
package com.voyra.crm.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;
import org.springframework.data.domain.Page;

import java.util.List;
import java.util.function.Function;

/**
 * Returned only when a caller supplies ?page=. Without it, list endpoints return a bare
 * JSON array exactly as before, so adding pagination breaks no existing consumer.
 */
@Data
@Builder
@Schema(description = "A single page of results")
public class PagedResponse<T> {

    @Schema(description = "The results on this page")
    private List<T> content;

    @Schema(description = "Zero-based index of this page", example = "0")
    private int page;

    @Schema(description = "Maximum results per page", example = "25")
    private int size;

    @Schema(description = "Total results across all pages", example = "120")
    private long totalElements;

    @Schema(description = "Total number of pages", example = "5")
    private int totalPages;

    public static <E, D> PagedResponse<D> from(Page<E> source, Function<E, D> mapper) {
        return PagedResponse.<D>builder()
                .content(source.getContent().stream().map(mapper).toList())
                .page(source.getNumber())
                .size(source.getSize())
                .totalElements(source.getTotalElements())
                .totalPages(source.getTotalPages())
                .build();
    }
}
```

**Step 2 — the page-request helper.** New file
`src/main/java/com/voyra/crm/util/PageRequestUtil.java`:
```java
package com.voyra.crm.util;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

/** Normalises and bounds the optional page/size query parameters. */
public final class PageRequestUtil {

    private static final int DEFAULT_SIZE = 25;
    private static final int MAX_SIZE = 200;

    private PageRequestUtil() {
    }

    /** @return null when the caller did not request pagination */
    public static Pageable resolve(Integer page, Integer size) {
        if (page == null) {
            return null;
        }
        int safePage = Math.max(page, 0);
        int safeSize = size == null ? DEFAULT_SIZE : Math.min(Math.max(size, 1), MAX_SIZE);
        return PageRequest.of(safePage, safeSize);
    }
}
```

**Step 3 — worked example: leads.** Apply this exact shape, then repeat it mechanically.

`repository/LeadRepository.java` — add `Pageable` overloads next to the existing derived queries:
```java
    Page<Lead> findByAssignedTo(String assignedTo, Pageable pageable);
    Page<Lead> findByStatus(LeadStatus status, Pageable pageable);
    Page<Lead> findByAssignedToAndStatus(String assignedTo, LeadStatus status, Pageable pageable);
```
(`JpaRepository` already provides `findAll(Pageable)`.)
Imports: `org.springframework.data.domain.Page`, `org.springframework.data.domain.Pageable`.

`service/LeadService.java` — add a paged sibling to `listLeads`, reusing the same scoping logic:
```java
    @Transactional(readOnly = true)
    public PagedResponse<LeadResponse> listLeads(LeadStatus statusFilter, Pageable pageable) {
        CustomUserPrincipal principal = SecurityContextUtil.getCurrentUserOrThrow();
        Page<Lead> page;
        if (principal.isAgent()) {
            page = statusFilter == null
                    ? leadRepository.findByAssignedTo(principal.userId(), pageable)
                    : leadRepository.findByAssignedToAndStatus(principal.userId(), statusFilter, pageable);
        } else {
            page = statusFilter == null
                    ? leadRepository.findAll(pageable)
                    : leadRepository.findByStatus(statusFilter, pageable);
        }
        return PagedResponse.from(page, this::toResponse);
    }
```

`controller/LeadController.java` — the list method becomes:
```java
    @GetMapping
    @Operation(summary = "List leads",
            description = "Agents see only their own assigned leads. Supply ?page= for a paged "
                    + "envelope; omit it for the full list as a plain array.")
    public ResponseEntity<Object> listLeads(
            @RequestParam(value = "status", required = false) LeadStatus status,
            @RequestParam(value = "page", required = false) Integer page,
            @RequestParam(value = "size", required = false) Integer size) {
        Pageable pageable = PageRequestUtil.resolve(page, size);
        return ResponseEntity.ok(pageable == null
                ? leadService.listLeads(status)
                : leadService.listLeads(status, pageable));
    }
```
Keep the existing `@PreAuthorize` behaviour (inherited from the class annotation) unchanged.

**Step 4 — repeat for the other five list endpoints,** using the identical pattern:

| Controller | Method | Service | Existing filters to preserve |
|---|---|---|---|
| `BookingController` | `listBookings` | `BookingService` | `type`, `status` |
| `CustomerController` | `listCustomers` | `CustomerService` | `search` |
| `VisaController` | `listVisas` | `VisaService` | none |
| `InvoiceController` | `listClientInvoices` | `InvoiceService` | none |
| `OwnerAgentController` | `listAgents` | `AgentService` | none |

**Do not paginate:** `PlatformAgencyController.listAgencies` (bounded by tenant count), the two
dashboard summaries, any report endpoint, or any CSV export. CSV exports must keep reading the
**unpaged** service method — a paginated export would silently truncate.

**Note on `CustomerService.listCustomers(search)`:** if the search filter is applied in Java rather
than as a derived query, add `findByNameContainingIgnoreCase(String, Pageable)` (and the agent-scoped
equivalent) rather than paginating a filtered in-memory list — pagination over an in-memory filter
would still read the whole table.

**VERIFY:**
```bash
./mvnw -q -o compile
```
Manual, app running:
```bash
OWNER_TOKEN=$(curl -s -X POST localhost:8080/api/auth/login/owner \
  -H 'Content-Type: application/json' \
  -d '{"email":"owner@globalexplorer.com","password":"Passw0rd!"}' | python3 -c 'import sys,json;print(json.load(sys.stdin)["token"])')

# unpaged: must be a bare JSON array, identical to before this task
curl -s localhost:8080/api/leads -H "Authorization: Bearer $OWNER_TOKEN" | head -c 200

# paged: must be an envelope with content/page/size/totalElements/totalPages
curl -s 'localhost:8080/api/leads?page=0&size=2' -H "Authorization: Bearer $OWNER_TOKEN"

# clamped: size=9999 must come back as size 200, not 9999
curl -s 'localhost:8080/api/leads?page=0&size=9999' -H "Authorization: Bearer $OWNER_TOKEN" | grep -o '"size":[0-9]*'
```

```bash
git add -A && git commit -m "Add opt-in pagination to the six list endpoints

Optional ?page=&size= returns a PagedResponse envelope backed by a real
Pageable query; omitting page returns the existing bare array unchanged, so
no current consumer breaks. Size is clamped to 200. CSV exports keep the
unpaged path so they can never silently truncate.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

## PHASE 5 — TESTS

**Blueprint §10:** "No test sources → Add unit tests for services and slice tests for controllers as
the app grows." This is the deviation the original plan reproduced most seriously.

**Goal:** convert every correctness claim in `PROGRESS.md` from an assertion into an executable
assertion. Target ~28 tests.

### T5.1 — Test infrastructure

**Step 1 — Docker.** Integration tests use Testcontainers.
```bash
docker info >/dev/null 2>&1 && echo "Docker OK" || echo "Docker MISSING"
```
If missing: `brew install --cask docker`, launch Docker Desktop, then re-check. Do not proceed to
T5.5 without it. T5.2–T5.4 need no Docker.

**Step 2 — pom dependencies.** Add inside the existing `<!-- Test -->` block in `pom.xml` (versions
are managed by the Spring Boot parent BOM — do **not** pin them):
```xml
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-testcontainers</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.testcontainers</groupId>
            <artifactId>postgresql</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.testcontainers</groupId>
            <artifactId>junit-jupiter</artifactId>
            <scope>test</scope>
        </dependency>
```

**Step 3 — test properties.** New file `src/test/resources/application-test.properties`:
```properties
# Secrets that must exist for the context to start. Test-only values, never used elsewhere.
app.jwt.secret=test-only-jwt-secret-key-at-least-256-bits-long-for-hs256-signing
app.security.password-encryption-key=dGVzdC1vbmx5LWFlcy1rZXktMzItYnl0ZXMtbG9uZyE=
app.seed.demo-data=false
app.public-proposal.base-url=http://localhost:3000/proposal
app.public-proposal.validity-days=90
app.storage.provider=local
app.storage.local.base-dir=./target/test-uploads
logging.level.com.voyra.crm=WARN
```

**Step 4 — the integration base class.** New file
`src/test/java/com/voyra/crm/AbstractIntegrationTest.java`:
```java
package com.voyra.crm;

import org.junit.jupiter.api.Tag;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Real Postgres via Testcontainers. Schema-per-tenant cannot be exercised against an
 * in-memory database - search_path routing, TEXT[] columns, and programmatic per-tenant
 * Flyway all require the real engine.
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
@Tag("integration")
public abstract class AbstractIntegrationTest {

    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("voyra_crm_test")
                    .withUsername("voyra")
                    .withPassword("voyra");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }
}
```

**VERIFY:**
```bash
./mvnw -q -o test-compile
```
```bash
git add -A && git commit -m "Add test infrastructure: Testcontainers Postgres and test profile

Blueprint §10.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### T5.2 — Pure unit tests (no Spring context, no DB)

Create these under `src/test/java/com/voyra/crm/util/`:

**`MarginCalculatorTest`** (5 tests) — margin% = (selling − net) / selling × 100, one decimal place,
HALF_UP:
- `netCost=8000, sellingPrice=10000` → `20.0`
- `sellingPrice=0` → `0` (guard against divide-by-zero)
- `sellingPrice=null` → `0`
- `netCost=null` → `100.0`
- selling < net (loss) → a negative percentage

**`VisaStatusCalculatorTest`** (6 tests) — the priority ladder
`REJECTED > APPROVED > SUBMITTED > APPOINTMENT_SCHEDULED > DOCUMENTS_PENDING`. Include the
**overlap case the ladder exists to resolve**: a `Visa` with `rejected=true` AND `approved=true` must
return `REJECTED`. And one with `submittedToEmbassy=true` AND `appointmentDate` set must return
`SUBMITTED`.

**`IdGeneratorTest`** (4 tests) — `generate6()` length 6 and matches `^[0-9A-Z]{6}$`;
`generateToken32()` length 32; `generateAlphanumeric(0)` and `generateAlphanumeric(33)` both throw
`IllegalArgumentException`.

**`TenantSearchPathUtilTest`** (5 tests) — **this is the SQL-injection guard, §3.3.** Assert:
- `toSchemaPath("ABC123")` → `"tenant_abc123, public"`
- `toSchemaPath(null)` → `"public"`
- `toSchemaPath("")` → `"public"`
- `toSchemaPath("abc; DROP TABLE tenant")` → `"public"` (rejected by the allowlist regex)
- `toSchemaPath("abc'--")` → `"public"`

**`UniqueIdResolverTest`** (3 tests) — returns an unused id first try; retries past a colliding
predicate; throws `IllegalStateException` when the predicate always returns `true`.

**`CsvWriterTest`** (3 tests) — header + rows produce the expected line count; a value containing a
comma is quoted; a value containing a double-quote is escaped.

**VERIFY:**
```bash
./mvnw -q -o test -Dtest='*Test' -DfailIfNoTests=false
```
```bash
git add -A && git commit -m "Add unit tests for calculators, ID generation, and the search_path guard

26 tests. TenantSearchPathUtilTest covers the allowlist regex that is the
only defence against SQL injection through the tenant identifier (§3.3).

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### T5.3 — Service tests with Mockito (no Spring context)

Use `@ExtendWith(MockitoExtension.class)`, `@Mock` repositories, `@InjectMocks` service. Where a
service reads the principal via `SecurityContextUtil`, set it up with
`SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(principal, null, authorities))`
in an `@BeforeEach`, and `SecurityContextHolder.clearContext()` in an `@AfterEach`.

Create under `src/test/java/com/voyra/crm/service/`:

**`InvoiceServiceGstTest`** (3) — GST is server-computed and never client-trusted:
- default rate 18% on `amount=10000` → `gst=1800.00`, `totalWithGst=11800.00`
- an explicit `gstRate=5` is honoured
- a newly created invoice is always `PENDING` with `amountPaid=0`, regardless of the request

**`BookingProfitTest`** (3) — `profit = sellingPrice − netCost`, always recomputed server-side:
- on create
- on update, when either cost changes
- a client-supplied `profit` in the request body is ignored

**`LeadBusinessRuleTest`** (4) — the BRD rules:
- `updateStatus(LOST)` with a null `lostReason` throws `IllegalArgumentException` (→ 400)
- `updateStatus(LOST)` with a blank `lostReason` throws
- `updateStatus(LOST)` with a reason succeeds and persists it
- moving away from `LOST` clears `lostReason`

**`LeadScopingTest`** (3) — the mock UI's scoping bug, fixed:
- an AGENT calling `findAccessibleLead` on a lead assigned to someone else throws
  `AccessDeniedException`
- an AGENT on their own lead succeeds
- an AGENCY_OWNER on any lead in the tenant succeeds

**`AgentRemovalTest`** (2) — `removeAgent` throws `IllegalStateException` (→ 409) when the agent has
non-terminal leads; succeeds when they do not.

**`PublicProposalExpiryTest`** (3) — `getProposal` throws for an unknown token, for an expired token,
and **both throw the identical message** (assert `getMessage()` equality between the two — the
disclosure guarantee).

**VERIFY:**
```bash
./mvnw -q -o test
```
```bash
git add -A && git commit -m "Add service tests for money rules, business rules, and agent scoping

18 tests covering GST/profit server-computation, the mandatory-reason rules,
the agent-scoping fix, agent-removal blocking, and the proposal-link
disclosure guarantee.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### T5.4 — Controller slice tests

Use `@WebMvcTest(TheController.class)` with `@MockBean` for the service and
`@Import(SecurityConfig.class)` so the real authorization rules apply. Add
`@AutoConfigureMockMvc(addFilters = true)`.

Create under `src/test/java/com/voyra/crm/controller/`:

**`PublicProposalControllerTest`** (3) — **the single most important test in the suite.**
`PROGRESS.md` claims the public proposal JSON structurally cannot contain internal fields. Assert it
on the raw response body:
```java
    @Test
    void publicProposalNeverExposesInternalFields() throws Exception {
        given(publicProposalService.getProposal("TOKEN")).willReturn(sampleResponse());

        String body = mockMvc.perform(get("/api/public/proposals/TOKEN"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        for (String forbidden : List.of("netCost", "margin", "marginPercent", "status", "priority",
                "source", "assignedTo", "notes", "visaTracker", "phone", "email", "budget",
                "lostReason", "customerId")) {
            assertThat(body).doesNotContain(forbidden);
        }
    }
```
Plus: the endpoint is reachable with no `Authorization` header; `POST /{token}/approve` returns
`{"success":true}`.

> **Note:** `customerName` is a legitimate field on this DTO, so asserting the absence of
> `customerId` via `doesNotContain` is safe only because the two strings differ. Do not add a
> `"customer"` prefix check.

**`SupplierInvoiceAuthTest`** (3) — with `@WithMockUser(roles = "AGENT")`, all three supplier
endpoints return 403; with `roles = "AGENCY_OWNER"` they return 200/2xx.

**`LeadControllerAuthTest`** (3) — `PATCH /api/leads/{id}/assign` is 403 for an AGENT and 2xx for an
AGENCY_OWNER; an unauthenticated request to `GET /api/leads` is 401.

**`GlobalExceptionHandlerTest`** (5) — a small `@RestController` fixture that throws each exception
type, asserting the §6.1 status contract: `IllegalArgumentException`→400,
`IllegalStateException`→409, `AccessDeniedException`→403, `MethodArgumentNotValidException`→400 with
`details.errors`, and that every error body carries `success:false`, `status`, `path`, and
`timestamp`.

**VERIFY:**
```bash
./mvnw -q -o test
```
```bash
git add -A && git commit -m "Add controller slice tests for the public proposal and authorization surface

14 tests. PublicProposalControllerTest asserts the pricing-safety guarantee
against the raw JSON body rather than the DTO type, so a future field
addition to the response fails the build.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### T5.5 — Tenant isolation integration test

**The claim being made executable:** `PROGRESS.md` states cross-tenant access is structurally
blocked, verified manually with two real agencies. Make it a test.

New file `src/test/java/com/voyra/crm/integration/TenantIsolationIT.java`, extending
`AbstractIntegrationTest`:

1. Create two agencies via `AgencyService` (as a `SUPER_ADMIN` principal). This provisions two real
   tenant schemas via `TenantFlywayMigrator`.
2. Set `TenantContext` to agency A, create a Lead and a Customer, capture their IDs, clear the
   context.
3. Set `TenantContext` to agency B. Assert `leadRepository.findById(<A's lead id>)` is **empty** —
   guessing a valid ID from another tenant returns nothing, because `search_path` never points at
   A's schema.
4. Assert the reverse direction too.
5. Assert `TenantContext.getTenantId()` is null after the test's `finally` block — no ThreadLocal
   leak (§3.5 rule 1).

Also add **`ProposalLinkResolutionIT`** (2 tests): generate a proposal link inside tenant A, then
resolve it through `PublicProposalService` **with no tenant context set at all**, and assert the
correct lead comes back from the correct schema. Then assert a token from tenant A does not resolve
against tenant B's data.

**VERIFY:**
```bash
./mvnw -q -o test
./mvnw -q -o verify
```
```bash
git add -A && git commit -m "Add tenant isolation and cross-tenant resolution integration tests

Converts PROGRESS.md's manually-verified isolation claim into an executable
one: two real provisioned tenant schemas, ID-guessing across the boundary
returns empty, and the unauthenticated proposal path resolves the correct
schema from a bare token with no tenant context set.

Blueprint §3.5, §10.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

## PHASE 6 — PRODUCTION READINESS (Google Cloud Run)

### T6.1 — Configuration hardening

**Blueprint §8.12:** "Secrets have **no** default, so the app fails fast when unset."

**File:** `src/main/resources/application.properties`

**Change 1 — remove the default from the DB password.** `spring.datasource.password=${DB_PASSWORD:}`
supplies an empty default, which silently works locally and silently fails to fail in production.
Change to `spring.datasource.password=${DB_PASSWORD}`. Then add `DB_PASSWORD=` explicitly to your
local `.env` (empty is fine there — local trust auth).

**Change 2 — make CORS origins a real environment variable.** Currently
`@Value("${app.cors.allowed-origins:http://localhost:3000}")` is read straight from a property with
no env indirection. Add to `application.properties`:
```properties
# --- CORS ---
# Comma-separated explicit origin allowlist. Never "*" - allowCredentials is true (§4.6).
app.cors.allowed-origins=${CORS_ALLOWED_ORIGINS:http://localhost:3000}
```

**Change 3 — logging level.** `logging.level.com.voyra.crm=DEBUG` is hardcoded. Change to:
```properties
logging.level.com.voyra.crm=${LOG_LEVEL:INFO}
```
and set `LOG_LEVEL=DEBUG` in your local `.env`.

**Change 4 — Cloud Run port.** Cloud Run injects `PORT`. Change
`server.port=${SERVER_PORT:8080}` to `server.port=${PORT:${SERVER_PORT:8080}}`.

**Change 5 — `.env.example`,** add every new variable with a placeholder:
```
CORS_ALLOWED_ORIGINS=http://localhost:3000
LOG_LEVEL=DEBUG
```

**VERIFY:**
```bash
./mvnw -q -o compile
# app must FAIL FAST with no secrets set:
env -u JWT_SECRET -u PASSWORD_ENCRYPTION_KEY -u DB_PASSWORD ./mvnw spring-boot:run 2>&1 | \
  grep -qi 'Could not resolve placeholder' && echo "OK: fails fast" || echo "FAIL: started without secrets"
```
```bash
git add -A && git commit -m "Harden configuration for deployment (blueprint §8.12)

Secrets no longer carry defaults so the app fails fast when unset. CORS
origins, log level, and port are environment-driven; Cloud Run's injected
PORT takes precedence.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### T6.2 — Health endpoint and startup behaviour

**Step 1 — actuator.** Add to `pom.xml`:
```xml
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-actuator</artifactId>
        </dependency>
```

**Step 2 — properties:**
```properties
# --- Actuator (Cloud Run startup/liveness probes) ---
management.endpoints.web.exposure.include=health
management.endpoint.health.probes.enabled=true
management.endpoint.health.show-details=never
```

**Step 3 — permit the health endpoint** in `config/SecurityConfig.java`, adding to the
`authorizeHttpRequests` chain **above** `.anyRequest().authenticated()`:
```java
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
```

**Step 4 — guard the tenant-migration startup runner.** `TenantMigrationStartupRunner` iterates every
tenant and runs Flyway on every boot. On Cloud Run, where instances start and stop constantly, that
is expensive and repeated. Add to `application.properties`:
```properties
# Tenant catch-up migration on boot. Safe to run concurrently (Flyway takes a Postgres
# advisory lock per schema) but costly on autoscaled instances - run it on a dedicated
# migration job or a min-instance, not on every cold start.
app.migration.run-tenant-catchup-on-startup=${RUN_TENANT_MIGRATIONS:true}
```
and add to `migration/TenantMigrationStartupRunner.java`:
```java
@ConditionalOnProperty(name = "app.migration.run-tenant-catchup-on-startup",
        havingValue = "true", matchIfMissing = true)
```
Keep the default `true` so local and single-instance deployments are unchanged.

**VERIFY:**
```bash
./mvnw -q -o compile
set -a; source .env; set +a
./mvnw spring-boot:run &
sleep 45 && curl -s localhost:8080/actuator/health   # must return {"status":"UP"} with no auth
```
```bash
git add -A && git commit -m "Add actuator health probe and gate tenant catch-up migrations

Cloud Run needs an unauthenticated health endpoint for startup and liveness
probes. The per-tenant Flyway catch-up is now flag-gated so it does not run
on every cold start of an autoscaled instance.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### T6.3 — Cloud Run deployment artifacts

**Step 1 — Cloud SQL socket factory.** Add to `pom.xml`:
```xml
        <dependency>
            <groupId>com.google.cloud.sql</groupId>
            <artifactId>postgres-socket-factory</artifactId>
            <version>1.19.0</version>
        </dependency>
```
Pin the version explicitly — it is not managed by the Spring Boot BOM (§0).

**Step 2 — production Hikari tuning.** New file
`src/main/resources/application-cloudrun.properties`:
```properties
# Cloud Run scales to many small instances, so each keeps a small pool. max-lifetime is
# kept well under Cloud SQL's idle timeout so connections are recycled before the server
# drops them mid-request.
spring.datasource.hikari.maximum-pool-size=5
spring.datasource.hikari.minimum-idle=0
spring.datasource.hikari.max-lifetime=600000
spring.datasource.hikari.idle-timeout=300000
spring.datasource.hikari.connection-timeout=10000

# Demo seeding must never run in a deployed environment.
app.seed.demo-data=false
logging.level.com.voyra.crm=INFO
```

**Step 3 — harden the Dockerfile.** Replace `Dockerfile` with:
```dockerfile
FROM eclipse-temurin:17-jdk-alpine AS build
WORKDIR /app
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN ./mvnw -B dependency:go-offline
COPY src ./src
RUN ./mvnw -B package -DskipTests

FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
# Never run as root.
RUN addgroup -S app && adduser -S app -G app
COPY --from=build --chown=app:app /app/target/travel-crm-backend.jar app.jar
USER app
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "app.jar"]
```

**Step 4 — deployment runbook.** New file `docs/DEPLOYMENT.md` documenting:

- **Prerequisites:** GCP project, Cloud SQL Postgres 16 instance, Artifact Registry repo,
  Secret Manager entries for `JWT_SECRET`, `PASSWORD_ENCRYPTION_KEY`, `DB_PASSWORD`.
- **Cloud SQL JDBC URL:**
  ```
  jdbc:postgresql:///voyra_crm?cloudSqlInstance=PROJECT:REGION:INSTANCE&socketFactory=com.google.cloud.sql.postgres.SocketFactory
  ```
- **Build and deploy:**
  ```bash
  gcloud builds submit --tag REGION-docker.pkg.dev/PROJECT/voyra/travel-crm-backend

  gcloud run deploy travel-crm-backend \
    --image REGION-docker.pkg.dev/PROJECT/voyra/travel-crm-backend \
    --region REGION \
    --add-cloudsql-instances PROJECT:REGION:INSTANCE \
    --set-env-vars SPRING_PROFILES_ACTIVE=cloudrun \
    --set-env-vars DB_URL='jdbc:postgresql:///voyra_crm?cloudSqlInstance=PROJECT:REGION:INSTANCE&socketFactory=com.google.cloud.sql.postgres.SocketFactory' \
    --set-env-vars DB_USERNAME=voyra \
    --set-env-vars CORS_ALLOWED_ORIGINS=https://app.voyraglobal.com \
    --set-env-vars RUN_TENANT_MIGRATIONS=true \
    --set-secrets JWT_SECRET=voyra-jwt-secret:latest \
    --set-secrets PASSWORD_ENCRYPTION_KEY=voyra-password-key:latest \
    --set-secrets DB_PASSWORD=voyra-db-password:latest \
    --min-instances 1 \
    --cpu-boost \
    --no-allow-unauthenticated
  ```
- **`--min-instances 1`** is deliberate: it keeps one warm instance so tenant catch-up migrations
  and cache loading are not repeated on every cold start.
- **A deployment checklist** covering: secrets rotated off local values, `app.seed.demo-data=false`
  confirmed, CORS set to the real frontend origin, and the local demo `SUPER_ADMIN`
  (`admin@travelos.com`) confirmed absent from the production database.
- **A known-limitations section** recording that `TenantCache`/`AgentCache`/`PlatformAdminCache` are
  per-instance (blueprint §8.10 rule 4 requires this be an explicit decision): a deactivation takes
  effect immediately on the instance that served the write and on others only after restart. With
  `--min-instances 1` and low traffic this is acceptable; document that moving to a shared cache is
  the fix when the service scales out.
- **The auth-lifecycle decision** the original plan never recorded: 24-hour JWT, no refresh token, no
  logout endpoint; revocation is the per-request active-flag cache check in `JwtAuthenticationFilter`.
  State this as an accepted v1 limitation with the upgrade path (short-lived access token plus
  refresh token) noted.

**Step 5 — `.env.example`,** add the deployment-only variables as commented placeholders:
```
# --- Deployment only (Cloud Run) ---
# SPRING_PROFILES_ACTIVE=cloudrun
# RUN_TENANT_MIGRATIONS=true
```

**VERIFY:**
```bash
./mvnw -q -o compile
docker build -t voyra-crm-test .          # must succeed
docker run --rm voyra-crm-test id         # must NOT print uid=0(root)
test -f docs/DEPLOYMENT.md && echo "OK"
```
```bash
git add -A && git commit -m "Add Cloud Run deployment artifacts and runbook

Cloud SQL socket factory, a cloudrun profile with serverless-tuned Hikari
settings, a non-root Dockerfile, and docs/DEPLOYMENT.md covering the deploy
command, a pre-deploy checklist, the per-instance cache limitation (§8.10
rule 4), and the v1 auth-lifecycle decision the original plan never recorded.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

## PHASE 7 — CLOSE OUT

### T7.1 — Make the compliance audit pass

Run the script from T0.2:
```bash
./scripts/blueprint-audit.sh
```
Every check must print `PASS` and the script must exit 0.

If anything still fails, fix the **code**, not the script. The one exception: if a check is
genuinely wrong (a false positive on a legitimate pattern), correct the check and note why in a
comment above it.

Then wire it into the build so compliance cannot silently regress. Add to `pom.xml` inside
`<build><plugins>`:
```xml
            <plugin>
                <groupId>org.codehaus.mojo</groupId>
                <artifactId>exec-maven-plugin</artifactId>
                <version>3.1.0</version>
                <executions>
                    <execution>
                        <id>blueprint-audit</id>
                        <phase>verify</phase>
                        <goals><goal>exec</goal></goals>
                        <configuration>
                            <executable>${project.basedir}/scripts/blueprint-audit.sh</executable>
                        </configuration>
                    </execution>
                </executions>
            </plugin>
```

**VERIFY:**
```bash
./scripts/blueprint-audit.sh && echo "COMPLIANT"
./mvnw -q -o verify
```
```bash
git add -A && git commit -m "Blueprint compliance audit passes; wire it into mvn verify

Every mechanical check in scripts/blueprint-audit.sh now passes, and the
script runs in the verify phase so compliance cannot regress silently.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### T7.2 — Update the project documentation

**File 1 — `PROGRESS.md`.** Update, do not rewrite:
- Correct **"56 endpoints"** to the real figure. Get it from:
  ```bash
  grep -rhoE '@(Get|Post|Put|Patch|Delete)Mapping' src/main/java/com/voyra/crm/controller/ | wc -l
  ```
  (It was 68 before this plan; pagination adds no new mappings, so re-count and use the actual
  number.)
- Replace the "How this was verified" section: it currently describes manual curl runs. Rewrite it
  around the test suite, stating the count and what each group proves, and note that
  `./mvnw verify` re-verifies every claim.
- Add a "Blueprint compliance" section pointing at `scripts/blueprint-audit.sh`.
- Update "Next steps": drop everything this plan completed; keep the GitHub remote, frontend
  integration, and GCS migration items.
- Update the codebase-size figures.

**File 2 — `docs/BACKEND_PLAN.md`.** Do **not** edit the plan body — it is the historical record of
what was approved. Append a dated addendum:

```markdown
---

## Addendum — Remediation (2026-08-13)

This plan's "Blueprint-compliance checklist" was written with `[x]` boxes before the code
existed. They recorded design intent, not verification, and one item ("Reports/aggregates:
... a single GROUP BY query per report") was not true of the delivered code.

Compliance is now machine-verified by `scripts/blueprint-audit.sh`, which runs in `mvn verify`.
Treat the checklist above as historical intent; treat the script's output as the current truth.

Gaps this plan did not cover, now addressed in `docs/REMEDIATION_PLAN.md`:

- **Testing strategy.** The original "Verification" section specified manual Swagger/curl passes
  only, reproducing the one deviation blueprint §10 explicitly names. Now a ~60-test suite.
- **Pagination.** Every list endpoint was unbounded. Now opt-in `?page=&size=`, non-breaking.
- **Deployment.** Only a Dockerfile was mentioned. See `docs/DEPLOYMENT.md`.
- **Seed-data safety.** The seed runner was unguarded and logged credentials. Now flag-gated.
- **Auth lifecycle.** 24h JWT with no refresh or logout was an unstated omission; it is now a
  recorded decision in `docs/DEPLOYMENT.md`.
```

**VERIFY:**
```bash
grep -c '56 endpoints' PROGRESS.md   # must be 0
grep -c 'Addendum' docs/BACKEND_PLAN.md   # must be 1
```
```bash
git add -A && git commit -m "Update PROGRESS.md and add a remediation addendum to BACKEND_PLAN.md

Corrects the endpoint count, replaces the manual-verification section with
the test suite, and records why the plan's pre-checked compliance checklist
was replaced by a machine-verified audit.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### T7.3 — Final full verification

Run everything, from a clean state:
```bash
cd "/Users/shivanshsrivastava/Projects/Voyra global/travel-crm-backend"
export JAVA_HOME="/opt/homebrew/opt/openjdk@17"; export PATH="$JAVA_HOME/bin:$PATH"

./mvnw -q clean verify          # compile + all tests + compliance audit
./scripts/blueprint-audit.sh    # must print RESULT: COMPLIANT
git status --porcelain          # must be empty
git log --oneline | wc -l       # ~25 commits
```

Then boot and smoke-test the three logins and the public proposal path end to end:
```bash
set -a; source .env; set +a
./mvnw spring-boot:run
```
Confirm at `http://localhost:8080/swagger-ui/index.html` that every endpoint shows descriptions and
examples.

**Report to the user:** total commits, test count, audit result, and anything that had to deviate
from this plan with the reason.

---

## APPENDIX A — Task index

| ID | Task | Blueprint § | Est. |
|---|---|---|---|
| T0.1 | Baseline git commit | — | 15m |
| T0.2 | Compliance audit script | §all | 1h |
| T1.1 | Gate demo seeder, stop logging credentials | §6.4, §8.9, §10 | 30m |
| T1.2 | Supplier invoices owner-only; split summary | §5.3 | 1h |
| T2.1 | Enforce proposal-link expiry | — | 45m |
| T2.2 | Collision-checked IDs on 8 paths | §8.5 | 1.5h |
| T2.3 | `@JsonIgnore` on password fields | §8.4 | 10m |
| T2.4 | `created_date` on two tables (tenant V2) | §8.4, §2.5 | 45m |
| T2.5 | Fix `readOnly` on approve; delete dead code | §8.6, §10 | 20m |
| T3.1 | Derived queries replace in-memory filtering | §2.5, §8.1 | 1.5h |
| T3.2 | Eliminate agent-stats N+1 | §8.1 | 2.5h |
| T3.3 | Four reports → GROUP BY | §8.1 | 2h |
| T4.1 | Validation on five patch DTOs | §7.4, §8.7 | 1.5h |
| T4.2 | `@Schema` on all DTO fields (6 batches) | §8.13 | 5h |
| T4.3 | Opt-in pagination on six list endpoints | — | 3h |
| T5.1 | Test infrastructure | §10 | 1h |
| T5.2 | Unit tests (26) | §10 | 2.5h |
| T5.3 | Service tests (18) | §10 | 3.5h |
| T5.4 | Controller slice tests (14) | §10 | 3h |
| T5.5 | Isolation integration tests (5) | §3.5, §10 | 2.5h |
| T6.1 | Config hardening | §8.12 | 45m |
| T6.2 | Health probe; gate tenant catch-up | §4.6 | 45m |
| T6.3 | Cloud Run artifacts and runbook | §0, §8.10 | 2h |
| T7.1 | Compliance audit passes; wire into verify | §all | 1h |
| T7.2 | Update PROGRESS.md and plan addendum | — | 45m |
| T7.3 | Final full verification | — | 30m |

**Total: ~40 hours.** Phases 0–2 (blockers and correctness) are ~6 hours and unblock deployment
readiness; Phase 4.2 and Phase 5 are the bulk of the remainder.

---

## APPENDIX B — What "100% blueprint compliance" means here

Three deliberate deviations from the blueprint remain after this plan, each with a reason. They are
documented rather than "fixed", and `scripts/blueprint-audit.sh` accounts for them.

| Deviation | Blueprint | Why it stands |
|---|---|---|
| **Class-level `@PreAuthorize`** instead of one per method | §5.3 | Functionally equivalent, and Spring applies it to every method. Per-method overrides are used where the rule differs (`LeadController.assignAgent`, the three supplier-invoice endpoints). The audit script enforces that every controller class carries one. Per-method duplication would be noise. |
| **REST-style URLs** (`POST /api/customers`) rather than `/save`, `/list` | §8.14 | The blueprint's suffix convention predates this codebase; plain REST is the better contract and is applied consistently across all 13 controllers. Changing it now would break the frontend integration for no gain. |
| **`/api/public/**` is a second `permitAll` surface** beyond `/api/auth/**` | §4.6, §5.3 | Required by the product: the customer-facing proposal page is unauthenticated by design. It is narrow (two endpoints), returns a hand-built DTO that cannot carry internal fields, and is covered by dedicated tests. |

Everything else in the blueprint is enforced mechanically by the audit script.
