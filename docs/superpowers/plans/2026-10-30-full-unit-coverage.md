# Full unit-test coverage for both repos

Date: 2026-10-30. Branch: `nitin-dev` in both repos. Nothing is pushed until the owner says so.

## Where we start

Backend (JaCoCo, unit and slice tests, measured 2026-10-30): 76.8 % of lines (15,785 of 20,556),
63.9 % of branches (6,547 of 10,243), 570 classes, 227 test files. 4,771 lines are missed, 3,196 of
them in `service`. The biggest gaps: ServiceInstanceService 250, BookingService 230,
InvoiceDocumentService 225, LeadService 166, PaymentReceiptService 133, SupplierLedgerService 130,
SupplierInvoiceService 125, PaymentAdvicePdfRenderer 103, BookingSpecifications 99,
InvoicePdfRenderer 92. Controllers are at 38 %, schedulers at 26 %.

Frontend: no unit tests and no unit-test tooling. About 53,000 lines in `app` (17,200), `components`
(24,500), `lib` (11,500) and `hooks` (100). The 41 Playwright specs test journeys, not lines.

## What counts

- Backend: JaCoCo line and branch counters over `com.voyra.crm`. Excluded from measurement only:
  the Spring Boot main class, the `migration` package (the Flyway runner needs a real database and
  is exercised by the Testcontainers ITs) and Lombok-generated code (`lombok.addLombokGeneratedAnnotation
  = true`, which JaCoCo filters on its own). Everything else counts, including entities' hand-written
  methods, `repository.spec`, config, schedulers and exception handlers.
- Frontend: Vitest with V8 coverage over `app`, `components`, `lib` and `hooks`. Excluded: type-only
  files, `*.d.ts`, `e2e/`, config files. Every page and component counts.
- The bar (owner's choice): either literal 100 % of lines, branches and functions in both repos, or
  100 % of lines and functions in both repos with branches at 100 % in the backend and at least 90 %
  in the frontend (every JSX `&&` and `?:` is a branch). Once reached, the bar is enforced by the
  build: `mvn verify` fails below it (jacoco `check`) and `npm run test:coverage` fails below it
  (Vitest thresholds).

## Rules every agent follows

- Tests only. No production code changes. A line or branch that cannot be reached is listed in the
  agent's report with the reason, and the lead decides: delete it as dead code, or keep it and
  reach it another way. Money, wallet, tenant-isolation and security checks are never deleted.
- Each test asserts behaviour (a value, a call, a thrown error, rendered text). A test that only
  executes lines without asserting is not accepted.
- Backend style matches the existing suite: JUnit 5, Mockito (`@ExtendWith(MockitoExtension.class)`),
  AssertJ; controller tests as `@WebMvcTest` slices the way the existing controller tests are built.
  No Testcontainers, no network, no database, no Tripjack key, no `.env`.
- Frontend: Vitest, React Testing Library and user-event, `jsdom`. API services are mocked at the
  `lib/api/services/*` module boundary; `next/navigation` through the shared helper. Tests sit next
  to the code as `*.test.ts(x)`.
- Each agent works in its own git worktree on its own branch, runs only its own tests (never the full
  suite, to keep the laptop usable), commits on its branch and does not push.
- Agents never touch the Voyra Global, Global Explorer or Shivansh Test tenants, or any database.

## Phase 0: tooling (lead, before agents start)

Backend:
1. `pom.xml`: jacoco-maven-plugin with `prepare-agent`, `report`, and `check` bound to `verify`.
   The check starts at today's numbers so the build stays green during the work and is raised to
   the bar at the end.
2. `lombok.config`: `lombok.addLombokGeneratedAnnotation = true`.
3. `.claude/run/cov-be.sh <test pattern>`: runs the given tests with JaCoCo and prints missed lines
   and branches per class, so an agent sees its own gaps.

Frontend:
1. Dev dependencies: vitest, @vitest/coverage-v8, @vitejs/plugin-react, jsdom,
   @testing-library/react, @testing-library/user-event, @testing-library/jest-dom.
2. `vitest.config.ts` (jsdom, the `@/` alias, setup file, `e2e/` excluded, coverage include and
   exclude lists, thresholds off until the end); `test/setup.ts`; `test/render.tsx` (render with the
   app's providers and a mocked router).
3. Scripts `test` and `test:coverage`.
4. Three reference tests that agents copy: one pure `lib` module, one component, one page.

Then commit Phase 0 on `nitin-dev` (locally) and create one worktree per agent under
`Voyra global/.wt/` on branches `cov/be-<n>` and `cov/fe-<n>`. Frontend worktrees link the main
checkout's `node_modules`.

## Phase 1: parallel agents

Backend, 5 agents, about 950 missed lines each:
- BE-1, BE-2, BE-3: the `service` package, its classes split into three lists balanced by missed
  lines (computed from `jacoco.csv` at launch).
- BE-4: `service.booking`, `service.tripjack`, `service.payments`, `util` (PDF renderers included).
- BE-5: `controller`, `config`, `scheduler`, `exception`, `cache`, `security`, `enums`, `entity`,
  `repository.spec`.

Frontend, 8 agents, about 6,500 lines each, split by folder and balanced by line count at launch:
two for `lib` and `hooks`, three for `components`, three for `app`.

Each agent returns: coverage of its slice before and after, test files added, unreachable lines
with reasons, and anything that looked like a real bug (reported, not fixed).

## Phase 2: integrate and close the gaps (lead)

1. Merge the branches into `nitin-dev` one by one (new test files mostly, so conflicts should be
   rare).
2. Backend `./mvnw verify` and the full Vitest run with coverage; list what is still missed.
3. Decide the unreachable-code list. Dead code is deleted in its own commit, and any production
   change gets the targeted Playwright specs for the screens it touches.
4. A second, smaller round of agents for whatever is still missed.
5. Raise the jacoco `check` and the Vitest thresholds to the bar.

## Phase 3: lock it in

- Both builds fail below the bar.
- `CLAUDE.md` (root and `travel-crm`) gets the new test commands and the rule that new code ships
  with tests.
- Report the final numbers. Push only when the owner says.

## Cost and risk

- 13 agents in the first round plus a smaller second round. Expect several hours of wall time, the
  frontend being most of it, and a large token spend.
- Running 5 Maven and 8 Vitest processes at once is heavy on a 12-core, 24 GB laptop; agents run
  only their own tests to keep this manageable. Local servers keep running.
- 100 % coverage means every future change, however small, must ship with tests or the build fails.
- Tests written to reach every branch of UI code mock heavily and tend to break when markup
  changes; they prove lines ran, not that the screen works. Playwright stays the guard for
  journeys.
- Bugs the agents find are reported, not fixed in this work.
