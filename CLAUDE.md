# goo-ets-etrm

ETRM for Guarantees of Origin (GoO) and EU ETS allowances. Kotlin, Spring Boot 4, Spring Modulith, PostgreSQL,
jOOQ, Flyway, Gradle. Read `docs/architecture-plan.md` for where the system is going and `docs/roadmap.md` for the
increment status.

## Build and test

The build needs PostgreSQL even before tests: jOOQ sources are generated from a Flyway-migrated database.

```bash
docker compose -f ops/docker-compose.yml up -d postgres   # creates etrm, etrm_codegen, etrm_test, etrm_e2e (user etrm/etrm)
export ETRM_CODEGEN_DB_URL=jdbc:postgresql://localhost:5432/etrm_codegen
export ETRM_TEST_DB_URL=jdbc:postgresql://localhost:5432/etrm_test
export ETRM_DB_USER=etrm ETRM_DB_PASSWORD=etrm

./gradlew build                                   # lint, codegen, all tests, Kover rule
./gradlew ktlintFormat                            # fix formatting before committing
./gradlew :modules:allocation:test --tests '*AllocationEngineTest*'
./gradlew :apps:api:test --tests '*GoldenPathIT*' # end-to-end REST scenario
./gradlew :apps:api:test --tests '*MonthEndIT*'   # increment 3: market data to hard close
SPRING_PROFILES_ACTIVE=dev,demo ./gradlew :apps:api:bootRun   # Swagger UI at /swagger-ui, web UI at /, demo data
./gradlew build -Petrm.skipWeb                    # backend only, without the web UI
./gradlew :apps:web:check                         # web: lint, format, typecheck, Vitest
cd apps/web && npm run dev                        # Vite on :5173, proxies /api to :8080
cd apps/web && npm run e2e                        # Playwright against apps/api/build/libs/api.jar (database etrm_e2e)
```

The build needs Node.js 22 and npm on the PATH for `:apps:web`.

- Without `ETRM_TEST_DB_URL`, integration tests start a Testcontainers PostgreSQL instead.
- `TestDatabase.dsl` joins a Spring transaction when a test runs one (e.g. with `DataSourceTransactionManager`), and is autocommit otherwise.
- Full-context tests each cache a context with a small connection pool (`application-test.yml`); keep the pool small so they fit PostgreSQL's connection limit.
- All test tasks share one test database and reset schemas. A Gradle build service (`SharedTestDatabase` in `build-logic`) runs them one at a time; keep it that way.
- Allocation benchmark at full size: `-Detrm.benchmark.lots=100000 -Detrm.benchmark.obligations=5000`.
- Load tests: `k6 run -e BASE_URL=http://localhost:8080 -e PROFILE=smoke ops/load/etrm.js` against the jar with `dev,demo` (`docs/operations/load-testing.md`); a change that moves the baseline figures notably updates that page.

## Layout and conventions

- `platform/` is the shared kernel (ids, money, quantity, date ranges, events, errors, actor, JSON). It is an open Spring Modulith module.
- Each `modules/<name>/` is a bounded context under `com.pianonurmi.etrm.<name>` with `api/`, `domain/`, `application/`, `adapter/rest`, `adapter/jooq`. Package names `in`/`out` are rejected by ktlint.
- `<Name>Module.kt` declares `@ApplicationModule(allowedDependencies = [...])`. Other modules may only use packages marked `@NamedInterface` (`api`, and `product :: criteria`). `apps/api` `ModularityTests` fails the build otherwise. Add the Gradle dependency and the allowed dependency together.
- Each module owns schema `etrm_<name>`, migrations in `src/main/resources/db/migration/<name>/`, no cross-schema foreign keys. A new module must be added to `ModularMigrations.DEFAULT_ORDER` in `apps/api` and to `settings.gradle.kts`.
- Selected events go to Kafka after commit through the Modulith publication registry (ADR 0029, `ExternalEvents` in `apps/api`); off unless `ETRM_KAFKA_ENABLED`. Their JSON is a public contract; `KafkaExternalizationIT` needs Docker.
- Cross-module side effects go through `DomainEvent`s published in the command transaction. Listeners must be idempotent on the event id. Use the `eventType` extension property, not a member.
- Jackson 3 (`tools.jackson.*`). Enums travel by constant name (`Json.configure`); do not override `toString()` on enums used in JSON.
- No floating point in the domain. Periods are closed-open `LocalDateRange`. Instants are UTC. Ids are UUIDv7 (`Ids`).
- Append-only tables (lot events, journal, audit) are protected by triggers and hash-chained. Correct by reversal, never by update.
- `audit_event` and `lot_event` are partitioned by month (ADR 0030): unique keys include the partition key, code uses the parent table only, and `PartitionMaintenance` creates partitions ahead. A new partitioned table follows the `<table>_pYYYYMM` / `<table>_pdefault` naming and is picked up automatically.
- A caller that catches a `DomainException` from another `@Transactional` service must run that call in `Savepoints.attempt { }` (platform `tx`); otherwise the whole transaction is already marked rollback-only. Hand-wired tests pass `Savepoints.NONE`, so cover such paths with a Spring-context test (see `AllocationStaleIT`).
- Deliveries are idempotent: pass `RecordDelivery.idempotencyKey` (e.g. `instruction:<id>`); `DeliveryRecorded.deliveryId` is the key downstream modules use.
- CSV in and out goes through `platform.text.Csv` (RFC 4180). Exports people open in a spreadsheet use `Csv.writeForSpreadsheet` (formula injection); machine files use `Csv.write`. PDFs and other issued documents go through the `document` module; templates live in `src/main/resources/documents/templates/`.
- Java 25 (toolchain in `build-logic`; Gradle runs on 21+). Virtual threads are on: requests and jobs run on them. Do not hold a lock across blocking I/O. API tests run with two carrier threads, so pinning shows up as a hang (`ConcurrencyIT`). Playwright starts the jar with `ETRM_JAVA` (a Java 25 binary) when `java` on the PATH is older.
- Observability (`docs/operations/observability.md`): Prometheus metrics, OpenTelemetry traces (OTLP when configured), `X-Trace-Id` on every response, ECS JSON logs in the `json` profile; the `kubernetes` profile moves the actuator to its own port, open only there.
- Kubernetes (`docs/operations/kubernetes.md`): Helm chart `ops/helm/etrm`, PostgreSQL from `ops/k8s/postgres-cluster.yaml` (CloudNativePG). A new environment variable or port goes into the chart too; CI lints it and validates the rendered manifests with kubeconform.
- Scheduled jobs use `@SchedulerLock`; the lock provider is configured in `apps/api` (`SchedulingConfiguration`).
- Four-eyes rules live in services and in database checks; tests use the `X-Actor` / `X-Roles` headers (and optionally `X-Entities`, default all entities) in the `dev` and `test` profiles.
- The `dev` profile has no authentication: it binds 127.0.0.1 (`application-dev.yml`) and `ProfileGuard` refuses it on another address (unless `etrm.dev.allow-remote`, local container only) or together with `showcase`/`kubernetes`. Persona headers are honoured only in `dev` and `test`. Use `127.0.0.1` rather than `localhost` in local URLs.
- Personas come from one catalogue, `web/Personas.kt`, served in `/api/v1/ui-config`; the web persona menu and the demo data (`DemoUser`) both use it.
- The `showcase` profile (ADR 0032) is the public demo: demo accounts from `ETRM_DEMO_PASSWORD`/`ETRM_DEMO_USERS`, a session cookie with CSRF (`XSRF-TOKEN` → `X-XSRF-TOKEN`), then `X-Persona` against the catalogue. The demo seeder authenticates with the in-memory `SeedToken` from loopback. Playwright's `showcase` project runs it on database `etrm_showcase_e2e`.
- Approvals go through the workflow inbox: the owning module registers a `TaskHandler` and applies the decision in the same transaction.
- Listeners triggered by deliveries or receipts must not fail the transaction that published the event: park the problem (`NEEDS_ATTENTION`, unmatched) or call through `Savepoints.attempt`.
- Registry calls never run inside a database transaction (outbox, ADR 0012). Registry API mappings stay `verified = false` until checked against the registry's own specification.
- Approval and work tasks: the owning module implements `workflow.api.TaskHandler` (definition plus `onDecision`), opens tasks from its own events and closes them with `closeFor`. Workflow depends on no other module (ADR 0011).
- Legal-entity scope: use `Actor.canAccess` / `requireEntity` in queries and commands that touch one entity's data.
- Registry calls never run inside a database transaction: gateways declare `inline` (no external I/O) or go through the outbox dispatcher (ADR 0012). A timeout is `GatewayResult.Unknown`, never a resend.
- FX conversion goes through `marketdata.api.FxRates` (direct, inverse or cross via EUR, with a staleness limit); prices are read from market data, never typed into another module.
- Sensitive master data (registry integration modes; later counterparty registry and bank accounts) changes through `ChangeRequestCommand` and a second person's approval in the inbox.
- Things that need a second person: curve snapshots, valuation runs, verified emissions, risk limits and credit support, accounting policies and posting rule changes. Each goes through its own `TaskHandler`; the service also refuses requester = approver.
- A module that must influence another without depending on it offers an SPI in the owner's `api`, for example `trade.api.TradeApprovalCheck`, implemented by risk. The owner resolves it lazily (`ObjectProvider`) so no cycle forms.
- Market data: rates go through `FxRates` and prices through `CurveQuery` / `GooMarks` / `IndexFixings`. Nothing else stores prices.
- Period end (`PeriodEndService`) and similar measurements post the difference between the required balance and what is booked, so a re-run is idempotent. Use the adjustment path (`LedgerService.postAdjustment`).
- Reports are entries in `ReportCatalogue`. Every run is logged with its content hash. Add a report there, with its roles.
- The OpenAPI document is a contract (ADR 0027): after changing a controller or a view, run `./gradlew :apps:api:updateOpenApiSnapshot` and commit `apps/api/openapi/etrm-api.json`. Operation ids are `<controller>_<method>`, so keep handler names unique per controller and stable.
- Allocation commands check roles and entity scope in the service: propose and what-if for TRADER, MIDDLE_OFFICE or BACK_OFFICE; approve and reject for MIDDLE_OFFICE or BACK_OFFICE (never the proposer); cancel for all three.
- Manual trade operations over REST (recording deliveries, settling, issuing our confirmation) need MIDDLE_OFFICE or BACK_OFFICE (`TradeCommand.requireTradeOperator`).
- Manual lot operations over REST (receive, split, reserve, release, cancel, withdraw, correct) need BACK_OFFICE or REGISTRY_OPERATOR on the holder (`LotCommand.requireLotOperator`); module flows call the commands directly.
- Operations guards sit in the REST layer because module flows call the services as SYSTEM: `SettlementAccess` (reads for BACK_OFFICE, ACCOUNTANT, MIDDLE_OFFICE, RISK, AUDITOR; commands check the entity), `RegistryAccess` (instructions: submit also MIDDLE_OFFICE, operate REGISTRY_OPERATOR or BACK_OFFICE, on the holder; routes MIDDLE_OFFICE or BACK_OFFICE; a registry-wide transaction import needs all entities) and inventory `ReconciliationAccess` (statements and breaks by the account's holder). Counterparty confirmations are received by MIDDLE_OFFICE or BACK_OFFICE. Issued documents (`/api/v1/documents`) are read only within the document's legal entity.
- CSV upload endpoints declare `consumes = ["text/csv", "text/plain"]` so the OpenAPI document types the body as text.
- Master data writes check roles in the controller: taxonomy and product buckets need MIDDLE_OFFICE; party addresses and VAT need MIDDLE_OFFICE, BACK_OFFICE or ACCOUNTANT (and entity scope for a legal entity); the audit trail is read by AUDITOR, ACCOUNTANT, MIDDLE_OFFICE, BACK_OFFICE and RISK.
- Read endpoints respect entity scope: unfiltered lists use `Actor.entityFilter` (see `TradeQuery.visibleTrades`, `LotQuery.visibleLots`), single records call `requireEntity`, and `EntityScopeInterceptor` refuses a `legalEntityId`/`holderEntityId` parameter outside the user's scope.
- External layouts not checked against their specification are marked `verified = false`: EMIR field rules, the SAP GL layout, PFE add-ons and registry API mappings.

## Web UI (`apps/web`, see `docs/ui/README.md`)

- React, TypeScript (strict), Vite, Mantine, AG Grid Community, TanStack Query, React Router. The jar serves the bundle (ADR 0025).
- API types are generated from `apps/api/openapi/etrm-api.json` (`npm run gen:api`, with read/write markers); never hand-write request or response types. Use `Schema<'X'>` for responses and `Body<'X'>` for request bodies. Update the snapshot first when the API changes.
- Calls go through `$api` / `api` in `shared/api/client.ts`; failures are `ProblemError`. Map 422 violations onto forms with `applyProblemToForm`.
- No money arithmetic in the browser: format with `shared/format`, send decimals back with `decimal()` (strings).
- Offer actions through `<Can op=...>` / `useCan` keyed by operation id (`shared/auth/permissions.ts`); the API still checks.
- All visible text goes through `src/locales/en.json`. Personas (served by the API, fallback in `shared/auth/personas.ts`) have two users per approving role for four-eyes paths.
- Every API operation has a UI caller or an entry in `apps/web/api-only.json` (`apiOnly` with a reason, or `planned` with the milestone). `npm run coverage:api` runs in `check`; a new endpoint needs a screen or a reason.
- `npm run format` (Prettier) and `npm run lint` before committing web changes.
- Colours come from the theme (`c="red"`, `color="teal"`): `app/theme.ts` keeps them at WCAG AA contrast and `e2e/hardening.spec.ts` runs axe and a CSP check. Keep new screens lazy; `npm run budget` guards the bundle size.
- Demo data (`demo` profile, `apps/api/.../demo`) goes through the REST API as the dev personas; extend it when a screen needs data to show, as a named `DemoStep` in `DemoScenario.steps()` (values for later steps go through `DemoContext.put`/`get`; the run log in `etrm_demo` makes a failed run resumable). Its test runs on a sibling database (`SiblingDatabase`) because its market data would clash with `MonthEndIT`.

## Lint

Kotlin warnings are errors. detekt config is `config/detekt/detekt.yml` (max line length 160). ktlint follows `.editorconfig`. Run `./gradlew ktlintFormat detekt` before pushing.

## Commits

Small, descriptive commit messages in the imperative. CI (`.github/workflows/ci.yml`) runs the full build with a PostgreSQL 17 service, publishes SBOMs, then builds the image and scans it: a critical vulnerability with a fix fails the build (accepted ones go in `.trivyignore` with a reason). See `docs/operations/supply-chain.md`.
