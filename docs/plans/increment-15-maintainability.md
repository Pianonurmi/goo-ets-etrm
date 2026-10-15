# Increment 15: maintainability

**Status: in progress.** Follows [increment 14](increment-14-production-readiness.md) (0.14.0).

## Context

Increment 14 closed what an auditor or an operator would refuse in the
[enterprise readiness review](../reviews/2026-10-enterprise-readiness.md). The maintainability findings were held
back on purpose:

- C1: six services carry four or five responsibilities each, and the linter is tuned not to see them.
- C2: the module tests wire services by hand and bypass Spring.
- C5: state-dependent fields are nullable and contracts use primitives.
- C6: the same cross-cutting rules are written out many times.
- C7: the clock is bypassed in repositories and event metadata.
- C10: the build regenerates jOOQ on every run, and the demo seeder ships in the jar.
- W8: the web role table is maintained by hand.
- W9: large pages, and no client error telemetry.

Increment 14 also handed over a list of smaller defects it found on the way. Finding ids refer to the review.

What the code looks like at the start (0.14.0):

| Measure | Value |
|---|---|
| Lines of the six large services | `RiskService` 886, `TradeService` 725, `ValuationService` 651, `PeriodEndService` 627, `AllocationService` 562, `RegistryInstructionService` 519 |
| Optional ports defaulting to `null` in production constructors | 19, in allocation, ledger, reporting, risk, settlement and valuation |
| Module tests with a Spring context | 0 (every savepoint path is covered only from `apps/api`) |
| Hand-wired test setups with `@Suppress("LongMethod")` | 8 |
| Hand-written migration lists in tests | 36 files, in an order that differs from `ModularMigrations.DEFAULT_ORDER` |
| detekt | `LongParameterList` ignores `@Service`; `TooManyFunctions` at 40; `LargeClass` at 800 lines |

## Decisions

- **Refactoring milestones change no behaviour.** M0, M1 and most of M2 keep the REST contract and the OpenAPI
  snapshot as they are. The full suite, `GoldenPathIT` and `MonthEndIT` are the safety net, and M0 lands first so the
  splits in M1 run under Spring.
- **Defects first.** M6 (the handed-over defects) changes behaviour and goes right after this plan. Every later
  milestone then refactors code that is already correct.
- **Order of work:** plan, M6, M0, M3, M2, M1, M4, M5, M7, M8. The numbering stays as in the outline.
- **One pull request per milestone** (or per part, where a milestone is large), merged when CI is green.

## Milestones

| Milestone | Content |
|---|---|
| M0 | Test seams (C2), detailed below |
| M1 | Large services split (C1), detailed below |
| M2 | Module APIs and shared rules (C5, C6), detailed below |
| M3 | Time and data (C7, S7 rest), detailed below |
| M4 | Build and supply chain (C10, S8 rest), detailed below |
| M5 | Web (W8, W9, W4 and W6 rest), detailed below |
| M6 | Handed over by increment 14, detailed below |
| M7 | Operations, detailed below |
| M8 | Close: arc42 sections 5 and 8; a status line per finding in the review; the roadmap row; release 0.15.0 |

### M0: test seams (C2)

- **One module order.**
  - `ModularMigrations.DEFAULT_ORDER` moves to `platform` (`ModuleOrder`).
  - `TestDatabase.migrate` sorts the modules it is given by that order and refuses an unknown name.
  - The application and the tests then migrate in one order.
- **Non-null ports.**
  - The 19 `? = null` ports in production constructors become required.
  - The tests take fakes from a per-module `testFixtures` source set (for example `ClearingQuery` with no accounts).
  - A missing bean then fails at startup instead of silently changing behaviour.
- **Wiring factories.**
  - Each module that other modules' tests wire gets a `testFixtures` factory, for example a ledger with its default rules.
  - The eight hand-wired setups become calls to these factories, and their `@Suppress("LongMethod")` goes.
- **Spring slices.**
  - An `@ApplicationModuleTest` per module, under `apps/api/src/test/kotlin/com/pianonurmi/etrm/<module>/`, for every path that catches another service's `DomainException`.
  - The risk pre-trade and concentration checks are covered first, then the allocation, settlement, ledger and clearing listeners.
  - Each slice test fails when its `Savepoints.attempt` is removed.

### M1: large services split (C1)

Each service is split by responsibility behind the same module API; the REST layer and the other modules see no
change.

| Service | Becomes |
|---|---|
| `RiskService` | <ul><li>`RiskLimitService`: lifecycle and four eyes</li><li>`LimitMeasurement`, with a `LimitMeasurer` per limit type instead of one `when`</li><li>`CreditExposureService`</li><li>`PreTradeChecks`</li><li>`RiskQueries`</li></ul> |
| `PeriodEndService` | <ul><li>`PeriodEndStep` implementations (accruals, NRV, FX revaluation, MtM, hedge reserve, provisions) run by `PeriodEndRun`</li><li>`AccountingPolicyService` for the policy change requests</li></ul> |
| `TradeService` | <ul><li>`TradeCapture` (with `IntercompanyMirror`)</li><li>`TradeAmendment` (amend and cancel, mirror included)</li><li>`TradeLifecycle` (approve, reject, confirm)</li><li>`TradeQueries`</li></ul> |
| `ValuationService` | <ul><li>`ValuationRunService` (submit, approve)</li><li>`TradeValuer` (pricing per instrument)</li><li>`PnlExplainService`</li><li>`ValuationQueries`</li></ul> |
| `AllocationService` | <ul><li>`PlanLifecycle` (propose, approve, reject, cancel)</li><li>`WhatIfService`</li><li>`AllocationQueries`</li></ul> |
| `RegistryInstructionService` | <ul><li>`InstructionCommands`</li><li>`InstructionOutcomes` (confirm, fail, unknown)</li><li>`InstructionQueries`</li></ul> |

After the split, `config/detekt/detekt.yml` is tightened:
- `LongParameterList` no longer ignores `@Service`, with `constructorThreshold` 10.
- `TooManyFunctions` drops to 25.
- `LargeClass` drops to 500 lines.

Any remaining exception is a `@Suppress` with a reason at the class, not a rule switched off.

### M2: module APIs and shared rules (C5, C6)

**Shared rules in `platform`:**
- `SecondPerson.require(requester, approver)` replaces the requester = approver comparisons at 22 sites.
- `Roles.OPERATIONS` stands for MIDDLE_OFFICE plus BACK_OFFICE.
- One cursor codec and page helper (`Page`, `Cursor`) replaces the three codecs, with one error contract (422).
- Keyset paging comes to invoices, payments and valuation results, with a "load more" in their grids.

**API types:**
- `currency` is a `Currency` in the api data classes (the JSON stays a string).
- `period` is a `YearMonth`.
- Sealed scope types per risk limit type replace the nullable fields of `RiskLimitView`.
- `UnsafeCallOnNullableType` is on, with a baseline that only shrinks.

**Ledger:** the 16 hard-coded GL accounts in period end are resolved through posting rule roles (ADR 0008).

**Contract:** the JSON contract stays the same; the OpenAPI snapshot changes only where a type becomes more precise.

### M3: time and data (C7, S7 rest)

**The clock everywhere:**
- Repositories take the time from the caller or the injected clock, never `Instant.now()`. Today 20 sites write `updated_at`, `approved_at`, `fixed_at` and similar.
- `EventMetadata.occurredAt` comes from the publishing service's clock.
- `TimeSourceTest` (ADR 0050) covers test sources too: no `now()` outside `TestClock`.
- `InvoiceService` dates in the entity's time zone.
- `Specificity` weights become scaled integers.

**Beneficiary data:**
- Beneficiary names leave the hash-chained payloads: chained rows carry a reference to a `beneficiary` table that may be erased.
- Chain verification can start from a signed checkpoint, so partitions past their retention can be archived and dropped ([data protection](../operations/data-protection.md)).

### M4: build and supply chain (C10, S8 rest)

- **jOOQ codegen** declares its inputs (the migration folders) and outputs, so an unchanged build is up to date.
- **The configuration cache** is on, or a recorded reason says why not.
- **The demo seeder** moves into its own Gradle module, added to the boot jar only for the image that serves the showcase, through a build property.
- **Gradle dependency locking** with `verification-metadata.xml`, and a workflow that refreshes both on Dependabot branches.
- **The Gradle wrapper** is pinned by its distribution checksum ([supply chain](../operations/supply-chain.md#pinning)).
- **Dependabot npm lock files:** its updates leave out vitest's optional peer `msw`, so `npm ci` refuses them (#39, #47). The same workflow regenerates the npm lock file on Dependabot branches.

### M5: web (W8, W9, W4 and W6 rest)

- **Roles in the OpenAPI document:** each operation's roles are published as `x-roles` through an `OperationCustomizer` that reads the guards. `permissions.ts` is generated from them and checked in `check`.
- **Large pages split:**
  - one file per tab or drawer;
  - queries in `hooks.ts`;
  - one shared `useUrlState` instead of ten copies;
  - the remaining direct `formatMoney` calls go through `Money`.
- **Client error telemetry:**
  - an `onerror` and `unhandledrejection` sink posting to `/api/v1/client-errors` with the last `X-Trace-Id`, rate-limited and counted;
  - hidden source maps kept as a CI artifact.
- **Coverage:** Vitest coverage over the whole tree with a ratchet (W4 rest).

### M6: handed over by increment 14

Each item gets a test that fails before the fix.

1. **A confirmation reaches a mirror that was pending approval.**
   - Today, confirming the original confirms its intercompany mirror only when the mirror is already approved; a mirror approved later stays unconfirmed.
   - Approving a mirror whose original is confirmed now confirms it with the original's reference.
2. **Account changes on amendment reach the mirror.**
   - The mirror's counterparty account follows a changed delivery account of the original, and its delivery account follows a changed counterparty account.
   - An unchanged account keeps the mirror's own value.
3. **An MtM result that is not OK marks the credit exposure incomplete.**
   - Today a result with `MISSING_PRICE` or `MISSING_FX` is dropped silently from the counterparty's MtM.
   - It now adds a reason naming the trade and status, as a missing rate does.
4. **The month average is retried when the month closes.**
   - Items waiting for a month-average fixing are re-evaluated only when quotes are imported, so the last day's import before the month ends leaves them unpriced.
   - A daily job (`@SchedulerLock`, business time zone) re-evaluates unpriced items that are waiting for attention.
5. **The margin cascade stops with a clear status on a missing price.**
   - Today, recomputing a day re-runs every later day, and a later day without a settlement price fails the whole command, including the correction of the day asked for.
   - The requested day now commits. The cascade stops at the first day it cannot compute; that run and the later ones are marked stale with the reason, and the screen shows the mark until they are run again.
6. **The registry client's open-circuit flag stays out.** The parked-publication metrics from increment 14 have not run for a quarter yet, so there is no evidence it is needed. The decision is recorded in O7's status line and revisited after a quarter in production.

### M7: operations

- **CloudNativePG** moves to the Barman Cloud plugin before operator 1.31; the chart and `ops/k8s/postgres-cluster.yaml` use the plugin's `ObjectStore`.
- **The PITR drill** runs against the plugin.
- **Load tests:** the run against the NFR table on a dedicated environment (`stress` and the batch scenario included, [load testing](../operations/load-testing.md)) needs that environment from the owner. Until then the CI smoke profile stays.

## Outside the code

The UAT period with real users after 0.14.0 feeds this increment's priorities. The external penetration test, the
accounting, VAT and PFE sign-offs and the registry, GL and EMIR specifications stay with the owner (#16–#21).

## Verification

**Every pull request:**
- `./gradlew build` and `./gradlew :apps:web:check`;
- `ktlintFormat detekt`;
- the OpenAPI snapshot, when a controller changes;
- Playwright against the jar, for UI changes;
- green CI before merge.

**Refactoring milestones (M0, M1, M2):**
- no change to the OpenAPI snapshot unless the milestone names one;
- `GoldenPathIT`, `MonthEndIT` and the demo seeder test pass unchanged.

**M0:** each new Spring slice test fails when the `Savepoints.attempt` it covers is removed (checked once by hand,
noted in the PR).

**M6:** each new test fails on `main` before its fix.
