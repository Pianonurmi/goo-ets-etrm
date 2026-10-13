# Changelog

All notable changes to Provena. The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and
versions follow [Semantic Versioning](https://semver.org/spec/v2.0.0.html) as described in
[releases](docs/operations/releases.md). Each minor version of the 0.x line is one increment of the
[roadmap](docs/roadmap.md). Tags, GitHub releases and container images start with 0.9.0; the earlier versions are recorded here only.

## [Unreleased]

EUA futures margining (increment 12).

### Added
- **Clearing accounts and daily variation margin:** an entity's account at a clearing house; every evening the
  futures cleared through it are margined at the exchange's settlement price (the position's move overnight and each
  new trade against the settlement price). Runs are kept per day and versioned when a trade or price changes
  (ADR 0046).
- **Clearing statements:** the clearing member's daily statement (CSV) is imported per account, versioned when
  re-sent and checked against the computed margin contract by contract; a difference opens a break in the inbox,
  which closes when corrected or is accepted by a second person with a reason. Initial margin moves cash at the
  member into margin posted (1970), and fees are booked to 4610.
- **Margin calls:** when the latest statement shows less cash at the member than the initial margin, a margin call
  opens in the inbox; a second person pays it (bank to cash at the member) or disputes it with a reason. A later
  statement replaces an unpaid call.
- **Risk for cleared futures:** margined futures no longer count as MtM or PFE towards the clearing house; the margin
  it holds counts as exposure instead. New limit type **Margin liquidity** (initial margin plus open calls).
- **Variation margin in the ledger:** realised result on futures (3600) against cash at the clearing member (1960),
  posted daily; a corrected day posts only the difference.

### Changed
- **Period end:** futures margined through a clearing account are no longer in the period-end MtM, since their
  result is already realised by the margin.
- **Hedge accounting:** futures cannot be designated as cash flow hedging instruments.

## [0.11.0] - 2026-10-13

Valuation depth (increment 11).

### Added
- **Discount curves:** zero rates by tenor per currency, built from rate quotes (negative rates allowed), signed off
  by a second person like other curves, and a discount-factor lookup on the curves screen.
- **Discounted MtM:** each delivery schedule is valued at its payment date's discount factor (futures are not
  discounted); valuation results show the payment date, the factor and the value before discounting.
- **Time and rates in the P&L explain:** the passage of time and the move of the discount curve are shown apart from
  price.
- **Carry beyond the last future:** an allowance forward curve can grow at its last carry rate instead of staying flat;
  the demo EUA curve does.
- **Cash flow hedge accounting:** forecast allowance or GoO purchases and sales hedged with forwards are designated by
  one accountant and approved by another, with the hedge documentation issued as a PDF; effectiveness is measured on
  every official valuation, and month end puts the effective portion in the hedging reserve and the rest in profit or
  loss. Discontinuation also needs a second accountant. New report: hedge effectiveness.
- **Invite-only demo behind a Cloudflare Tunnel:** `ops/demo/docker-compose.tunnel.yml` runs the showcase with a
  tunnel connector and publishes no port on the server; Cloudflare Access admits only the e-mail addresses you list,
  then everyone signs in with the demo password. Caddy takes each visitor's address from Cloudflare, so a wrong
  password locks out only that visitor; the session cookie is always Secure and HSTS is sent. The VPS guide has the
  steps (tunnel, Access policy, zone settings, inviting and removing people).

### Changed
- **Demo stack hardened:** no new privileges in any container, capabilities dropped, process limits and rotated
  logs; the database and the application sit on a network the edge cannot reach. CI validates both compose
  variants and the Caddyfiles.
- **Web:** when an access proxy's session expires, the page reloads once so the proxy can sign in again, instead of
  showing network errors.

## [0.10.0] - 2026-10-13

Portfolio follow-ups (increment 10).

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
- **Available-to-sell checks on large books:** sales whose eligible supply is nested (later delivery dates) share a
  chain in the flow, so a check at 100 000 lots takes about 30 ms instead of about 150 ms, and the table 4 s instead
  of about 30 s. Device caps stay outside the flow (measured: 32 s instead of 0.7 s to build it).
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

[Unreleased]: https://github.com/Pianonurmi/goo-ets-etrm/compare/v0.10.0...HEAD
[0.10.0]: https://github.com/Pianonurmi/goo-ets-etrm/releases/tag/v0.10.0
[0.9.0]: https://github.com/Pianonurmi/goo-ets-etrm/releases/tag/v0.9.0
