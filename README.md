# goo-ets-etrm

Enterprise ETRM for Guarantees of Origin (GoO) and EU ETS allowances: trading and confirmations, certificate
inventory and allocation, registry instructions and reconciliation, settlement (invoices, VAT, payments, netting),
positions and a double-entry sub-ledger, for a European company group with several legal entities.

- Kotlin · Spring Boot 4 · Spring Modulith · PostgreSQL · jOOQ · Flyway · Gradle
- Modular monolith: one deployable, strict module boundaries verified in the build
- Documentation: [`docs/`](docs/README.md) (arc42, ADRs, domain notes, roadmap)

## Modules

| Module | What it does |
|---|---|
| `referencedata`, `taxonomy` | Legal entities and accounting policies, books, counterparties, registries and accounts, production devices; EECS-style code hierarchies and region sets |
| `product` | Product catalogue and the GoO **criteria language** (stored on trades, compiled to SQL, evaluated in memory) |
| `trade` | Deal capture, versions, four-eyes approval, **confirmation matching** (our JSON/PDF confirmation, theirs matched field by field), deliveries, intercompany mirroring |
| `inventory` | Certificate **lots as ranges** with hash-chained journal and bitemporal history; allowance holdings; registry reconciliation with a break lifecycle |
| `registry` | Transfer routes, instruction **outbox** with simulator / manual (Union Registry) / file / API gateways, statement formats as data, break resolution |
| `settlement` | Settlement items per delivery, **VAT rules as data**, gap-free invoices with PDF, payments, credit notes, netting, AR/AP and VAT reports |
| `marketdata` | Instruments with vendor aliases, prices and FX rates (bitemporal, plausibility checks), import formats as data, price index fixings, ECB reference rates |
| `valuation` | MtM of open trades per delivery schedule against official curves, end-of-day runs signed off by risk, P&L explain |
| `risk` | Limits under four eyes, credit exposure and credit support, utilisation and breaches, pre-trade checks on trade approval |
| `etscompliance` | EU ETS installations and aircraft operators, emissions (verified with four eyes), free allocation, surrender attribution and requests, deadline reminders |
| `allocation` | Matching engine: lot classes, scoring, greedy solver with share constraints, **max-flow coverage**, plans with soft and hard reservations |
| `position` | GoO coverage position by bucket and vintage, expiry ladder, EU ETS compliance ladder, snapshots |
| `ledger` | Posting rules as data, immutable journal, FIFO / weighted-average costing, periods, FX, GL export |
| `workflow`, `audit` | Approval inbox (task types registered by modules, SLA, escalation) with segregation of duties; append-only audit trail |
| `document` | Rendered and stored PDFs (confirmations, invoices, netting statements), immutable and hashed |

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

## Clone and run on a new machine

```bash
git clone https://github.com/Pianonurmi/goo-ets-etrm.git
cd goo-ets-etrm
docker compose -f ops/docker-compose.yml up -d postgres keycloak
export ETRM_CODEGEN_DB_URL=jdbc:postgresql://localhost:5432/etrm_codegen
export ETRM_TEST_DB_URL=jdbc:postgresql://localhost:5432/etrm_test
export ETRM_DB_USER=etrm ETRM_DB_PASSWORD=etrm
./gradlew build
```

- You need JDK 21 and Docker. The Gradle wrapper downloads Gradle itself.
- `ops/postgres-init.sql` creates the code-generation and test databases on the first start of the PostgreSQL container. If the volume already exists, create them by hand.
- Work happens on `main`, the default branch.
- `CLAUDE.md` summarises commands and conventions for working on the code with Claude Code.

## Golden path (see `apps/api/src/test/kotlin/com/pianonurmi/etrm/GoldenPathIT.kt`)

1. Create a legal entity, book, counterparty, registry accounts and a production device.
2. Receive certificates (`POST /api/v1/inventory/lots`) – the ledger posts the issuance.
3. Capture a GoO sale with criteria (`POST /api/v1/trades`), approve it as a second user through the workflow inbox, confirm it – an obligation opens.
4. Check coverage (`GET /api/v1/allocation/coverage`), propose a plan, approve it as a second user – lots are reserved, the registry transfer executes, the trade is delivered, revenue and cost of goods sold are posted.
5. Inspect positions (`/api/v1/positions/goo`, `/api/v1/positions/ets`), the ledger (`/api/v1/ledger/entries`, `trial-balance`, `journal/verify`) and the audit trail (`/api/v1/audit/events`).
6. From delivery to cash (`AllowanceDeliveryIT`): an EUA sale is delivered through a four-eyes Union Registry instruction, invoiced (`/api/v1/settlement/invoice-runs`, approval in the inbox), paid (`/api/v1/settlement/payments`), and the trade becomes SETTLED with the receivable cleared in the ledger.

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
