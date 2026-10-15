# Changelog

All notable changes to Provena. The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and
versions follow [Semantic Versioning](https://semver.org/spec/v2.0.0.html) as described in
[releases](docs/operations/releases.md). Each minor version of the 0.x line is one increment of the
[roadmap](docs/roadmap.md). Tags, GitHub releases and container images start with 0.9.0; the earlier versions are recorded here only.

## [Unreleased]

### Security
- PostgreSQL JDBC driver 42.7.14 (GHSA-rhp9-mr79-r74h, GHSA-f64h-wr5q-3qf3). The application uses neither
  `requireAuth` nor the driver APIs that padded values with earlier bytes, so 0.14.0 is not exposed.
- **Gradle dependency locking and verification metadata** (review finding S8): every configuration is locked and
  every downloaded artifact is checked against `gradle/verification-metadata.xml`. A workflow refreshes both, and the
  npm lock file, on Dependabot pull requests ([supply chain](docs/operations/supply-chain.md#pinning)).

### Added
- **Beneficiary register** (review finding S7). A cancellation's beneficiary is recorded once in the register, and the
  cancellation carries `beneficiaryRef`. An administrator asks for an erasure with a reason
  (`POST /api/v1/inventory/beneficiaries/{id}/erasure`), and an auditor approves it in the inbox (`BENEFICIARY_ERASURE`).
  The approval removes the name and identifier from the register, the lots, the registry instructions and the
  obligations ([data protection](docs/operations/data-protection.md)).
- **Archive cuts.** An administrator declares that audit and lot journal rows before a month may be archived
  (`POST /api/v1/admin/integrity/archive-cuts`, at least `etrm.integrity.archive-min-age` old, ten years by default).
  The chains must verify first, and the lot journal is anchored at the cut. Afterwards the old partitions can be
  dropped and the chains still verify; a missing row after the cut is still reported.
- Invoices, payments and valuation results are listed a page at a time (newest first for invoices and payments), with
  **Load more** on their screens: `GET /api/v1/settlement/invoices/search`, `/api/v1/settlement/payments/search` and
  `/api/v1/valuation/runs/{id}/results/search`. The full lists stay for integrations.

### Changed
- **The demo seeder is no longer in the production jar and image.** `bootJar` builds the application alone;
  `showcaseJar` (`api-showcase.jar`) adds the seeder, and CI publishes it as `ghcr.io/pianonurmi/goo-ets-etrm-showcase`.
  The public demo (`ops/demo`) now pulls that image: set `ETRM_IMAGE` to it if your `.env` names the old one.
- **Records that cannot forget no longer keep a beneficiary's name or identifier:** the lot journal, the audit trail,
  the lots' version history and the instructions' event history keep only the `beneficiaryRef`. Rows written before
  this version keep their copy.
- Externalised events carry the `beneficiaryRef` of a cancellation (still without the name and identifier).
- Period end reads the accounts it measures (MtM, hedges, NRV, provision, grant release, FX revaluation) from its own
  posting rules instead of fixed account numbers, so remapping a rule moves the measurement with it.
- A cursor that does not decode is refused with 422 on every paged list; the trade search answered 400 before.
- Dependencies: Jackson 3.2.3, lz4-java 1.12.0, Mantine 9.7.2; demo stack Caddy 2.11 and cloudflared 2026.10.0.
- The mirror of an intercompany trade delivers from the account the original names for its counterparty, when that
  is a registered account of the mirror's entity.

### Fixed
- Invoices and credit notes created without a date are dated in the legal entity's time zone, not in UTC (an invoice
  run shortly after midnight in Helsinki no longer takes the previous day).
- Domain events carry the application's clock, as everything else does, instead of the JVM's.
- **A mirror approved after its original was confirmed stayed unconfirmed.** Approving it now takes the original's
  confirmation over, and opens its delivery obligations.
- **Registry accounts changed on an intercompany trade did not reach its mirror.** A new delivery account of the
  original becomes the mirror's counterparty account, and a new counterparty account its delivery account; accounts
  the amendment leaves alone keep the mirror's own.
- **Credit exposure left out trades the valuation could not price without saying so.** A result with a missing price,
  rate or bucket, or one not valued, now marks the counterparty's exposure incomplete, naming the trade.
- **A month average waited for the next quote import after its month ended.** A morning job
  (`etrm.settlement.reprice-cron`, 06:00 Europe/Berlin) prices the deliveries still waiting for a fixing.
- **Computing a margin day again failed when a later day had no settlement price**, and the correction of the day
  itself was lost. The day now commits; the cascade stops at the day it cannot compute, and that day and the later ones
  are marked stale with the reason until they are computed again (Futures and clearing → Margin).

## [0.14.0] - 2026-10-15

Increment 14, production readiness: the rest of the [enterprise readiness review](docs/reviews/2026-10-enterprise-readiness.md)
after the patches 0.13.1 and 0.13.2 (status per finding in the review, what moved to increment 15 in its
[plan](docs/plans/increment-15-maintainability.md)).

**Before upgrading:**
- Kubernetes: create the runtime database role's secret (`database.runtimeSecret`, e.g. `etrm-db-runtime`); migrations
  now run in a pre-upgrade Job as the owner ([Kubernetes](docs/operations/kubernetes.md)).
- Identity provider: tokens must carry the `etrm-api` audience (`oidc.audience`); add the audience mapper first.
- Kafka: create the topics before enabling it (`kafka.createTopics` now defaults to off), and tell consumers that
  cancellation beneficiaries are no longer in the events.
- Re-run the period end of any open period, as after 0.13.2.

### Security
- **Kafka events no longer carry a cancellation's beneficiary.** `InstructionConfirmed`, `InstructionFailed`,
  `LotReceived` and `LotTransferred` leave with `beneficiaryName` and `beneficiaryId` set to null; consumers read
  the beneficiary through the API by the instruction or lot id. A change to the event contract (ADR 0029): a consumer
  that read those fields must now call the API. Topics written by earlier versions still hold them; see
  [data protection](docs/operations/data-protection.md) for what is kept where, for how long, and how access and
  erasure requests are handled.
- **Tokens must be issued for this API.** The resource server checks the `aud` claim (`ETRM_OIDC_AUDIENCE`, default
  `etrm-api`; Helm value `oidc.audience`). The development realm adds the audience with a mapper; an identity
  provider in production needs the same before upgrading.
- **Administrators no longer decide approvals.** ADMIN administers the inbox (assigns, escalates, cancels) but a
  four-eyes decision needs one of the task's candidate roles held explicitly.
- **Legal-entity scope everywhere.** The audit trail and the home activity feed are read only by users who see
  every entity; bank accounts and the history of addresses, VAT numbers and bank accounts need a maintaining role;
  history is kept for a fixed list of tables; books, registry accounts, a single journal entry, free allocation,
  a change request, valuation submission and production forecasts check the entity.
- **The actuator on the application port is for administrators**; probes and Prometheus use the management port.
- **The `test` profile is held to the `dev` profile's rules** (loopback only, never with the public profiles).
- **Request bodies with unknown properties are refused** instead of silently dropping the property, and a body
  that does not parse names the failing field rather than the parser's internal message.
- Jackson 3.1.7 and 2.22.3 and lz4-java 1.11.4 ahead of Spring Boot and Kafka (CVE-2026-89407, CVE-2026-68497,
  CVE-2026-106451); the image scan now fails the build on high vulnerabilities with a fix, not only critical ones.
- The showcase login throttle counts failures only, so successful sign-ins never lock anyone out, and its memory is
  bounded. The FILE registry gateway uses only file-name-safe characters from an instruction key.
- **The application no longer runs as the schema owner** in Kubernetes: migrations and partition maintenance use the
  owner (`ETRM_DB_MIGRATION_USER`), requests and jobs a role with table rights only, which cannot disable the
  append-only triggers ([database operations](docs/operations/database.md#roles-schema-owner-and-runtime-role)).
- **The journal is protected in the database.** An entry can only change from posted to reversed, by its reversal,
  and only once, also when two people reverse it at the same moment; nothing is written into a hard-closed period;
  lines balance at commit. New entries are hashed with the header, rates, base amounts, dimensions and memo
  (chain version 2); earlier entries keep verifying.
- **Allowance movements, trade versions, lot versions, reference data history and posting rule history are
  append-only**, and allowance movements are hash-chained per holding (the journal check reports `brokenHoldings`).
  Every state of a registry instruction is kept in `registry_instruction_event`.
- **The audit trail is sealed into a hash chain** every minute and verified with the other chains every night; the
  gauge `etrm_integrity_chain_ok` reports each chain, and a daily digest of the chain heads, signed when
  `ETRM_INTEGRITY_SIGNING_KEY` is set, is stored for keeping outside the database
  ([tamper evidence](docs/operations/database.md#tamper-evidence)).
- **The audit trail says who read sensitive data**: document content, bank accounts, report and invoice exports and
  the trail itself, and showcase sign-ins including failed ones. History rows name the user who made the change, and
  audit records carry the request's trace id.

### Changed
- **Overload and database outages answer quickly.** At most 200 API requests run at once (`etrm.http.max-concurrent-requests`);
  more wait two seconds and get 503 with `Retry-After`. A request waits at most five seconds for a database
  connection. Readiness no longer includes the database, so an outage no longer takes every pod out of the service
  at once; `ETRM_DB_POOL_MAX` (Helm `database.poolMax`) sizes the pool. Probes time out after three seconds.
- Registry adapters keep one HTTP client per registry instead of building one per call, and use the JVM's proxy
  settings.
- **Migrations run before an upgrade, not in the pods** (review finding O1). The Helm chart runs the image with
  `--migrate-only` in a `pre-install,pre-upgrade` Job as the schema owner; a failed migration stops the upgrade while
  the running version keeps serving. The pods start with migrations off (`migrations.job: false` restores the old
  behaviour). CI starts the previous release on every build's schema (ADR 0048).
- **Chart** (O5, O6): a node drain takes one pod at a time (`podDisruptionBudget.maxUnavailable`); a running
  scheduled job finishes before shutdown; `/tmp` is bounded (`tmpSizeLimit`); Kafka with SASL and without topic
  creation (`kafka.createTopics` now defaults to off: create the topics before enabling Kafka); `ecb.enabled`;
  application settings such as registry adapters through `config`; an optional egress policy
  (`networkPolicy.egress`). See [Kubernetes](docs/operations/kubernetes.md).
- **Point-in-time recovery is drilled** (O4): a monthly workflow restores a CloudNativePG cluster to a target time
  on kind and checks the rows; the production cluster archives WAL at least every five minutes
  (`archive_timeout`). [Disaster recovery](docs/operations/disaster-recovery.md) lists what to reconcile per
  integration after a recovery.
- **Stricter gates.** Every GitHub Action is pinned to a commit and the base images to a digest, kept current by
  Dependabot; the image scan fails on high as well as critical vulnerabilities that have a fix; the weekly
  dependency check fails when its NVD key is missing and on a CVSS score of 7 or more; coverage must stay at 90 % of
  lines and 65 % of branches.
- **Browser tokens stay in memory only**, no longer in session storage; a reload signs in again silently, and the
  silent renewal of a session works without a refresh token (its page may now be framed by the application itself,
  and only by it). See the amendment to ADR 0026.
- Grids open the focused row with Enter or Space, as a click does, so every detail page can be reached by keyboard.
- **A save refused because the record changed no longer repeats itself.** On a trade amendment and on editing a legal
  entity, counterparty or production device, the form fetches the record again, lists the fields that changed on the
  server, and **Reload, keep my edits** continues from the current version with your edits on top.
- **Long lists say so.** Lots, criteria search results, received confirmations, registry statements and the lots
  offered for a receipt show a notice when the answer came back exactly at its limit; a trade's version changes
  beyond the first 40 are counted; the hedge designation loads further pages of trades on request.
- **Numbers and dates follow the browser's language** (English UK remains the fallback), times of day show in the
  selected legal entity's time zone with a short zone name, and the labels of states and types come from the
  translation catalogue; a test fails when a text key is missing or the API gains a constant without a label.
- The web UI retries network failures twice, refetches stale data when the window regains focus, and reloads
  `/api/v1/me` on focus so a role change applies without a reload; tabs and filters read from the URL fall back to
  their default for a value they do not know; decimal strings are formatted exactly instead of through a double; a
  download keeps its object URL for ten seconds.

### Added
- Property tests take their seed from `PropertySeeds`: fixed in a build, `-Detrm.proptest.seed=<n>` replays one, and a
  nightly workflow runs them with random seeds. A ledger property test checks that foreign-currency entries balance
  in base currency after rounding.
- Concurrency tests for the plan approval and netting settlement row locks and the portfolio cache's single build.
- A month-end load scenario (`batch`: valuation, period end, trial balance, MTM report, invoice export) with its own
  threshold ([load testing](docs/operations/load-testing.md)).
- `OperationGuardsIT` sends every command of the OpenAPI document as a signed-in user with no roles and expects it
  refused; `TokenValidationIT` checks signature, expiry, issuer, audience and unsigned tokens with Spring Boot's own
  decoder.
- **Operations signals** (review findings O2, O3, O7, O8): every scheduled job reports failures, its last success and
  its expected interval (`etrm_jobs_*`); alert rules for the Prometheus Operator (`prometheusRule.enabled`) with a
  [runbook](docs/operations/runbooks/README.md) per alert and a metric catalogue in
  [observability](docs/operations/observability.md).
- Event publications that fail ten times are parked instead of retried forever; administrators list and resubmit
  them under `/api/v1/admin/event-publications`.
- The change process: `CODEOWNERS`, a pull request template, `SECURITY.md`, [contributing](docs/contributing.md)
  and the [branch protection](docs/operations/branch-protection.md) ruleset; workflows run with a read-only token
  by default.
- **Events the ledger cannot post wait in the inbox** (`LEDGER_ATTENTION`, for accountants). A posting listener now
  runs in a savepoint: when it fails (no FX rate, too few cost layers) the delivery, registry confirmation or payment
  still commits, and the event is recorded for the ledger's attention instead of failing the business transaction.
  A posting rule that applies but lacks an amount (an index price not fixed yet) is recorded the same way instead of
  posting nothing silently. An hourly job retries what can be retried and closes the task once it posts; the
  accountant can also retry from the task, or dismiss it with a reason after a manual journal. The gauge
  `etrm_ledger_unposted_open` counts what is waiting.

### Fixed
- **A period end failed with an internal error when a lot was reserved outside an allocation plan** (a manual
  reservation): the net realisable value test looked the reservation up as a plan and rolled the whole run back. It now
  values such a lot at its vintage mark.
- An answer from a registry API that arrives but cannot be read (a body cut off or too slow) is an unknown outcome,
  resolved by a status query, as a timeout is; it was already never resent.
- Foreign-currency entries balance in base currency: the cent left by rounding each line goes on the last line, and an
  entry that does not balance in base currency is refused.
- Weighted-average costing reduces cost layers in whole units, so no fractional allowances remain in the layers.
- Cancellations, expiries and deliveries are dated when they happened (the registry's confirmation, the expiry date)
  rather than when the event was processed.
- The hard close's period-end gate refuses a run older than the last posting of the period, and the release of the
  free-allocation grant reads the deferred grant net of any debit. Period end warns when MtM booked under an earlier
  treatment stays booked after the policy changed to disclosure only.
- **Cancelling an allocation plan could fail after logging success.** A registry instruction that could not be
  cancelled, or a lot that could not be released, marked the cancel transaction rollback-only and the command failed
  at commit. Both run in a savepoint now, so the plan is cancelled and the problem is logged with its stack.
- **One lot could stop every lot from expiring.** The nightly expiry ran in one transaction, so a lot the ledger could
  not post (no exchange rate, no posting rule) rolled back all expiries every night. Each lot now expires in its own
  transaction; a lot that fails stays as it was, is counted in `etrm.inventory.expiry.failures` and opens a
  `LOT_EXPIRY_FAILED` task for back office and registry operators, which closes when the lot expires.
- **Risk left out amounts without a rate and said nothing.** Receivables, collateral, PFE and group usage in a
  currency with no exchange rate counted as zero. Exposures and limit measurements now carry `incomplete`, the
  currencies left out and the reasons; a limit keeps its last value instead of falling to zero; the pre-trade check
  says that the credit check is incomplete. An option without a value still counts at delta one, now marked.
- **Risk limits and checks that could not run looked fine.** A GoO limit that could not be measured stood at zero; it
  is now `NOT_MEASURED`, keeps its last value and counts as a breach (a breach task opens). A concentration check that
  failed returned no finding; it now warns that the check could not run. Failures are logged with their stack.
- **An intercompany mirror trade diverged from its original.** Amending or cancelling one side left the other with the
  old price, obligation, exposure and position. An amendment or cancellation now reaches the mirror in the same
  transaction; when the mirror cannot take it (deliveries recorded, a decided option leg) the change is refused for
  both.
- **Smaller trade and valuation defects.** An index leg may carry a negative spread (a fixed price still may not be
  negative). A delivery date on the exclusive end of its window is refused. An index leg past its delivery with no
  fixing is `MISSING_PRICE` in a valuation run instead of being valued at the forward.
- **A settlement item that could not be evaluated failed the delivery's commit.** Parking an item whose counterparty
  or legal entity could not be found (or whose futures price lookup failed) marked the transaction rollback-only, so
  the delivery that created it failed at commit although the item had been parked. The lookups now run in a savepoint.
- **Credit notes can be refunded and netted.** What is left of a credit note after its original was paid is refunded
  by a payment in the other direction (an outgoing payment for a sales credit note) or offsets what the counterparty
  claims in a netting statement. Netting and credit notes in a foreign currency now realise the exchange difference
  to the booked rate, as a payment does; default posting rules version 8.
- **A netting statement is refused when an invoice changed after it was prepared.** Agreeing and settling check the
  amounts captured at preparation against what is outstanding now, and name the invoice (a payment applied in
  between, for example).
- **A month average is a fixing only once the month has ended.** Index-priced deliveries stay unpriced (needs
  attention) while the average of the delivery month is partial, instead of being priced at the average so far.
- **A payment is applied to an invoice only when its remittance quotes the whole invoice number.** `INV-2026-000012`
  no longer matches a remittance mentioning `INV-2026-0000123`.
- **A late settlement price no longer margins a day's move twice.** Computing a margin day again runs every later day
  of the account that already has a run, in date order, and the ledger posts only the differences. The run hash
  (and so the idempotency of a re-run) no longer depends on the scale of the prices; the first re-run of a day after
  upgrading therefore gives a version 2 with the same margin and nothing to post.
- The margin job dates its work in Europe/Berlin, where its cron runs, instead of UTC.

## [0.13.2] - 2026-10-14

An accounting patch: three defects that put wrong figures in the books, found by the
[enterprise readiness review](docs/reviews/2026-10-enterprise-readiness.md) (F1–F3). Re-run the period end of any
open period after upgrading.

### Fixed
- **Index-priced GoO sales booked revenue at the spread.** For an index-priced leg the trade's price is the spread
  over the index, but the ledger booked revenue and the accrued receivable at spread × MWh when the registry confirmed
  the delivery, and the period end used the spread as the firm sale price of a reserved lot, writing it down to
  almost nothing. Both now use the index fixing for the delivery date plus the spread, as settlement does. While the
  fixing is not there, the revenue is not booked (the cost of sales is) instead of being booked at the spread; a
  formula-priced leg is treated the same way.
- **The FX revaluation re-posted realised FX every month.** Realised FX on a paid foreign-currency invoice is booked
  in base currency, which the period end's revaluation of 1510 and 2210 did not see, so it posted the realised
  difference again as unrealised (and reversed it) at every month end. Realised FX is now tagged with the invoice's
  currency and counted as booked.
- **Allowance corrections from reconciliation left the cost layers behind.** A correction changed the holding but
  not the cost layers: units found in the registry had no layer, so their later surrender or sale failed inside the
  registry confirmation, and units missing kept their cost on the asset account. Found units now come in at the
  statement's price (3920, reconciliation differences found) and missing units leave at cost (4920, missing);
  default posting rules version 7.

## [0.13.1] - 2026-10-14

A security patch. Upgrade at once; the public showcase was redeployed on this version.

### Security
- **API reads without sign-in through encoded paths.** Up to 0.13.0 the security chain decided whether a request was
  for the web UI by its raw, percent-encoded path, while the server routed it by the decoded one. A request such as
  `GET /%61pi/v1/trades` therefore reached the API without a token or a session and ran with system rights, which see
  every legal entity. GET endpoints, the OpenAPI document and the actuator (SBOM, metrics) were readable this way in
  every deployment, including the public showcase. Only GET requests passed, so no command could be sent. The chain now matches on the decoded path, every API, actuator and OpenAPI path
  needs credentials, and a request without a signed-in user acts as nobody rather than as the system. Found by the
  [enterprise readiness review](docs/reviews/2026-10-enterprise-readiness.md) (S1).
- **No system role from a token.** A `SYSTEM` role in a token's roles claim is ignored.
- **The manual expiry sweep** (`POST /api/v1/inventory/jobs/expire`) needs back office or registry operator rights on
  every legal entity and refuses a date after today; before, any signed-in user could expire every entity's lots.

### Changed
- `KafkaExternalizationIT` is skipped where Docker is not available instead of failing.

## [0.13.0] - 2026-10-13

Options on EUA futures (increment 13).

### Added
- **Options on EUA futures:** a trade with instrument Option names its call or put, strike, expiry and underlying
  futures contract; the price is the premium per tonne and the option has no delivery schedule. Validated like
  futures (exchange or clearing house, whole lots, cleared on the contract's venue), reported to EMIR with the option
  fields, and captured and shown in the trade screens (ADR 0047).
- **Option volatility:** volatility quotes on futures instruments and an `EUA_VOLATILITY` curve per allowance product,
  read in total variance between expiries and signed off like every curve; the curve page looks it up by expiry.
- **Option valuation:** options are valued by Black-76 on the underlying's forward, discounted to expiry, with delta,
  gamma and vega per result; the P&L explain revalues them step by step and shows volatility moves (`VOL`).
- **Option premium and expiry:** the premium is booked through the clearing member's cash on confirmation (1810
  premiums paid, 2810 premiums received). At expiry an option in the money against the underlying's settlement price
  is exercised into a confirmed, cleared future at the strike, which the margin run then settles; one out of the money
  is abandoned. The option closes and its premium is released to the realised result.
- **Options in risk:** positions, the pre-trade check and PFE count an option's delta-equivalent; the tenor limit
  runs to its expiry. New report `OPTION_GREEKS` (delta, gamma and vega by underlying and expiry).

## [0.12.0] - 2026-10-13

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
- **Futures delivery:** a margined future is invoiced and delivered at the final settlement price (the
  settlement price of its last trading day), since the margin has paid the rest; the settlement item waits for that
  price when it is not stored yet.
- **Clearing screens:** a Clearing page (accounts, daily margin with each run's lines, statement import and
  statements, breaks with a detail page, margin calls) with key figures for the margin held, the cash at the member
  and what is open; inbox tasks link to the break or call. A clearing tour in the demo guide, and a manual page.
- **Variation margin in the ledger:** realised result on futures (3600) against cash at the clearing member (1960),
  posted daily; a corrected day posts only the difference.

### Changed
- **Period end:** futures margined through a clearing account are no longer in the period-end MtM, since their
  result is already realised by the margin.
- **Hedge accounting:** futures cannot be designated as cash flow hedging instruments; the instrument picker offers
  only forwards of the hedged allowance product, and allowance hedges keep no production year. The month-end tour
  shows the hedges.

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

[Unreleased]: https://github.com/Pianonurmi/goo-ets-etrm/compare/v0.14.0...HEAD
[0.14.0]: https://github.com/Pianonurmi/goo-ets-etrm/releases/tag/v0.14.0
[0.13.2]: https://github.com/Pianonurmi/goo-ets-etrm/releases/tag/v0.13.2
[0.13.1]: https://github.com/Pianonurmi/goo-ets-etrm/releases/tag/v0.13.1
[0.13.0]: https://github.com/Pianonurmi/goo-ets-etrm/releases/tag/v0.13.0
[0.12.0]: https://github.com/Pianonurmi/goo-ets-etrm/releases/tag/v0.12.0
[0.11.0]: https://github.com/Pianonurmi/goo-ets-etrm/releases/tag/v0.11.0
[0.10.0]: https://github.com/Pianonurmi/goo-ets-etrm/releases/tag/v0.10.0
[0.9.0]: https://github.com/Pianonurmi/goo-ets-etrm/releases/tag/v0.9.0
