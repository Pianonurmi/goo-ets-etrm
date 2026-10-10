# goo-ets-etrm

Enterprise ETRM for Guarantees of Origin (GoO) and EU ETS allowances: trading, certificate inventory and
allocation, registry instructions, positions and a double-entry sub-ledger, for a European company group with several
legal entities.

- Kotlin · Spring Boot 4 · Spring Modulith · PostgreSQL · jOOQ · Flyway · Gradle
- Modular monolith: one deployable, strict module boundaries verified in the build
- Documentation: [`docs/`](docs/README.md) (arc42, ADRs, domain notes, roadmap)

## Modules

| Module | What it does |
|---|---|
| `referencedata`, `taxonomy` | Legal entities and accounting policies, books, counterparties, registries and accounts, production devices; EECS-style code hierarchies and region sets |
| `product` | Product catalogue and the GoO **criteria language** (stored on trades, compiled to SQL, evaluated in memory) |
| `trade` | Deal capture, versions, four-eyes approval, confirmation, deliveries, intercompany mirroring |
| `inventory` | Certificate **lots as ranges** with hash-chained journal and bitemporal history; allowance holdings; registry reconciliation |
| `registry` | Transfer routes, instruction state machine, simulator / manual / file gateways, CSV statements |
| `allocation` | Matching engine: lot classes, scoring, greedy solver with share constraints, **max-flow coverage**, plans with soft and hard reservations |
| `position` | GoO coverage position by bucket and vintage, expiry ladder, EU ETS compliance ladder, snapshots |
| `ledger` | Posting rules as data, immutable journal, FIFO / weighted-average costing, periods, FX, GL export |
| `workflow`, `audit` | Approval inbox with segregation of duties; append-only audit trail |

## Build and run

Prerequisites: JDK 21, Docker (for PostgreSQL and Keycloak).

```bash
# 1. Infrastructure: PostgreSQL 17 (databases etrm, etrm_codegen, etrm_test) and Keycloak
docker compose -f ops/docker-compose.yml up -d postgres keycloak

# 2. Build: lint, jOOQ code generation against etrm_codegen, unit and integration tests, coverage gate
export ETRM_CODEGEN_DB_URL=jdbc:postgresql://localhost:5432/etrm_codegen
export ETRM_TEST_DB_URL=jdbc:postgresql://localhost:5432/etrm_test
export ETRM_DB_USER=etrm ETRM_DB_PASSWORD=etrm
./gradlew build

# 3. Run the API (dev profile: no token needed, pass X-Actor / X-Roles headers)
SPRING_PROFILES_ACTIVE=dev ./gradlew :apps:api:bootRun
open http://localhost:8080/swagger-ui
```

Without `ETRM_TEST_DB_URL`, integration tests start a Testcontainers PostgreSQL. The jOOQ code generation always
needs a reachable database (`ETRM_CODEGEN_DB_URL`).

Full-size allocation benchmark:

```bash
./gradlew :modules:allocation:test --tests '*AllocationBenchmarkTest*' -Detrm.benchmark.lots=100000 -Detrm.benchmark.obligations=5000
```

## Golden path (see `apps/api/src/test/kotlin/com/pianonurmi/etrm/GoldenPathIT.kt`)

1. Create a legal entity, book, counterparty, registry accounts and a production device.
2. Receive certificates (`POST /api/v1/inventory/lots`) – the ledger posts the issuance.
3. Capture a GoO sale with criteria (`POST /api/v1/trades`), approve it as a second user through the workflow inbox, confirm it – an obligation opens.
4. Check coverage (`GET /api/v1/allocation/coverage`), propose a plan, approve it as a second user – lots are reserved, the registry transfer executes, the trade is delivered, revenue and cost of goods sold are posted.
5. Inspect positions (`/api/v1/positions/goo`, `/api/v1/positions/ets`), the ledger (`/api/v1/ledger/entries`, `trial-balance`, `journal/verify`) and the audit trail (`/api/v1/audit/events`).

## Repository layout

```
build-logic/     Gradle convention plugins (Kotlin, module with jOOQ codegen, application)
platform/        shared kernel (value types, events, errors, security context, test fixtures)
modules/<name>/  bounded contexts: api / domain / application / adapter, own schema and migrations
apps/api/        Spring Boot application: migrations runner, security, problem details, end-to-end tests
docs/            arc42, ADRs, domain and integration notes, roadmap, glossary
ops/             docker-compose, Dockerfile, Keycloak realm
config/detekt/   static analysis configuration
```
