# goo-ets-etrm

ETRM for Guarantees of Origin (GoO) and EU ETS allowances. Kotlin, Spring Boot 4, Spring Modulith, PostgreSQL,
jOOQ, Flyway, Gradle. Read `docs/architecture-plan.md` for where the system is going and `docs/roadmap.md` for the
increment status.

## Build and test

The build needs PostgreSQL even before tests: jOOQ sources are generated from a Flyway-migrated database.

```bash
docker compose -f ops/docker-compose.yml up -d postgres   # creates etrm, etrm_codegen, etrm_test (user etrm/etrm)
export ETRM_CODEGEN_DB_URL=jdbc:postgresql://localhost:5432/etrm_codegen
export ETRM_TEST_DB_URL=jdbc:postgresql://localhost:5432/etrm_test
export ETRM_DB_USER=etrm ETRM_DB_PASSWORD=etrm

./gradlew build                                   # lint, codegen, all tests, Kover rule
./gradlew ktlintFormat                            # fix formatting before committing
./gradlew :modules:allocation:test --tests '*AllocationEngineTest*'
./gradlew :apps:api:test --tests '*GoldenPathIT*' # end-to-end REST scenario
SPRING_PROFILES_ACTIVE=dev ./gradlew :apps:api:bootRun   # Swagger UI at /swagger-ui
```

- Without `ETRM_TEST_DB_URL`, integration tests start a Testcontainers PostgreSQL instead.
- All test tasks share one test database and reset schemas. A Gradle build service (`SharedTestDatabase` in `build-logic`) runs them one at a time; keep it that way.
- Allocation benchmark at full size: `-Detrm.benchmark.lots=100000 -Detrm.benchmark.obligations=5000`.

## Layout and conventions

- `platform/` is the shared kernel (ids, money, quantity, date ranges, events, errors, actor, JSON). It is an open Spring Modulith module.
- Each `modules/<name>/` is a bounded context under `com.pianonurmi.etrm.<name>` with `api/`, `domain/`, `application/`, `adapter/rest`, `adapter/jooq`. Package names `in`/`out` are rejected by ktlint.
- `<Name>Module.kt` declares `@ApplicationModule(allowedDependencies = [...])`. Other modules may only use packages marked `@NamedInterface` (`api`, and `product :: criteria`). `apps/api` `ModularityTests` fails the build otherwise. Add the Gradle dependency and the allowed dependency together.
- Each module owns schema `etrm_<name>`, migrations in `src/main/resources/db/migration/<name>/`, no cross-schema foreign keys. A new module must be added to `ModularMigrations.DEFAULT_ORDER` in `apps/api` and to `settings.gradle.kts`.
- Cross-module side effects go through `DomainEvent`s published in the command transaction. Listeners must be idempotent on the event id. Use the `eventType` extension property, not a member.
- Jackson 3 (`tools.jackson.*`). Enums travel by constant name (`Json.configure`); do not override `toString()` on enums used in JSON.
- No floating point in the domain. Periods are closed-open `LocalDateRange`. Instants are UTC. Ids are UUIDv7 (`Ids`).
- Append-only tables (lot events, journal, audit) are protected by triggers and hash-chained. Correct by reversal, never by update.
- A caller that catches a `DomainException` from another `@Transactional` service must run that call in `Savepoints.attempt { }` (platform `tx`); otherwise the whole transaction is already marked rollback-only. Hand-wired tests pass `Savepoints.NONE`, so cover such paths with a Spring-context test (see `AllocationStaleIT`).
- Deliveries are idempotent: pass `RecordDelivery.idempotencyKey` (e.g. `instruction:<id>`); `DeliveryRecorded.deliveryId` is the key downstream modules use.
- CSV in and out goes through `platform.text.Csv` (RFC 4180). PDFs and other issued documents go through the `document` module; templates live in `src/main/resources/documents/templates/`.
- Scheduled jobs use `@SchedulerLock`; the lock provider is configured in `apps/api` (`SchedulingConfiguration`).
- Four-eyes rules live in services and in database checks; tests use the `X-Actor` / `X-Roles` headers in the `dev` and `test` profiles.

## Lint

Kotlin warnings are errors. detekt config is `config/detekt/detekt.yml` (max line length 160). ktlint follows `.editorconfig`. Run `./gradlew ktlintFormat detekt` before pushing.

## Commits

Small, descriptive commit messages in the imperative. CI (`.github/workflows/ci.yml`) runs the full build with a PostgreSQL 17 service, then builds the image and scans it.
