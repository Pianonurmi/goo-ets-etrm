# Changelog

All notable changes to Provena. The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and
versions follow [Semantic Versioning](https://semver.org/spec/v2.0.0.html) as described in
[releases](docs/operations/releases.md). Each minor version of the 0.x line is one increment of the
[roadmap](docs/roadmap.md). Tags, GitHub releases and container images start with 0.9.0; the earlier versions are recorded here only.

## [Unreleased]

### Added
- **Release workflow:** releases a version from GitHub's Actions tab without a clone: it checks the version, tags
  the commit and runs CI for it, which publishes the image tags and the GitHub release.
- **Presentation in the repository:** the 18-slide deck as a PDF and as a web page with speaker notes
  (`docs/presentation/`), built from one HTML source per slide.
- **Portfolio figures patched in place (ADR 0043):** after a change to an entity's lots or obligations, available to
  sell, the product view and the GoO limit measures are patched from what changed (about 0.3 s at 100 000 lots)
  instead of rebuilt. A pre-trade check after a change is now answered instead of reading "not checked".

- **GoO limits by vintage:** concentration, expiry and basis limits can be narrowed to one production year, on the
  limit form too.
- **Sales checked against the basis limit:** a GoO sale is served from the cheapest free certificates that qualify,
  and the premium it would hand over is checked against the entity's basis limits before capture (`GOO_BASIS`).

- **GoO give-away report** (`GOO_GIVE_AWAY`): every sale of the plans approved in a period with its delivered value,
  its product's mark and the give-away, logged like every report.

### Changed
- **Preparing the portfolio figures:** a full build at 100 000 lots and 5 000 sales takes about 3 s instead of about
  28 s. Eligibility is computed once per criterion, the scarcity index only for plans, and the flow is compressed by
  profile. Plan coverage is faster too (2.3 s to 0.7 s).

## [0.9.0] - 2026-10-12

Housekeeping (increment 9).

### Added
- **Versioning and releases:** semantic versions in `gradle.properties`, checked against the web package, the Helm
  chart and this changelog. A `vX.Y.Z` tag publishes the image as `X.Y.Z`, `X.Y` and `latest`, and creates a GitHub
  release with the SBOMs and the application jar.
- **OCI image labels:** version, revision and creation time.

### Changed
- **CI:** GitHub Actions moved to current majors (checkout 7, setup-java 6, setup-node 7, upload-artifact 7,
  setup-helm 5, setup-gradle 6), so no job runs on the retired Node 20.
- **Backend:** Gradle 9.8.1, Kotlin 2.4.21, Kover 0.9.11, Flyway 13.10, jOOQ 3.21.9, Tomcat 11.0.26.
- **Web:** react-router 8.4 and ESLint 10. Node 22.22 is now the minimum.
- **Dependabot:** action updates are grouped, up to ten PRs may be open per ecosystem, and held-back majors are
  recorded with their reason (TypeScript 6 and 7; `@types/node` above 22).
- **Documentation:** the README and the presentation are rewritten for enterprise readers (architecture, reliability,
  security, robustness, maintainability, roadmap), with fresh screenshots.
- **Licence:** PolyForm Noncommercial 1.0.0.

## 0.8.0 - 2026-10-12

GoO portfolio management (increment 8).

### Added
- **Value-based allocation in EUR**, with the give-away reported per line, obligation and plan.
- **Shadow prices:** marginal cost per obligation and scarcity rent per bucket and year.
- **Available to sell** per bucket, vintage and delivery, and the `GOO_HEADROOM` pre-trade finding.
- **Product hierarchy** from proven containment, netted through the eligibility flow.
- **Future supply:** forecasts per device, purchases and expected issuance as supply tiers, and virtual plan lines.
- **Portfolio value and cheapest to deliver** with every valuation run, with an explain at sign-off.
- **GoO limits** on concentration, expiry and basis, with pre-trade checks.

### Changed
- **CI** runs on GitHub's hosted runners, with a self-hosted runner as a fallback for a private repository.

## 0.7.0 - 2026-10-12

Public showcase demo (increment 7).

### Added
- **Design system and app shell:** the Provena brand and a role-aware home page.
- **Showcase sign-in:** demo accounts and role personas.
- **Demo data:** six months of business in every area, seeded in logged, resumable steps, with a nightly reset.
- **Every remaining operation on a screen**, with guards where the API had none.
- **Manual and onboarding:** the help manual in the app, guided tours, the demo guide and screenshots.
- **Public demo stack** (PostgreSQL, the image, Caddy) and the homelab guide.

## 0.6.0 - 2026-10-11

Scale and operations (increment 6).

### Added
- **Observability:** Prometheus metrics, OpenTelemetry traces, JSON logs and a Kubernetes management port.
- **Supply chain:** SBOMs and a blocking image scan.
- **Kafka:** selected domain events externalised after commit.
- **Kubernetes:** Helm chart and a CloudNativePG cluster.
- **Partitioning:** monthly partitions for the audit trail and the lot journal.
- **Allocation:** an exact CP-SAT solver, never worse than greedy.
- **Load and recovery:** k6 load tests, the DR drill and penetration test preparation.

### Changed
- **Runtime:** Java 25 with virtual threads.

## 0.5.0 - 2026-10-11

Operations screens (increment 5).

### Added
- **Operations screens:**
  - settlement items, invoices, payments, netting and VAT rules;
  - registry instructions and transfer routes;
  - reconciliation breaks;
  - counterparty confirmations.
- **Guards:** role and entity guards on the operations endpoints.

## 0.4.0 - 2026-10-11

Web UI (increment 4).

### Added
- **The web UI, bundled into the application jar.** It covers:
  - the approval inbox, trade capture and the blotter;
  - the inventory explorer with the criteria builder, and the allocation workbench;
  - positions, market data, valuation, risk and ETS compliance;
  - the ledger, month end, reports and administration.
- **OpenAPI contract:** a committed OpenAPI document with generated client types.

## 0.3.0 - 2026-10-10

Valuation, risk and finance (increment 3).

### Added
- **Market data and valuation:** market data with ECB reference rates, curves with official snapshots, mark to market
  and P&L explain.
- **ETS compliance and period end:** ETS compliance, and ledger period end (MtM by book type, NRV, provision, FX
  revaluation).
- **Risk:** limits, credit exposure and pre-trade checks.
- **Reporting:** the report catalogue, EMIR events and MiFID II ancillary activity.
- **GL interface:** exactly-once exports and reconciliation.

## 0.2.0 - 2026-10-10

Registries, settlement and workflow (increment 2).

### Added
- **Approval inbox** with a task-handler SPI.
- **Registry integration:** the registry outbox, statement formats, Union Registry deliveries and the registry API
  channel framework.
- **Reconciliation** break workflow.
- **Confirmations:** trade confirmation matching.
- **Settlement:** VAT rules, invoices, payments, credit notes and netting, posted to the ledger.

## 0.1.0 - 2026-10-10

Foundation (increment 1).

### Added
- **Core modules:** reference data, taxonomy, the product catalogue and the GoO criteria language; trades;
  certificate inventory; the allocation engine; positions.
- **Ledger** with posting rules as data.
- **Workflow and audit.**
- **CI and documentation.**

[Unreleased]: https://github.com/Pianonurmi/goo-ets-etrm/compare/v0.9.0...HEAD
[0.9.0]: https://github.com/Pianonurmi/goo-ets-etrm/releases/tag/v0.9.0
