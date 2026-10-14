# Enterprise readiness review, October 2026

A housekeeping review of the whole repository at version 0.14.0-SNAPSHOT (commit `6debcde`, 2026-10-14), asking one
question: does the system, as built, meet what enterprise, business-critical software must offer in stability,
robustness, maintainability, security and reliability? This page records the method, the verdict, the evidence, the
findings with their proposals, and a plan.

## Verdict

**Not yet.** The architecture, the documentation and the breadth of what is built are well above what a system of
this age usually has, and the build is green with high coverage. But the review found one confirmed authentication
bypass, an audit trail that is weaker than the documents claim, a ledger whose immutability guard covers three
columns, three accounting defects that put wrong figures in the books today (index-priced sales, FX revaluation,
allowance corrections), no process controls on changes (one author, no reviews, no branch protection, the whole code
base written in five days), and an operations story that stops at "the pod has probes". Each of these is fixable in
weeks; together they mean the system should not carry real money or real certificates until the plan at the end of
this page is done.

The short form, by quality:

| Quality | Grade | In one sentence |
|---|---|---|
| Security | D | Good design, one confirmed critical bypass, hand-written authorisation with verified holes, tokens not checked for audience |
| Robustness and data integrity | C | Excellent transaction and outbox discipline; the tamper-evidence story has gaps a determined insider walks through |
| Reliability and operations | C | Hardened chart and HA database on paper; no alerts, no job-failure signal, migrations in pod start, DR drill on the wrong path |
| Maintainability | B− | Consistent structure, strong conventions, thorough docs; six god services the linter is tuned not to see, contracts full of nullable fields, documents already drifting, one author |
| Test confidence | C+ | 94 % line coverage and real property tests on the hard maths; but module tests bypass Spring, the money seams are the least tested, concurrency and the ledger invariant are examples, and the suite has a shelf life |
| Stability | C+ | 366 backend and 367 web tests pass; but five days of life, no production exposure, no soak, performance targets missed under stress |

Grades read: A meets enterprise expectations, B meets them with known small gaps, C has gaps that must close before
production, D has a gap that must close before any exposure, E is unfit.

## Method

- Six focused read-only reviews, each over its own area (security, data integrity and transactions, code quality and
  tests, operations and reliability, web UI, financial correctness), each asked to verify claims in the code rather
  than from names, and to report evidence as `path:line`.
- The full backend build run locally on PostgreSQL 16 (`./gradlew build koverXmlReport`) and the web checks through
  Gradle (`:apps:web:check`), so the figures below are measured, not quoted.
- The assembled jar started in the secured (production) profile and probed over HTTP to confirm or refute the
  security findings marked unverified by the review.
- Repository history, GitHub branches, pull requests, issues, tags and CI runs inspected through the GitHub API.
- The architecture plan's non-functional targets (`docs/architecture-plan.md` §3) and the roadmap's own list of known
  gaps used as the yardstick.

What was not done: no penetration test, no load test on a reference machine, no Playwright run (needs Docker for the
Kafka test and a second database), no review of the registry and EMIR mappings against their specifications (they are
marked `verified = false` in the code and stay so).

## Evidence

### Size and shape

| Measure | Value |
|---|---|
| Main Kotlin | 48 984 lines in 19 modules, `platform` and `apps/api` |
| Test Kotlin | 17 281 lines, 96 test classes, 366 tests |
| Web TypeScript | 66 546 lines (including the generated API types), 80 Vitest files, 367 tests |
| REST surface | 273 paths, 323 operations, 346 schemas in `apps/api/openapi/etrm-api.json` |
| SQL migrations | 69 files, one schema per module |
| Decision records | 47 ADRs, arc42 sections 1–8, 18 user-guide pages, 12 domain notes, 8 operations pages |
| History | 152 commits, all between 2026-10-10 and 2026-10-14, one author, every commit co-authored by an AI assistant, no merge commits, no pull requests for code (13 Dependabot PRs, 4 of them still open) |
| Releases | v0.9.0 to v0.13.0, four of them on 2026-10-13 |

### Build and tests (run locally, 2026-10-14)

| Check | Result |
|---|---|
| `./gradlew build` (lint, detekt, codegen, tests, Kover rule) | Green except `KafkaExternalizationIT`, which needs Docker (not available here); 365 of 366 tests pass, none skipped |
| Backend line coverage (Kover, generated code excluded) | 94.3 % lines, 68.6 % branches; lowest modules: `events` 46 %, `platform` 81 %, `reporting` 83 % |
| Web (`npm run check`: lint, format, typecheck, Vitest with coverage, API coverage, help check, bundle budget) | Green; 80 files, 367 tests |
| CI on GitHub | Green on `main` and the working branch; a run takes 17–23 minutes; one failed run on 2026-10-13 fixed by the next push |
| Test run time | 550 s of JUnit time, 309 s of it in `apps/api` (full-context integration tests) |

The Kover gate in `build-logic` is 70 %; the measured figure is 94 %, so the gate is far below what the code already
has and does not protect it.

Test code per line of main code, by module (lowest first): `position` 0.00 (no tests of its own), `inventory`
0.13, `taxonomy` 0.15, `platform` 0.17, `settlement` 0.20, `audit` 0.20, `workflow` 0.21; highest `apps/api` 0.68,
`allocation` 0.44, `valuation` 0.43. Of the 96 test classes, 60 are integration tests against PostgreSQL and 36 are
unit tests; there are no parameterised tests and 11 property-based tests.

### Confirmed by execution

The jar started with no profile (the OIDC resource-server chain) on port 8085, with no identity provider reachable
and no token sent:

| Request | Status | Body |
|---|---|---|
| `GET /api/v1/trades` | 401 | |
| `GET /%61pi/v1/trades` | 200 | `[]` (every trade of every entity once data exists) |
| `GET /%61pi/v1/ledger/accounts?legalEntityId=…` | 200 | `[]` |
| `GET /%61ctuator/sbom` | 200 | the SBOM index |
| `GET /v3/api-docs` | 401 | |
| `GET /%76%33/api-docs` | 200 | the full OpenAPI document |
| `GET //api/v1/trades`, `/api%2Fv1/trades` | 400 | rejected by the firewall |
| `GET /./api/v1/trades`, `/x/../api/v1/trades` | 401 | normalised before matching |

See finding S1.

## Findings

Severity: **Critical** exposes data or money now; **High** must close before production; **Medium** should close in
the production-readiness increment; **Low** is housekeeping. Each finding names where it is and what to do. Items
the reviews could not verify by reading alone are marked.

### Security

**S1 [Critical, confirmed] Percent-encoded paths bypass authentication.** `SecurityConfiguration.SPA_ROUTES`
(`apps/api/.../config/SecurityConfiguration.kt:107-109`) decides "this is the web shell, permit it" on the raw
`request.requestURI`, while Spring MVC routes on the decoded path. `/%61pi/v1/trades` is therefore permitted and
reaches `TradeController`. With no authentication the actor provider falls back to `Actor.SYSTEM`
(`SecurityConfiguration.kt:180`), which sees all entities, so every GET endpoint that only checks entity scope
answers an anonymous caller with all entities' data, and the actuator (SBOM, metrics, module graph) and the OpenAPI
document are open. The showcase chain reuses the matcher (`ShowcaseSecurityConfiguration.kt:70`). Fix: match on the
decoded, normalised servlet path (`PathPatternRequestMatcher`), make the production chain require authentication
for anything the dispatcher could route to a controller, and make the actor provider refuse to answer a web request
with `SYSTEM` when there is no principal. Add a test that walks the OpenAPI document with encoded variants of every
path and expects 401.

**S2 [High] Bearer tokens are not checked for audience.** `application.yml:49-53` configures only `issuer-uri`;
there is no `audiences` value and no `OAuth2TokenValidator`. Any access token the realm mints for any other client
(including the dev realm's password-grant client `etrm-api`, `ops/keycloak/etrm-realm.json:47-51`) is a valid
credential. Fix: `spring.security.oauth2.resourceserver.jwt.audiences` as a required Helm value, plus a
`SecuredChainIT` that uses a real `NimbusJwtDecoder` with a test key for bad signature, expiry, wrong audience and
`alg=none` (today it stubs the decoder, `SecuredChainIT.kt:34-50`).

**S3 [High] `ADMIN` and `SYSTEM` are ordinary roles from the token.** `parseRole` accepts any `Role` name
(`SecurityConfiguration.kt:197`); `Actor.has` treats ADMIN as every role and `seesAllEntities` is true for SYSTEM
(`platform/.../security/Actor.kt:15-18`). An identity-provider group named `SYSTEM` grants all entities and the
SYSTEM-only operations; an ADMIN can capture, approve (as a candidate of every workflow task), post manual journals,
close periods and export the GL, with requester ≠ approver as the only brake. Fix: refuse `SYSTEM` from tokens;
make ADMIN an administrative role without business rights, or at least not a workflow candidate and not a trade or
ledger operator.

**S4 [High] Unguarded or under-scoped endpoints.** Verified by reading the controllers and services:
- `POST /api/v1/inventory/jobs/expire?asOf=…` has no role or entity check (`InventoryController.kt:149-152`,
  `LotService.kt:253-259`): any authenticated user expires every held lot of every entity.
- The audit trail read spans every entity and returns full event payloads (trades, invoices, cancellation
  beneficiaries) to five roles (`AuditController.kt:21-36`); the home page's activity feed does the same.
- Bank accounts (IBANs) and the full reference-data history, including bank-account history, are readable by every
  role across all entities (`PartyDetailsController.kt:75-79`, `ReferenceDataController.kt:174-185`).
- Single-record reads without `requireEntity`: ledger entry (`LedgerService.kt:423`), free allocation
  (`EtsComplianceService.kt:354`), change request (`ChangeRequestService.kt:140`); valuation submit checks the role
  only (`ValuationService.kt:210-213`); `createBook`, `createRegistryAccount`, `books` and `registryAccounts` check
  neither (`ReferenceDataService.kt:180-307`, `ReferenceDataController.kt:79-129`); production forecasts for any
  device with MIDDLE_OFFICE (`ProductionForecastService.kt:99-101`).
Fix each; then add the generated guard test described under S1 so the class of bug cannot return.

**S5 [High] Authorisation is entirely hand-written and sample-tested.** There is no `@PreAuthorize`, no
`@EnableMethodSecurity`; every endpoint relies on a developer remembering `requireRole`/`requireEntity`. The guard
tests (`OperationsGuardsIT`, `MasterDataGuardsIT`, `EntityScopeIT`) assert about forty hand-picked URLs out of 323
operations. S1 and S4 are what that gap looks like. Fix: a test that drives every OpenAPI operation as a
minimal-role, single-entity actor and expects 403 unless whitelisted, kept green in CI; consider a default-deny
`AuthorizationManager` keyed by operation id as a second layer.

**S6 [Medium] The audit trail is not what the documents say.** CLAUDE.md and ADR 0003 say the audit trail is
hash-chained; `audit_event` has no hash columns (`audit/V2__partition_audit_event.sql:10-23`). `reference_history.actor`
is always null because nothing sets the `etrm.actor` session variable the trigger reads
(`referencedata/V1__referencedata_schema.sql:29`). `correlationId` is never populated, and `AuditCommand.record` (for
exports, logins, reads) has no caller, so document downloads, report reads, bank-account reads and showcase logins
leave no audit row. Fix: chain `audit_event` per partition, set the actor per transaction, set the correlation id
from the trace id, and record sensitive reads; correct the documents until then.

**S7 [Medium] Personal data: beneficiaries on plaintext Kafka, no retention or erasure path.** `InstructionConfirmed`
and `InstructionFailed` carry `CancellationDetails` with beneficiary name and id and go to topic `etrm.registry`
(`apps/api/.../events/EventExternalization.kt:50-51`); the chart and `application.yml` configure no Kafka TLS or
SASL. Actor ids and beneficiary data sit in chained, immutable rows, so an erasure request cannot be honoured
without breaking a chain. The plan promised field-level protection, encryption at rest and a retention policy
(`architecture-plan.md:365`); none exists. Fix: pseudonymous actor ids with a separate mapping, beneficiary data in a
non-chained table referenced by id, stripped or pseudonymised in externalised payloads; Kafka security values in
the chart; a written retention and erasure procedure.

**S8 [Medium] Supply chain.** No Gradle dependency verification or lock files (`gradle/` holds only the catalogue
and the wrapper); actions and base images pinned by tag, not digest; Trivy fails only on critical-with-fix; the
weekly OWASP job passes green when `NVD_API_KEY` is missing (`security.yml:41-49`). Fix: `verification-metadata.xml`
with checksums and signatures, dependency locking, digest pins, fail on high-with-fix, fail the OWASP job when the
key is missing, sign images.

**S9 [Low] Smaller items.** Error responses leak internals (Jackson cause messages, `IllegalArgumentException`
text, constraint names, required-role lists; `ProblemDetailsHandler.kt:58-78`); unknown JSON properties are silently
dropped (`platform/.../json/Json.kt:19`) and `@Valid` is used in 8 controllers; the showcase login throttle grows
without bound and locks everyone after 30 requests a minute from one address (`ShowcaseFilters.kt:123-152`); the
`kubernetes` profile trusts every `Forwarded` header (`application-kubernetes.yml`); the `test` profile is as open as
`dev` but `ProfileGuard` only refuses `dev`; actuator `sbom`, `modulith`, `metrics` and `prometheus` are readable by
any authenticated user on the shared port; workflows other than `build` and `release` declare no `permissions`
block; the idempotency key flows unsanitised into a file name in the FILE gateway (`Gateways.kt:57-59`); OIDC
tokens are kept in `sessionStorage` (`apps/web/src/shared/auth/oidc.ts:24`), acceptable under the strict CSP but
worth stating as a decision.

### Data integrity and transactions

**D1 [High] One database role owns every schema and runs the application.** `ops/postgres-init.sql`,
`ops/k8s/postgres-cluster.yaml:27-30` and `ModularMigrations.kt:112` use the same `etrm` role for migrations and for
serving. The role that owns a table can `ALTER TABLE … DISABLE TRIGGER`, rewrite rows and recompute the hashes,
which the application computes client-side with a public algorithm and never anchors outside the database
(`platform/.../crypto/HashChain.kt`). Tamper evidence currently catches accidents, not an insider with the
connection string. Fix: separate owner and runtime roles (runtime gets `SELECT/INSERT/UPDATE` per table, never
ownership), run migrations from a Helm pre-upgrade job as the owner, and publish each chain head daily to write-once
storage or sign it with a key held outside the database.

**D2 [High] The ledger's immutability trigger covers three columns and the chain four fields.**
`ledger/V1__ledger_schema.sql:93` refuses an update only when `hash`, `posting_date` or `legal_entity_id` changes;
the hash covers number, date, event type and lines (`LedgerService.kt:261-268`). `period`, `status` (REVERSED back to
POSTED), `reversed_by`, `source_type`, `source_ref`, `event_id`, `rule_code`, `created_by`, `created_at` and
`currency` can all be rewritten without the trigger firing or `verifyJournal` noticing, and `period` is what
exports, the trial balance and period end query on. Fix: refuse every change except POSTED→REVERSED with
`reversed_by` set once; add the header fields and the base-currency amounts to the chain payload; one integration
test per protected table that tries an `UPDATE` through the parent and a partition.

**D3 [High] The EU ETS allowance movement journal is neither append-only nor chained.**
`inventory/V1__inventory_schema.sql:152-168` creates `allowance_movement` without the trigger `lot_event` has, and no
hash. It drives cost layers, surrender attribution and holdings reconciliation. Fix: the same trigger, a
per-holding chain, inclusion in the verify endpoint and the DR drill.

**D4 [High] Double reversal race.** `LedgerService.reverse` reads the entry, checks POSTED, then takes the entity
lock; `markReversed` has no status guard (`LedgerService.kt:153-184`, `JooqLedgerRepositories.kt:179-184`). Two
concurrent reversals both pass the check and both post a reversal. Fix: lock first and re-read; `UPDATE … WHERE
status = 'POSTED'` and check the count; a unique partial index on `reversal_of`.

**D5 [Medium] The repository's own savepoint rule is broken in three places.** `AllocationService.cancel` and
`releaseQuietly` wrap `@Transactional` calls in `runCatching` without `Savepoints.attempt`
(`AllocationService.kt:498,542-545`); `SettlementItemService.evaluate` catches `DomainException` from
`ClearingService` and `ReferenceDataService` the same way (`SettlementItemService.kt:229-252`). A thrown
`DomainException` marks the transaction rollback-only, the catch hides it, and the command fails at commit with
`UnexpectedRollbackException` after logging success. Fix: `savepoints.attempt { }` at each site and a Spring-context
test like `AllocationStaleIT` for each; a detekt or ArchUnit rule that flags `runCatching` around a call into another
module's `api`.

**D6 [Medium] Nightly lot expiry is one transaction that one lot can veto.** `LotService` is `@Transactional` and
`expireDueLots` loops over every due lot publishing `LotExpired`, which the ledger listener posts synchronously
(`LotService.kt:55,253-262`, `LedgerEventListeners.kt:69-89`). One costed lot in a currency with no FX rate within
ten days, or an unmapped posting rule, rolls back every expiry every night, silently. Fix: one transaction per lot,
park failures as a workflow task, count them in a gauge.

**D7 [Medium] Base-currency rounding and unscaled manual lines.** Base amounts are rounded per line and never
checked to balance (`LedgerService.kt:254-255`, `LedgerDomain.kt:51-60`): a foreign-currency invoice with three or
more lines can leave the trial balance a cent off. Manual journal lines are inserted unscaled while the hash uses
HALF_EVEN at scale 2 (`LedgerService.kt:121-134,270`): a line of `10.125` is stored as `10.13`, hashed as `10.12`,
`verifyJournal` flags the entry and `exportGl` refuses the period. Rounding modes differ between modules
(settlement HALF_UP, ledger proceeds HALF_UP, ledger cost HALF_EVEN, platform HALF_EVEN). Fix: one rounding rule in
`platform.money`; balance the base amounts with a residual on the last line and check them in `requireBalanced`;
reject scale > 2 in `postManual`; a test that delivered proceeds equal the invoice net.

**D8 [Medium] No database guard on closed periods or unbalanced entries.** The close trigger protects
`accounting_period` rows only; nothing on `journal_entry` insert consults the period status, and balance is checked
in the application only (`ledger/V2__period_and_memo_fixes.sql`, `LedgerDomain.kt:51-71`). With D1 a hard close is
enforceable only through the application. Fix: a `BEFORE INSERT` trigger refusing CLOSED periods and SOFT_CLOSED
unless the entry is an adjustment; a deferred constraint trigger summing lines per entry and currency.

**D9 [Medium] Version and history tables are mutable; registry instructions keep no history.** `trade_version`,
`delivery_record`, `certificate_lot_version` and `reference_history` have no append-only trigger; `registry_instruction`
is updated in place with no event table, so its attempts, errors and UNKNOWN resolutions survive only in the audit
trail (which is not chained, S6). Fix: triggers allowing only the supersede columns to change; an instruction event
table.

**D10 [Medium] Migrations are not safe for the chart's rolling update.** Several migrations copy and drop tables
(`inventory/V4`, `audit/V2`, `allocation/V2`, `position/V2`, `ledger/V3`), the chart runs two replicas with
`maxUnavailable: 0`, and there is no expand/contract rule, no N−1 compatibility check and no rollback path other
than restoring a backup (`releases.md:19-20`). Fix: an ADR with the expand/contract rule, a CI job that runs the
previous release's jar against the current migrations, and migrations from a pre-upgrade job rather than pod start.

**D11 [Low] Smaller items.** `propose` soft-reserves without the entity lock (caught later as stale,
`AllocationService.kt:115-120`); workflow `open` is check-then-insert against a partial unique index
(`WorkflowService.kt:80,105`); event republish on restart with several replicas is at-least-once to Kafka and the
contract is stated only in the DR page; PDF rendering runs inside a database transaction holding a pool connection
(`DocumentService.kt:29,40-42`); the lot journal verify sorts every partition in one query; the ledger's V3 migration
writes into the marketdata schema; manual REST deliveries without an idempotency key are not idempotent
(unverified at the controller); the DR drill verifies neither `task_event` nor `allowance_movement`; no scheduled
chain verification exists.

### Operations and reliability

**O1 [High] Migrations run inside pod start under a five-minute startup budget.** `ModularMigrations` migrates
twenty schemas in `afterPropertiesSet` before the management port binds; the startup probe allows 60 × 5 s
(`deployment.yaml:123-128`). A migration slower than that at production volume (the partition migrations copy whole
tables) is killed, rolled back and retried forever. Fix: a Helm pre-upgrade job (same image, migrations on, as the
owner role from D1), pods with `etrm.migrations.enabled=false`; document expected durations per release.

**O2 [High] Job failures are invisible and there are no alert rules.** EOD valuation, curve build, risk
measurement, margin, option expiry, position snapshot and the ECB pull each `runCatching` per entity and log at
WARN (curve build at INFO); the only application meters are six gauges; `ops/` contains no `PrometheusRule`, no
dashboard. An evening where every entity's valuation fails produces nothing a pager sees. Fix: a
`etrm.jobs.failures{job,entity}` counter in every `onFailure`; a `PrometheusRule` template in the chart for
incomplete publications, default-partition rows, dispatch lag, UNKNOWN instructions, FX age, job failures, pool
saturation and CNPG backup age; a Grafana dashboard; a metrics catalogue page (the observability page names meters
that do not exist, `observability.md:13-14`).

**O3 [High] Unbounded request concurrency, a 20-connection pool and readiness that includes the database.** Virtual
threads remove the servlet thread cap; there is no bulkhead or rate limiter; the readiness group includes `db`
(`application-kubernetes.yml:7-9`) and the probe has the default one-second timeout. Under a burst every pod queues
on Hikari, the health check needs a connection too, all pods go unready together and the ingress answers 503 while
the database is fine. The stress profile already misses the read target (p95 598 ms against 300 ms,
`load-testing.md:61`). Fix: readiness without `db`, probe timeout ≥ 3 s, a request bulkhead answering 503 with
`Retry-After`, `hikari.connection-timeout` 5 s, an alert on pending connections.

**O4 [High] The DR drill does not test the production recovery path.** `ops/dr/drill.sh` runs `pg_dump` and
`pg_restore` on seeded demo data; production recovery is a CloudNativePG bootstrap from barman object storage with
WAL replay, which no automation has run; `archive_timeout` is not set, the restore example's `externalClusters`
block is a placeholder (`disaster-recovery.md:37`), and RPO/RTO are asserted, not measured. The post-restore runbook
reconciles registries but not invoices already sent, GL exports already acknowledged, clearing statements,
confirmations issued or Kafka consumers. Fix: a drill that takes a `Backup`, bootstraps a cluster with
`recoveryTarget.targetTime` and runs the same checks; measured RPO/RTO on the page; a per-integration reconciliation
list.

**O5 [Medium] Deployments and jobs.** Graceful shutdown waits for HTTP only, so a rollout at 19:45 CET cuts the
valuation job mid-loop and leaves its lock held for up to an hour (`application.yml:6-8`, `deployment.yaml:141-145`);
there is no deployment-window guidance against the job calendar; the HPA scales on CPU of a 500 m request while
one CP-SAT solve or one nightly job uses several cores on one replica; the PDB is `minAvailable: 1` for up to six
replicas; the FILE outbox defaults to an `emptyDir` so a pack marked SENT disappears on restart. Fix:
`spring.task.scheduling.shutdown.await-termination`, a longer grace period, a request-rate HPA metric,
`maxUnavailable: 1`, persistence required for the FILE channel.

**O6 [Medium] The chart cannot carry an enterprise configuration.** No values for Kafka SASL/TLS or a credentials
secret, topics are created by the application, no config map mount, registry adapter settings and secrets only
through hand-written `extraEnv`, `ETRM_ECB_ENABLED` not a value, no per-environment values files, no egress network
policy (`networkpolicy.yaml:12-27`), no ingress timeouts while period end, valuation runs and reports are long
synchronous calls, and memory sizing for the in-memory portfolio flows is unmeasured against the 2 Gi limit. Fix:
the values listed, egress rules, proxy timeouts, async run/poll for the long operations, a measured RSS figure.

**O7 [Medium] Resilience of outbound calls and events.** A new JDK `HttpClient` per registry request, never
closed, no TLS/mTLS/proxy settings, no circuit breaker (`RegistryHttp.kt:70-75`); a permanently failing event
publication is resubmitted every ten minutes forever with no attempt count or parked state
(`EventPublicationMaintenance.kt:37-44`); the PDF renderer has no fonts beyond base-14 and no limits; report
exports keep their full content in the primary database with no retention. Fix as named in each.

**O8 [Medium] Runbooks.** Pages exist for default-partition rows, archiving and restore. Nothing covers
instructions stuck in UNKNOWN on manual channels, a failed period end, a Kafka outage, an OIDC outage, certificate
rotation, partition maintenance failing, a poison publication, a chain verification failure, lock timeouts, pool
exhaustion, a CNPG failover or secret rotation. Fix: one page per scenario with the signal, the confirming query,
the action and the verification, linked from the alert annotations.

**O9 [Medium] Load figures do not cover the day.** Reads miss the target under stress; period end, EOD valuation,
risk, reports, PDFs, dispatch, externalisation and multi-replica behaviour have no figures; the only full baseline
ran on a shared four-core developer box. Fix: a scenario per nightly job and export at production-shaped data on a
dedicated environment, recorded against the NFR table, with regression thresholds.

### Process and governance

**P1 [High] One author, no review, no protection.** 152 commits in five days by one account, every one with an AI
co-author, pushed straight to `main`, which is unprotected (GitHub reports `protected: false`); no pull requests for
code, no CODEOWNERS, no PR template, no CONTRIBUTING, no SECURITY.md, no issue tracker use (zero issues). The README
says "every change reaches `main` only after the full CI run is green", which the repository cannot show. For
business-critical software the change process is a control in its own right, and it does not exist yet. Fix: a
ruleset on `main` (required checks `chart` and `build`, one review, no force push, signed commits), CODEOWNERS for
`db/migration`, `ops/`, the OpenAPI snapshot and the security configuration, a PR template, and a second maintainer
who reviews every change touching money, security or migrations.

**P2 [High] No production exposure, no soak.** Thirteen increments, five releases and roughly 130 000 lines were
produced in five days. Nothing has run for a week under real use; no bug has been found by a user. The quality of
the tests is good, but tests written in the same hour as the code share its blind spots (S1 is the example: the
guard tests cover the paths the author thought of). Fix: a UAT period with real users and real workflows before any
production decision, the external penetration test and its retest, and a bug bar (no open High) as a release gate.

**P3 [Medium] Known gaps are listed but not tracked.** The roadmap names the penetration test, the DR drill on a
production backup, registry code lists, accounting and VAT sign-off, the GL specification, market data vendors and
the EMIR layout as open. They live in prose, with no owner, date or issue. Fix: an issue per gap with an owner and a
milestone, and the roadmap linking to them.

**P4 [Low] Dependabot.** Four Dependabot PRs are open for versions the catalogue already carries (Flyway 13.10,
jOOQ 3.21.9, Tomcat 11.0.26, PostgreSQL 42.7.14 pending), and eight were closed unmerged after manual bumps. Fix:
merge or close them, and let Dependabot's PRs be the way versions move.

### Code quality, structure and tests

Maintainability B−, test confidence C+. The structure is unusually consistent: every module follows
`api / application / adapter`, Modulith verifies the boundaries and no cycle exists, the `api` packages hold
Kotlin types only, there are no TODOs, no `lateinit` in main code, no `catch (Exception)`, no main function over 78
lines, and warnings are errors. The coverage figure is real: 94 % of lines run under tests against a real
PostgreSQL. What the review found underneath:

**C1 [High] Six application services carry four or five responsibilities each, and the linter is tuned not to
see them.** `RiskService` (805 lines, 19 constructor dependencies, 52 functions) holds the limit lifecycle, a
measurement engine dispatching eight limit types in one `when`, the pre-trade check and the queries
(`RiskService.kt:95-805`); `PeriodEndService` takes 22 dependencies and also owns policy change requests
(`PeriodEndService.kt:72-95,511-583`); `TradeService.capture` authorises, builds, checks, submits, opens obligations
and mirrors intercompany trades in one path; `ValuationService`, `AllocationService` and `RegistryInstructionService`
are the same shape. `config/detekt/detekt.yml` exempts `@Service` from `LongParameterList`, sets `TooManyFunctions` to
40 with private functions ignored, and switches off `MagicNumber`, `SwallowedException`, `TooGenericExceptionCaught`,
`UnsafeCallOnNullableType`, `WildcardImport`, `SpreadOperator`, `LoopWithTooManyJumpStatements` and
`MatchingDeclarationName`. Fix: split by responsibility (a `LimitMeasurer` per type, `PeriodEndStep`
implementations, capture separate from query), then tighten the thresholds so the split holds.

**C2 [High] Module integration tests bypass Spring.** None of the 32 module-level ITs uses a Spring context (no
`@SpringBootTest` under `modules/`); they wire the module graph by hand (`AllocationServiceIT.kt:153-215` builds
seven modules' services directly, the bus is a lambda, `Savepoints.NONE` is passed). So `@Transactional` proxies,
rollback-only marking, savepoints and listener-failure isolation are exercised only through the `apps/api`
scenarios, and exactly one test (`AllocationStaleIT`) covers the savepoint rule that D5 and F5 show is broken
elsewhere. Optional ports default to `null` in production constructors to make this wiring possible
(`RiskService.kt:110-117`, `PeriodEndService.kt:93-94`, `SettlementItemService.kt:150` and ten more), and a missing
bean then silently changes behaviour (no bucket validation, no posted margin, no excluded margined trades). Fix: a
Spring slice per module for every path that catches a `DomainException` from another service; non-null ports with
fakes supplied by test factories; per-module wiring factories in `testFixtures` so that the nine hand-wired setups
of 82–137 lines (each with `@Suppress("LongMethod")`) and the 22 hand-copied migration-order lists disappear.

**C3 [High] Silent degradation.** A GoO limit that cannot be measured is stored as zero with a note
(`RiskService.kt:385-388`); a concentration check whose figures fail returns no finding, so the trade passes
(`RiskService.kt:662-671`); an option without a valuation counts at delta ±1 (`:525-527`); every nightly job
catches everything and logs the message only (see O2). Fix: a `NOT_MEASURED` status that reports as a breach, a
WARN finding when a check could not run, exceptions logged with their stack and counted.

**C4 [High] The architecture documents are already drifting.** arc42 section 5 omits the `clearing` module and
six dependency arrows the code declares, says "every module follows the same layout" (false for `audit` and
`position`, which have no `domain`), and its narrative stops at increment 8; section 8 names a field that does not
exist (`verified` for PFE add-ons; the field is `calibrated`, `RiskApi.kt:189`) and says every command states an
expected version when five api files do. `docs/README.md` says arc42 covers increments 1 and 2. Fix: generate the
module diagram from `ModularityTests` (it already renders to `build/spring-modulith-docs`) and a doc test that
fails when the section names fewer modules than `ApplicationModules.of(...)`.

**C5 [Medium] Nullability and primitives in the contracts.** 231 `!!` in main code (valuation 33, ledger 32,
settlement 26, risk 23) because state-dependent fields are modelled as `Type?` (`RiskLimitView` carries entity,
currency, bucket and horizon as nullable for every limit type; `SettlementItemView` amounts are null until OPEN);
`currency: String` in 53 api fields against `Currency` in 2; `period: String` for `YearMonth`; `status: String` in
the position model; 31 % of api view properties nullable. Fix: sealed scope types per limit type, an
`OpenSettlementItem` type, value types in `api`, `UnsafeCallOnNullableType` on with a baseline.

**C6 [Medium] Duplicated cross-cutting logic.** `SegregationOfDutiesException` thrown from 22 sites in 20 files
with their own requester = approver comparison; `Role.MIDDLE_OFFICE, Role.BACK_OFFICE` written out 31 times; three
keyset cursor codecs with two error contracts (400 against 422); `limit + 1` paging in three repositories; 16
hard-coded GL account codes in `PeriodEndService` although ADR 0008 keeps posting rules as data. Fix: a
`SecondPerson.require` helper, a `Roles.OPERATIONS` constant, one cursor codec and page helper in `platform`,
accounts resolved by rule role.

**C7 [Medium] The clock is bypassed where audit cares.** `Instant.now()` writes `updated_at`, `approved_at`,
`fixed_at`, `agreed_at`, `settled_at` and `reconciled_at` in 20 repository sites; `LocalDate.now()` is a controller
default in five places; `EventMetadata.occurredAt` stamps every domain event with wall time even under a fixed test
clock (`platform/.../DomainEvent.kt:27`). Floating point survives in `product/domain/Specificity.kt` (weights and
scores as `Double`) feeding bucket ordering, undocumented as an exception. Fix: column defaults or clock injection
in repositories, the instant from the caller for events, scaled integer weights.

**C8 [Medium] Test design.** The suite is example-based and written in the same hour as the code by the same
author. The plan's ledger invariant ("every entry balances per currency, period totals reconcile to the export")
exists as examples, not as a property; 8 of 11 property tests carry fixed seeds, so they never explore new inputs;
`CriteriaSqlEquivalenceIT` generates depth-3 trees (the plan said five). Concurrency is tested for liveness and
invoice numbering only: no test for two approvers on one plan, two deliveries on one schedule, two re-evaluations of
one item, or two readers of the portfolio flow cache. Contract tests for the `verified = false` layouts assert the
project's own implementation (`GlInterfaceIT.kt:143`, `ApiRegistryGatewayTest`). 650 hard-coded 2025–2029 dates
sit next to ten wall-clock reads and two `Thread.sleep`s, so parts of the suite will start failing on calendar
time (`OperationsGuardsIT.kt:46-148`). `position` has no tests of its own (0 % own coverage, 89 % reached through
`apps/api`). `GoldenPathIT` is six ordered tests and `MonthEndIT` one 127-line test. `KafkaExternalizationIT` fails
rather than skips without Docker. Fix: the ledger property, unseeded nightly property runs, two-thread tests around
each `forUpdate` lock, golden files from the specifications when they arrive, one `TestClock` per scenario with a
detekt ban on `now()` in tests, `@Testcontainers(disabledWithoutDocker = true)`.

**C9 [Medium] The quality gate does not protect what exists.** Kover requires 70 % lines; the code has 94 %, with
branch coverage at 69 % overall and 32–37 % in `document` and `inventory`. No mutation testing, no `@Timeout`, no
dependency locking or verification (also S8). The stack is at the newest release on every axis (Kotlin 2.4.21,
Spring Boot 4.1.1, Java 25, jOOQ 3.21.9, Jackson 3, kotest 6) with a compatibility workaround already present
(detekt analysing as JVM 21), which over a ten-year life is an upgrade tax and needs a written version policy. Fix:
`minBound(90)` lines and a branch rule, a pitest trial on the domain packages of allocation, ledger and valuation,
locking, and an ADR on the version policy (for example N−1 majors for Spring Boot and Kotlin).

**C10 [Medium] Build ergonomics.** `generateJooq` is never up to date, so every Gradle invocation migrates 19
codegen schemas and regenerates about 83 000 lines; the configuration cache is off with no recorded reason; all test
tasks serialise on one database; CI takes a median of about 19 minutes. The demo seeder (2 400 lines, 13 of the 16
unchecked-cast suppressions) ships inside the production jar behind a profile. Fix: declare inputs and outputs on
`generateJooq`, try the configuration cache, one test database per module, the seeder as a separate source set or
module.

**C11 [Low] Smaller items.** Jackson annotations inside inter-module contracts (`allocation/api`, `product/api`)
bind the contract to a serialiser; boolean-wrapped assertions in property tests lose their operands on failure;
`Thread.sleep` of up to a second in three HTTP retry tests; missing ADRs for the test strategy, the time source,
virtual threads, the coverage level, api nullability and cursor encoding; CLAUDE.md is the de-facto handbook but is
written for an assistant, repeats itself and is not referenced from `docs/`.

Module dependency shape, for the record: `referencedata`, `product` and `workflow` are the hubs (13, 13 and 11
dependants); `ledger` depends on 13 modules, `risk` on 11, `reporting` on 10; `allocation` and `settlement` depend
on `clearing` (futures margining reaching into GoO allocation and invoicing) and `position` on `etscompliance`.
These are not wrong, but each is a cost when a module is to be extracted, and ADR 0001 promises extraction.

### Web UI

Grades: maintainability B, security B, accessibility B−, test confidence B−. The checks all pass (lint with zero
warnings under `strictTypeChecked`, typecheck, 367 Vitest tests in 45 s, API coverage 306 of 323 operations with 17
reasoned exceptions, help check, bundle within budget, `npm audit` clean, every runtime dependency in use, exact
pins and a lockfile). What holds the grades down:

**W1 [Medium] Silent token renew cannot complete through the iframe path.** `oidc.ts` sets `automaticSilentRenew`
but the only callback handler is `signinRedirectCallback` on `/auth/callback` (`apps/web/src/shared/auth/oidc.ts:23-42`);
`signinSilentCallback` is never called, so the renew iframe boots the whole SPA and fails. Renewal works today only
because the identity provider issues a refresh token for the public client; without one the session ends in a
full-page redirect and lost form state. No unit test covers `oidc.ts`. Fix: handle the silent callback (or a static
`/auth/silent.html`), decide explicitly on refresh tokens, and test both paths against a fake provider.

**W2 [Medium] Access and refresh tokens in `sessionStorage`.** `oidc.ts:24`; ADR 0026 accepted it, the strict CSP is
the only barrier, and `style-src 'unsafe-inline'` is required by Mantine. Fix: in-memory user store with silent
sign-in on reload, or a backend-for-frontend cookie session; record the decision either way.

**W3 [Medium] Grids are outside the accessibility gate and open rows only by mouse.** `hardening.spec.ts:56` excludes
`.ag-root-wrapper` from axe; 23 grids use `onRowClicked` with no keyboard handler, so a keyboard-only user cannot
open a trade from the blotter. Fix: an Enter/Space handler in `DataGrid`, identifier columns as links, and only
AG Grid's known rule ids excluded.

**W4 [Medium] Coverage and tests measure the small part.** Vitest coverage is scoped to `shared/**` and
`features/**/model/**` (2 121 statements of a 47 000-line tree, `vite.config.ts:44`); the 41 pages have 12 component
tests, and those mock the grid. The 91 Playwright journeys are good but not re-runnable on the same database, lean
on named demo rows (`payments.spec.ts:28-61`), and run with one retry in CI. Fix: coverage over `src/**` with an
honest threshold and a ratchet; msw page tests for the five heaviest pages; journeys that create their own subjects
through `e2e/api.ts`; retries off with flakes tracked.

**W5 [Medium] Optimistic-concurrency conflicts end in a notification.** A 409 on amend or reference-data edit
shows "Someone changed this in the meantime" and keeps the stale version in the form
(`notify.ts:12-23`, `TradeFormPage.tsx:175-194`), so resubmitting repeats the conflict until a reload. Fix: refetch,
show the diff against the versions, offer "reload and keep my edits".

**W6 [Medium] Large lists are capped or loaded whole without saying so.** Inventory caps at 2 000 rows with no
hint (`InventoryPage.tsx:142,236`); valuation results load whole; 89 of the contract's GETs return unpaged arrays
and only five screens page by cursor. AG Grid Community has no server-side row model. Fix: a "showing the first
2 000" notice, keyset paging on items, invoices, payments and results, `useInfiniteQuery` as the blotter does.

**W7 [Medium] English-only in substance.** `format.ts:3` fixes `en-GB` number formatting (Finnish and Swedish users
read `1,234.50`); status labels are derived from enum constants and bypass `en.json`; instants show in browser-local
time with no zone label although entities carry a time zone; 206 dynamic translation keys defeat static checking.
Fix: locale from the user, status labels through the catalogue, instants in the entity's zone with a suffix, an
unused/missing-key test.

**W8 [Medium] The role table is hand-maintained with no contract check.** `permissions.ts:8-164` mirrors about
150 service role checks; the OpenAPI document carries no role metadata; the test asserts two entries. Drift shows
up as hidden buttons or 403 notifications. Fix: publish `x-roles` from the guards through an `OperationCustomizer`
and generate or assert the table from it.

**W9 [Medium] God components and no client error telemetry.** 22 page files over 500 lines mixing queries,
mutations, state and markup (`ReferenceDataPage.tsx` 1 100 lines with 11 queries, 7 mutations and 27 state hooks;
a 578-line form function in `TradeFormPage.tsx`); only five features keep data access in a `hooks.ts`; ten copies of
the same URL-state helper. `sourcemap: false`, no `onerror`/`unhandledrejection` hook, so production errors are
minified and invisible to operations. Fix: one file per tab or drawer, queries in hooks, a shared `useUrlState`,
hidden source maps kept as an artifact, an error sink carrying `X-Trace-Id`.

**W10 [Low] Smaller items.** Network blips (`status 0`) are never retried and `refetchOnWindowFocus` is off
(`App.tsx:30-31`); `decimal()` and three `as unknown as number` casts exist because the generated types call
decimals `number`; `formatDecimal` round-trips through `Number()` and rounds above 16 digits; query invalidation
depends on the library's internal key layout; the whole AG Grid Community module set is registered (the largest
chunk, 312 of 360 kB); URL parameters are cast to enums without validation; the object URL is revoked before the
download starts; several parallel 401s each start a redirect; `/api/v1/me` is cached forever so a role change needs
a reload; `Money`/`Quantity` components are used in 17 files while `formatMoney` is called directly at 63 sites.

What is right here deserves saying: typed end to end over the committed contract, a single error model with
problem details mapped onto forms, four-eyes rules as pure tested functions behind `<Can>`, idempotency keys created
once per dialog, no money arithmetic in the browser, security headers from the server with a CSP-violation gate in
e2e, lazy routes with an enforced bundle budget, and an e2e suite that plays both personas of every approval.

### Financial correctness

The numeric core is sound (decimal everywhere, mismatched currencies and units throw, the period end measured to
post only the difference, gap-free invoice numbers under concurrency, Black-76 in decimals, allocation
property-tested for determinism). The defects sit at the seams between modules, where one module reuses another's
field with a different meaning or reads a balance another module writes in a different currency. Three of them
produce wrong figures in the books today.

**F1 [High, confirmed by reading] Index-priced GoO sales book revenue at the spread.** For an INDEX leg,
`DeliveryObligationView.price` is the spread over the fixing (`trade/api/TradeModel.kt:339-340`), but the ledger
listener posts `proceeds = price × quantity` on `AllocationLineConfirmed` (`LedgerEventListeners.kt:101`) and the
period end's NRV uses the same field as the firm sale price (`PeriodEndService.kt:282-288`). Revenue and the accrued
receivable are understated by fixing × MWh, the receivable account keeps a permanent credit when the invoice (which
prices correctly through `IndexFixings`) later clears it, and the reserved lot is written down to almost nothing at
month end. No test delivers an index-priced GoO sale through allocation. Fix: resolve the delivered price the way
`SettlementItemService.evaluate` does, post nothing and record an unposted event when it cannot be resolved, and
rename the field so a spread cannot be mistaken for a price.

**F2 [High, confirmed by reading] FX revaluation re-posts realised FX every month.** `fxRevaluation` reads the
foreign-currency lines of 1510/2210 and the earlier revaluation lines by dimension (`PeriodEndService.kt:417-429`);
realised FX on settlement is posted in base currency with no currency dimension (`SettlementPostings.kt:107-121`),
so it is invisible to both. After a SEK invoice is paid at another rate the foreign balance is zero, the base
balance is the realised difference, and every month end posts that difference again as an unrealised gain or loss
and reverses it on day one. The per-line base rounding of D7 leaves cent residuals that get the same treatment.
`PeriodEndIT` revalues a manual journal only. Fix: give realised-FX lines the `revaluedCurrency` dimension and
include them in `booked`; a test that invoice → pay at another rate → period end posts nothing.

**F3 [High] Allowance `CORRECTION` movements change holdings but not cost layers.** The ledger listener returns on
CORRECTION (`LedgerEventListeners.kt:159`) while the holding changes (`AllowanceHoldingService.kt:40-42`). A positive
correction from a reconciliation break adds units with no layer, and the next surrender or sale throws
"Insufficient cost layers" inside the registry confirmation transaction, which then cannot be recorded; a negative
one leaves orphan layers that overstate the asset accounts, the NRV test and the emission provision's cover. Fix:
insert or consume layers on CORRECTION with the difference to a reconciliation account.

**F4 [Medium] Posting fails silently.** No applicable rule, a missing amount or a negative amount posts nothing
and records nothing (`LedgerService.kt:86-87`, `LedgerDomain.kt:43-44`). With posting rules as data this is the
normal failure mode of a rule change: positions move, the books stand still, nobody is told. Fix: an
`unposted_event` row and a ledger-attention task whenever an event type with rules finds none applicable; a replay
from a job.

**F5 [Medium] Ledger listeners can fail the delivery or registry-confirmation transaction.** They run synchronously
and `fxRates.require` throws when the rate is older than ten days (`LedgerService.kt:296`,
`MarketDataService.kt:96-97`); `RegistryInstructionService.applyConfirmation` has no savepoint. A USD-priced sale
confirmed by the registry after eleven days of failed ECB pulls rolls back the confirmation, the lot transfer and
the delivery. CLAUDE.md states the opposite rule for delivery-triggered listeners. Fix: `Savepoints.attempt` around
each ledger listener with the unposted-event record of F4.

**F6 [Medium] Variation margin is not recomputed for later days.** The previous settlement price is "the latest on
or before" (`ClearingService.kt:157`) and the job computes today only (`MarginJob.kt:19-21`). When yesterday's price
arrives late, today's run used the day before yesterday's; computing yesterday by hand then books that day's move
twice. Cancelling or amending a future leaves every past day as it was. Fix: recompute every later business day of
the account when a run version or settlement quote changes (idempotent by hash, the ledger posts the difference).

**F7 [Medium] Settlement seams.** A credit note's unused amount can be neither refunded nor netted
(`PaymentService.kt:83-84,244-252,285`); netting settles against the amounts captured at preparation while a
payment can be applied in between (`PaymentService.kt:238-241,363-382`); netting and credit notes in a foreign
currency realise no FX (`SettlementPostings.kt:75,93`); a `MONTH_AVERAGE` fixing read mid-month is a partial average
and a priced item is never re-priced (`MarketDataService.kt:400-411`, `SettlementItemService.kt:277-283`); payments
auto-apply by substring match on free-text supplier references (`PaymentService.kt:240`). Fix each as named; the
tests are listed below.

**F8 [Medium] Risk treats a missing FX rate as zero.** `RiskService.kt:785-793` logs and returns zero, so
receivables, PFE notional, collateral and group usage in that currency vanish from exposure with no mark on the
utilisation. Fix: an `incomplete` flag with the currencies left out, the last measured value kept for the limit,
and the pre-trade finding saying so.

**F9 [Medium] Intercompany mirror trades diverge.** Confirmation propagates to the linked trade only when it is
already APPROVED; amend and cancel never touch it (`TradeService.kt:419-502`). One entity re-prices or cancels
while the other keeps the obligation, memo, exposure and position. Fix: propagate in the same transaction or refuse
one-sided changes.

**F10 [Medium] Weighted-average costing leaves fractional layers on a whole-unit asset.** Layers are reduced
pro rata at scale 3 (`LedgerDomain.kt:109-122`) and the provision truncates with `toLong()`
(`PeriodEndDomain.kt:79`). Fix: one running (quantity, average) layer per asset key, or whole-unit reduction by
largest remainder with a reconciliation to the account balance.

**F11 [Low] Smaller items.** Revenue, COGS, cancellations and write-offs are dated the processing day, not the
delivery or registry date (`LedgerEventListeners.kt:79,107`); a policy change to DISCLOSE_ONLY between runs leaves
the earlier MtM posting in place (`PeriodEndService.kt:183-187`); index-leg spreads cannot be negative
(`TradeService.kt:332`); a delivery date on the window's exclusive end is accepted (`TradeService.kt:324`); an index
leg with no fixing after delivery is valued at the forward with status OK (`ValuationService.kt:393,488-489`); the
`PERIOD_END_RUN` hard-close gate accepts a run older than the last posting (`GlInterfaceService.kt:61`); the grant
release reads only the credit side of 2400 (`PeriodEndService.kt:387`); jobs scheduled in Europe/Berlin date their
work in the clock's zone (UTC); the clearing run hash depends on decimal scale.

The ten tests worth writing first: period end after a foreign-currency invoice paid at another rate posts nothing;
an INDEX-priced GoO sale delivered through allocation posts (fixing + spread) × MWh; a property test that every
entry balances in base currency; CORRECTION then SURRENDER consumes layers; a late settlement price does not double
a day's margin; a credit note after full payment can be refunded and netted; netting after a partial payment is
refused; a mid-month `MONTH_AVERAGE` fixing is not used for settlement; exposure with a missing rate is flagged, not
zero; amending an intercompany trade propagates to its mirror.

## What is good

The review should not read as if nothing were right. These stood out, with the files that show them:

- A clear, reusable actor model with consistent guard helpers (`platform/.../security/Actor.kt`,
  `TradeService.authorise`, `RegistryAccess`, `SettlementAccess`, `ReconciliationAccess`) and an interceptor backstop
  for entity parameters.
- Four-eyes rules in the services and in database checks on sixteen tables, with requester ≠ approver refused in
  every owning service.
- A registry outbox done as ADR 0012 describes: short claim transaction, `FOR UPDATE SKIP LOCKED`, leases, UNKNOWN
  on timeout, bounded retries, SLAs per channel, gauges (`RegistryDispatcher.kt`, `JooqRegistryRepositories.kt`).
- Idempotency keys backed by unique constraints and checked under the aggregate lock; period end posting the
  difference under an entity lock; GL export exactly-once at the database level.
- No floating point where money is computed: `Money`/`Quantity` refuse cross-currency and cross-unit arithmetic,
  Black-76 runs in `BigDecimal`, allocation scoring in `Long` cents with overflow checks.
- Every scheduled job carries `@SchedulerLock`; partitions are created ahead from the catalogue with lock timeouts
  and a stray-row gauge; the migrations have never been edited after their creating commit.
- A hardened image and chart: non-root numeric user, read-only root file system, dropped capabilities, seccomp, no
  service-account token, separate management port, network policy, PDB, topology spread, startup probe; chart lint
  and kubeconform in CI; CloudNativePG with synchronous replication, WAL archiving and nightly backups.
- Supply-chain basics: Trivy gate, three SBOMs, Dependabot on four ecosystems, weekly ZAP baseline with reviewed
  rules, `npm ci`, checksum-verified downloads, releases from a workflow with a version check.
- Browser hardening: CSP without inline scripts, `frame-ancestors 'none'`, referrer policy, no CORS, API docs behind
  authentication, formula-defused spreadsheet exports.
- Documentation unusual for its size: 47 ADRs, arc42, domain notes, a user manual in the app, runbooks for the
  database, a penetration-test brief, and a CLAUDE.md that works as a developer handbook.
- A measured 94 % line coverage, a green build, and end-to-end scenarios from trade capture to hard close
  (`GoldenPathIT`, `MonthEndIT`) plus Playwright journeys.

## Plan

The order matters: close what exposes data first, then what an auditor or an operator would refuse, then the rest.

### Now (days)

1. S1: fix the path matcher and the SYSTEM fallback; add the encoded-path guard test; rotate nothing (no secrets
   are exposed) but assume the public showcase was readable without login and say so in the changelog.
2. S2, S3: audience validation and role parsing; a real-decoder `SecuredChainIT`.
3. S4: the expiry job, audit read, bank accounts, history and the six scope gaps.
4. F1, F2, F3: the index-priced revenue, the FX revaluation and the allowance corrections, each with the test
   named under financial correctness; then a review of every entity's books for the three patterns before any
   figure is reported.
5. P1: protect `main`, add CODEOWNERS and a PR template, and route every further change through a reviewed pull
   request, including the fixes above.

### Production-readiness increment (weeks; proposed as increment 14)

6. S5: the generated authorisation test over every OpenAPI operation.
7. D1, D2, D3, D4, D8: owner and runtime roles, pre-upgrade migration job, the ledger trigger and chain payload,
   the allowance journal, the reversal race, period and balance guards, chain heads anchored outside the database,
   a nightly chain verification job with a gauge.
8. D5, D6, D7, F4, F5: savepoints at every site, per-lot expiry, one rounding rule and base-currency balancing,
   the unposted-event record with its attention task and replay, ledger listeners that cannot fail a registry
   fact, with their tests.
9. F6–F10: later-day margin recomputation, the settlement seams (credit-note refunds, netting against current
   amounts, FX on netting and credit notes, month-average fixings, remittance matching), risk with missing rates
   flagged, intercompany propagation, whole-unit cost layers; the ten tests listed under financial correctness.
10. O2, O3: job-failure counter, `PrometheusRule` and dashboard, metrics catalogue, readiness without `db`, a
    bulkhead, pool and probe timeouts.
11. O1, D10: expand/contract ADR, N−1 compatibility job in CI, migrations out of pod start.
12. O4: the CNPG recovery drill, measured RPO/RTO, the post-restore reconciliation list.
13. S6, S7: chained audit trail, actor and correlation id populated, sensitive reads recorded; beneficiary data out
    of chained rows and out of externalised events; Kafka security values; a written retention and erasure
    procedure.
14. O5–O9: the chart values, egress policy, ingress timeouts, async long operations, runbooks, a load test on a
    dedicated environment against the NFR table.
15. S8: dependency verification and locking, digest pins, stricter scan gates.
16. Raise the Kover gate to what the code has (90 % line, and a branch rule), so the figure cannot drift down.

### Before a production decision

17. P2: a UAT period with real users; the external penetration test and retest (the brief exists); the accounting,
    VAT and PFE sign-offs and the registry, GL and EMIR specifications the roadmap already lists, each as a tracked
    issue with an owner (P3).
18. A second maintainer who has read the code, and a documented support model (who is paged, for what, with which
    runbook).

### Maintainability, alongside the above

19. C2 first, because it unlocks the rest: Spring slices per module, non-null ports, wiring factories in
    `testFixtures`, one migration order. Then C1: split the six services and tighten detekt so the split holds.
20. C8 and C9: the ledger balance property, unseeded property runs, concurrency tests around every lock, a
    `TestClock` per scenario, the coverage gate at what the code has, a mutation-testing trial.
21. C4: generated module diagrams and a doc test; the arc42 sections brought to increment 13; the four missing ADRs;
    `docs/contributing.md` with CLAUDE.md pointing at it.
22. C5, C6, C7, C10: contracts without `!!` and strings, shared four-eyes and paging helpers, the clock everywhere,
    accounts by rule role, build inputs declared, the demo seeder out of the jar.
23. W1–W9 in the web UI: the silent-renew path, the token store decision, keyboard access to grids, honest
    coverage, conflict recovery, paging and truncation notices, locale and time zones, roles published in the
    contract, pages split with telemetry.

## Appendix A: scheduled jobs

All sixteen carry `@SchedulerLock` (provider `etrm_platform.shedlock`, database time, default `lockAtMostFor` 10 min).

| Lock | Class (module) | Schedule | Zone | lockAtMostFor | On failure |
|---|---|---|---|---|---|
| registry-dispatch | `RegistryJobs.dispatch` (registry) | every 30 s | UTC | 5 min | gateway errors → UNKNOWN; others propagate |
| registry-recovery | `RegistryJobs.recover` (registry) | every 5 min | UTC | 10 min | propagates |
| event-publication-maintenance | `EventPublicationMaintenance` (api) | every 10 min | UTC | 9 min | propagates; gauge |
| partition-maintenance | `PartitionMaintenance` (api) | 02:07 | UTC | 30 min | per partition, logged; gauge |
| inventory-lot-expiry | `LotExpiryJob` (inventory) | 00:30 | UTC | 30 min | one transaction for all lots (D6) |
| etscompliance-deadlines | `ComplianceDeadlineJob` (etscompliance) | 06:15 | Europe/Berlin | 30 min | not inspected |
| risk-measure | `RiskTriggers.measureAll` (risk) | 07:30, 20:30 Mon–Fri | Europe/Berlin | 30 min | per entity, WARN |
| valuation-eod | `EodValuationJob` (valuation) | 19:45 Mon–Fri | Europe/Berlin | 1 h | per entity, WARN |
| workflow-escalation | `WorkflowEscalationJob` (workflow) | every 15 min | UTC | 10 min | not inspected |
| option-expiry | `OptionExpiryJob` (trade) | 19:00 Mon–Fri | Europe/Berlin | 30 min | per trade, WARN |
| clearing-margin | `MarginJob` (clearing) | 19:30 Mon–Fri | Europe/Berlin | 30 min | per account, WARN |
| position-daily-snapshot | `PositionSnapshotJob` (position) | 23:50 | UTC | 30 min | per entity, WARN |
| marketdata-curve-build | `CurveBuildJob` (marketdata) | 18:15 Mon–Fri | Europe/Berlin | 20 min | per curve, INFO |
| marketdata-ecb-pull | `EcbPullJob` (marketdata) | 16:30, 17:30 Mon–Fri | Europe/Berlin | 10 min | WARN; gauge |
| allocation-soft-reservation-expiry | `AllocationJobs.expireProposals` (allocation) | every 15 min | UTC | 10 min | single transaction |
| showcase-nightly-reset | `ShowcaseNightlyReset` (api, showcase only) | 04:15 | Europe/Helsinki | 5 min | exit 75 |

The evening chain (ECB pull → curve build → option expiry → margin → valuation → risk → snapshot → backup →
partitions) is ordered by wall clock, not by dependency; a late curve build values on stale curves without a record
of which snapshot was used.

## Appendix B: how to reproduce the measurements

```bash
# PostgreSQL 16 or 17 with the databases from ops/postgres-init.sql, then:
export ETRM_CODEGEN_DB_URL=jdbc:postgresql://localhost:5432/etrm_codegen
export ETRM_TEST_DB_URL=jdbc:postgresql://localhost:5432/etrm_test
export ETRM_DB_USER=etrm ETRM_DB_PASSWORD=etrm
./gradlew build koverXmlReport --continue        # KafkaExternalizationIT needs Docker
./gradlew koverXmlReport -x :apps:api:test       # the report without rerunning the Docker test
python3 - <<'EOF'
import xml.etree.ElementTree as ET
r = ET.parse('build/reports/kover/report.xml').getroot()
for c in r.findall('counter'):
    print(c.get('type'), c.get('covered'), c.get('missed'))
EOF

# The authentication bypass (S1), against the jar with no profile and no identity provider:
ETRM_DB_URL=jdbc:postgresql://localhost:5432/etrm ETRM_OIDC_ISSUER=https://idp.invalid/realms/etrm SERVER_PORT=8085 \
  java --enable-native-access=ALL-UNNAMED -jar apps/api/build/libs/api.jar &
curl -s -o /dev/null -w '%{http_code}\n' http://127.0.0.1:8085/api/v1/trades      # 401
curl -s -w '\n%{http_code}\n' 'http://127.0.0.1:8085/%61pi/v1/trades'              # [] 200
```
