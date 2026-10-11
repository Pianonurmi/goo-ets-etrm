# Provena

An ETRM for guarantees of origin and EU ETS allowances.

![The trader's home page in Provena: tasks, trades pending approval, coverage and mark to market](docs/images/home-trader.png)

Provena follows a certificate or an allowance from the trade to the registry, the invoice and the general ledger. A
sale names criteria, not lots, and the system finds the certificates that satisfy them. Every step that moves value
needs a second person. It is built for a European company group with several legal entities.

## Try the demo

<!-- demo URL -->
The public demo sits behind a login. Ask the maintainer for a demo account.

- You act as a **persona**: trader, middle office, back office, risk, accountant, auditor, administrator and others.
  Switch persona from the menu at the top right.
- Four-eyes approvals need two personas. Each approving role has two, so you can play both sides.
- The data is shared by all visitors and is reset every night. Every company, counterparty and price is fictitious.
- Start with the demo guide at `/guide`: it lists scenarios and guided tours. The `?` button in the app opens the
  manual page for the screen you are on.

You can also run the demo yourself, see [Quick start A](#quick-start-a-run-the-showcase-locally).

## What it does

<table>
  <tr>
    <td width="50%" valign="top">
      <img src="docs/images/trades-new.png" alt="Trade capture form with criteria and pricing" width="400"><br>
      <sub>Trade capture with criteria and pricing</sub>
    </td>
    <td width="50%" valign="top">
      <img src="docs/images/inventory-lots.png" alt="Certificate inventory with lots, vintages and states" width="400"><br>
      <sub>Certificate inventory</sub>
    </td>
  </tr>
  <tr>
    <td width="50%" valign="top">
      <img src="docs/images/allocation-workbench.png" alt="Allocation workbench with plan and coverage" width="400"><br>
      <sub>Allocation with the CP-SAT solver</sub>
    </td>
    <td width="50%" valign="top">
      <img src="docs/images/valuation-runs.png" alt="Valuation runs with mark to market and P&amp;L explain" width="400"><br>
      <sub>Valuation and P&amp;L explain</sub>
    </td>
  </tr>
  <tr>
    <td width="50%" valign="top">
      <img src="docs/images/monthend-close.png" alt="Month end with the close checklist and GL exports" width="400"><br>
      <sub>Month end and the GL interface</sub>
    </td>
    <td width="50%" valign="top">
      <img src="docs/images/inbox-middle.png" alt="Approval inbox of a middle office user" width="400"><br>
      <sub>The four-eyes inbox</sub>
    </td>
  </tr>
</table>

- **Trading.** GoO and EUA forwards, spots and futures; fixed or index prices; brokers, master agreements,
  intercompany mirror trades. Deal versions, approval by a second person, pre-trade limit checks.
- **Confirmations.** Our JSON and PDF confirmation, the counterparty's matched field by field, override and dispute.
- **Inventory and registries.** Certificate lots as ranges with a hash-chained journal and bitemporal history;
  allowance holdings; registry instructions through an outbox with simulator, manual, file and API gateways.
- **Allocation.** GoO criteria language compiled to SQL; matching with a greedy or exact (CP-SAT) solver, share
  constraints, max-flow coverage and plans with soft and hard reservations.
- **Positions.** GoO coverage by bucket and vintage, expiry ladder, EU ETS compliance ladder, snapshots.
- **Reconciliation.** Registry statements against our holdings, with a break lifecycle and resolutions.
- **Settlement and invoicing.** Settlement items per delivery, VAT rules as data, gap-free invoices with PDF,
  payments, credit notes, netting, ageing and VAT reports.
- **Market data and curves.** Instruments, prices and FX rates (bitemporal, plausibility checks), file imports,
  ECB reference rates, official curve snapshots signed off by a second person, index fixings.
- **Valuation.** Mark to market per delivery schedule against official curves, end-of-day runs signed off by risk,
  P&L explain.
- **Risk.** Limits under four eyes, credit exposure and credit support, utilisation and breaches, pre-trade checks.
- **EU ETS compliance.** Installations and aircraft operators, verified emissions, free allocation, surrender
  attribution and requests, deadline reminders.
- **Ledger and month end.** Posting rules as data, immutable journal, FIFO and weighted-average costing, period end
  (MtM by book type, NRV, emission provision, grant release, FX revaluation), exactly-once GL exports and a gated
  hard close.
- **Reports and EMIR.** Report catalogue with logged, hashed exports; EMIR trade events and completeness; MiFID II
  ancillary activity.
- **Audit.** Append-only, hash-chained audit trail; segregation of duties enforced in services and the database.

## Quick start A: run the showcase locally

This runs the published image with the demo data, as the public demo does. You need Docker with the compose plugin.

```bash
git clone https://github.com/Pianonurmi/goo-ets-etrm.git
cd goo-ets-etrm/ops/demo
cp .env.example .env && chmod 600 .env
# edit .env: set POSTGRES_PASSWORD and ETRM_DEMO_PASSWORD (12 characters or more)
docker compose pull && docker compose up -d
docker compose logs -f app      # "Demo data created in ... s" when the example portfolio is ready
```

The first start migrates the database and creates about six months of business through the API, which takes around
a minute. Until then Caddy shows a maintenance page. Open `http://127.0.0.1/` and sign in as `demo` with your
password. `ETRM_DEMO_USERS` (`alice:secret-one,bob:secret-two`) sets several sign-ins instead.

| Variable in `.env` | Purpose |
|---|---|
| `POSTGRES_PASSWORD` | Database password; only the containers use it |
| `ETRM_DEMO_PASSWORD` / `ETRM_DEMO_USERS` | Demo sign-in: user `demo`, or a list of `user:password` pairs |
| `ETRM_SITE_ADDRESS` | `:80` for plain HTTP; a domain name turns on HTTPS from Let's Encrypt |
| `ETRM_IMAGE` | `ghcr.io/pianonurmi/goo-ets-etrm:main`, or a commit tag to pin a version |

The demo wipes its database on every start and resets nightly at 04:15 Helsinki time. To put it on a server, with a
firewall, a domain, backups and a security checklist, follow the [deployment guide](docs/deploy/vps.md).

## Quick start B: developer build

You need JDK 25 (Gradle itself runs on JDK 21 or later), Node.js 22 with npm, and Docker.

```bash
# PostgreSQL 17; creates etrm, etrm_codegen, etrm_test and etrm_e2e (user etrm/etrm)
docker compose -f ops/docker-compose.yml up -d postgres

export ETRM_CODEGEN_DB_URL=jdbc:postgresql://localhost:5432/etrm_codegen
export ETRM_TEST_DB_URL=jdbc:postgresql://localhost:5432/etrm_test
export ETRM_DB_USER=etrm ETRM_DB_PASSWORD=etrm

./gradlew build          # lint, jOOQ code generation, all tests, coverage rule

# Run with the dev profile (no sign-in, persona headers) and demo data on first start
SPRING_PROFILES_ACTIVE=dev,demo ./gradlew :apps:api:bootRun
```

Open `http://127.0.0.1:8080/` for the web UI (pick a persona in the header) and `http://127.0.0.1:8080/swagger-ui`
for the API. The dev profile binds the loopback address only.

For the web UI with hot reload, keep the API running and start Vite, which proxies `/api` to port 8080:

```bash
cd apps/web && npm run dev      # http://localhost:5173
```

- The build needs PostgreSQL even before the tests, because jOOQ sources are generated from a Flyway-migrated
  database. Without `ETRM_TEST_DB_URL`, integration tests start a Testcontainers PostgreSQL.
- `./gradlew build -Petrm.skipWeb` builds the backend without the web UI. `./gradlew :apps:web:check` runs lint,
  format, typecheck and Vitest for the UI.
- `./gradlew :apps:api:test --tests '*GoldenPathIT*'` runs the end-to-end REST scenario, `*MonthEndIT*` the path
  from market data to hard close.
- Full-size allocation benchmark:
  `./gradlew :modules:allocation:test --tests '*AllocationBenchmarkTest*' -Detrm.benchmark.lots=100000 -Detrm.benchmark.obligations=5000`
- `ops/docker-compose.yml` also defines a Keycloak service for token sign-in outside the dev profile.
- `CLAUDE.md` lists the commands and conventions for working on the code.

## Architecture

Provena is a modular monolith: one deployable with strict module boundaries that the build verifies. It is written in
Kotlin on Spring Boot 4 with Spring Modulith. Each module is a bounded context with its own PostgreSQL schema and
Flyway migrations. Data access is jOOQ, not JPA. The web UI (React, TypeScript, Vite) is bundled into the API jar.
Modules talk through their `api` packages and through domain events published in the command transaction. Selected
events go to Kafka after commit when enabled.

| Module | What it does |
|---|---|
| `referencedata`, `taxonomy` | Legal entities, books, counterparties, registries and accounts, devices; EECS-style code hierarchies |
| `product` | Product catalogue and the GoO criteria language |
| `trade` | Deal capture, versions, four-eyes approval, confirmation matching, deliveries, intercompany mirroring |
| `inventory` | Certificate lots as ranges, allowance holdings, hash-chained journal, registry reconciliation |
| `registry` | Transfer routes, instruction outbox, simulator, manual, file and API gateways, statement formats |
| `settlement` | Settlement items, VAT rules, invoices with PDF, payments, credit notes, netting |
| `marketdata` | Instruments, prices, FX, import formats, index fixings, ECB reference rates |
| `valuation` | Mark to market against official curves, end-of-day runs, P&L explain |
| `reporting` | Report catalogue, logged exports, EMIR events, MiFID II ancillary activity |
| `risk` | Limits, credit exposure and support, breaches, pre-trade checks |
| `etscompliance` | Installations, verified emissions, free allocation, surrender, deadlines |
| `allocation` | Matching engine: lot classes, scoring, greedy and CP-SAT solvers, max-flow coverage, plans |
| `position` | GoO coverage, expiry ladder, EU ETS compliance ladder, snapshots |
| `ledger` | Posting rules, immutable journal, costing, periods, period end, GL interface, hard close |
| `workflow`, `audit` | Approval inbox with segregation of duties; append-only audit trail |
| `document` | Rendered, stored and hashed PDFs |

```
build-logic/     Gradle convention plugins (Kotlin, module with jOOQ codegen, application)
platform/        shared kernel (value types, events, errors, security context, test fixtures)
modules/<name>/  bounded contexts: api / domain / application / adapter, own schema and migrations
apps/api/        Spring Boot application: migrations runner, security, demo data, end-to-end tests
apps/web/        web UI (React, TypeScript, Vite), bundled into the API jar
docs/            arc42, ADRs, domain and integration notes, roadmap, glossary
ops/             docker-compose, Dockerfile, demo stack, Helm chart, CloudNativePG cluster, k6 load test, DR drill
config/detekt/   static analysis configuration
```

Read more in the [architecture plan](docs/architecture-plan.md), the [arc42 documentation](docs/arc42/) and the
[decision records](docs/adr/) (ADR 0001 to 0034).

## Quality and operations

**Tests.** Unit tests per module, integration tests against PostgreSQL, end-to-end REST scenarios
(`GoldenPathIT`, `MonthEndIT`), a modularity test that fails the build on illegal dependencies, and a coverage rule.
The web UI has Vitest tests and Playwright specs that include axe accessibility and CSP checks. Load tests use k6
([load testing](docs/operations/load-testing.md)). See [UI testing](docs/ui/testing.md).

**Security.** The public demo has a documented threat model and accepted risks
([showcase security](docs/security/showcase.md)). Builds publish CycloneDX SBOMs, scan the image with Trivy and
fail on critical fixable vulnerabilities ([supply chain](docs/operations/supply-chain.md)). Preparation for an
external penetration test is in [docs/security/penetration-test.md](docs/security/penetration-test.md).

**Operations.** Prometheus metrics, OpenTelemetry traces and ECS JSON logs
([observability](docs/operations/observability.md)). A Helm chart and a CloudNativePG cluster for Kubernetes
([Kubernetes](docs/operations/kubernetes.md)). Events to Kafka through Spring Modulith when `ETRM_KAFKA_ENABLED`
([ADR 0029](docs/adr/0029-event-externalisation-to-kafka.md)). Monthly partitions for append-only tables
([database](docs/operations/database.md)). A disaster recovery runbook with a drill script
([disaster recovery](docs/operations/disaster-recovery.md)).

## Documentation

- [Getting started](docs/user-guide/getting-started.md): the user guide, one page per area (also served in the app
  under `/help/`).
- [Deployment guide](docs/deploy/vps.md): the public demo on one server.
- [Web UI](docs/ui/README.md) and the [design system](docs/ui/design-system.md).
- [Roadmap](docs/roadmap.md): what each increment delivered.
- [Documentation index](docs/README.md): architecture, domain notes, integration, glossary.
- Presentation: [the slides](https://claude.ai/artifact/NHG4trUF1EkTnB6dXmAxyv) (PowerPoint or PDF from its Share
  menu) and their [outline with speaker notes](docs/presentation/outline.md).
