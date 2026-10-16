# Increment 16: review leftovers

**Status: planned.** Follows [increment 15](increment-15-maintainability.md) (0.15.0).

## Context

Increments 14 and 15 acted on the [enterprise readiness review](../reviews/2026-10-enterprise-readiness.md). What
the review's [status table](../reviews/2026-10-enterprise-readiness.md#status-after-0150) still marks open is
technical and needs no product decision. This increment closes it:

- **D11:**
  - `propose` soft-reserves without the entity lock;
  - workflow `open` is check-then-insert;
  - PDF rendering holds a database connection;
  - the lot journal verification sorts every partition at once;
  - the ledger's V3 migration writes into another module's schema;
  - manual REST deliveries without an idempotency key are not idempotent;
  - the DR drill skips `task_event` and `allowance_movement`.
- **O5:** the HPA scales on CPU only.
- **O6:** period end, valuation runs and report exports are long synchronous calls; the memory figure for the
  in-memory portfolio flows is unmeasured.
- **O7:** the registry clients have no open-circuit flag; the PDF renderer has no fonts beyond base-14 and no limits;
  report exports keep their full content forever.
- **C8:** no two-thread tests for two deliveries on one schedule or two re-evaluations of one settlement item;
  `position` has no tests of its own.
- **C11:**
  - Jackson annotations in `allocation/api` and `product/api`;
  - `Thread.sleep` in the registry HTTP tests;
  - boolean-wrapped property assertions;
  - ADRs missing for virtual threads, the coverage level, api nullability and cursor encoding;
  - `CLAUDE.md` not referenced from `docs/`.
- **W4:** no page tests for the heaviest pages; Playwright journeys lean on named demo rows and retry once in CI.
- **W6:** settlement items are not paged.
- **Increment 15 leftover:** detekt's `UnsafeCallOnNullableType` with a baseline that only shrinks.

## Decisions

- **No product changes.** The REST contract changes only where a milestone names it: async runs (M2), a required
  idempotency key on manual deliveries (M0), items paging (M5).
- **The ledger's V3 migration stays.** An applied migration cannot change (Flyway checksums). A build test refuses
  any new migration that names another module's schema, and the exception is recorded.
- **Report retention keeps the evidence.** After the retention period an export's content is dropped. Its number,
  hash and audit event stay, so the export log still proves what was produced.
- **One pull request per milestone** (or per part), merged with green CI, as in increment 15.

## Milestones

| Milestone | Content |
|---|---|
| M0 | Races and idempotency (D11, C8) |
| M1 | Documents and reports (D11, O7) |
| M2 | Long operations run asynchronously; memory measured (O6) |
| M3 | Operations: request-rate HPA, open-circuit flag, drill and verification gaps (O5, O7, D11) |
| M4 | Tests and code hygiene (C8, C11, `UnsafeCallOnNullableType`) |
| M5 | Web: page tests, self-contained journeys, settlement items paging (W4, W6) |
| M6 | Close: status per finding, arc42, release 0.16.0 |

### M0: races and idempotency

- `PlanLifecycle.propose` takes the holder entity's lock (`plans.lockEntity`) before computing, as `approve` does.
  Two proposals for one entity then serialise, instead of the second one being caught later as stale.
- Workflow `open` inserts with `ON CONFLICT DO NOTHING` against its partial unique index, then reads the open
  task back. Two listeners opening the same task no longer surface a duplicate-key error.
- **Manual deliveries need an idempotency key over REST.** `DeliveryController` and the trade operation endpoint
  refuse a missing key with 422. The web sends one from `newKey`. Module flows keep their source keys.
- **Two-thread tests:** two deliveries on one schedule; two re-evaluations of one settlement item; two proposals
  for one entity; two opens of one task. Each test fails without its fix (checked once by hand, noted in the PR).

### M1: documents and reports

- **PDF rendering outside the transaction.** `DocumentService` renders first, then stores the bytes and hash in a
  short transaction, so no pool connection is held while a page lays out.
- **Limits.** A page count and size limit per document type, refused with a problem instead of a stuck request.
- **Fonts.** A bundled open font (with Latin Extended, for Nordic and Baltic names) replaces base-14.
- **Report retention.** `etrm.reporting.retention` (default 400 days) for export content: a scheduled job drops the
  content of older exports and keeps number, hash and audit event. The UI shows "content expired", and a re-run
  produces a new export.

### M2: long operations run asynchronously

- Period end, a valuation run and a report export become run-and-poll operations:
  - `POST` answers 202 with the run's id, and the work runs on a virtual thread;
  - `GET` returns the status and, when done, the result.
- The web polls with TanStack Query, the way it already does for allocation runs. The OpenAPI snapshot changes for
  these operations only.
- **Ingress timeouts** in the chart drop to a short default, because nothing long is synchronous any more.
- **Memory measured.** The allocation benchmark at full size (100 000 lots, 5 000 obligations) records peak heap and
  RSS for the prepared portfolio flows. The chart's default limit and `docs/operations/kubernetes.md` follow the
  figure.

### M3: operations

- **HPA on request rate.** An optional `autoscaling.requestsPerSecond` metric, through the Prometheus adapter's custom
  metric, next to CPU. Off by default; documented in `kubernetes.md`.
- **Open-circuit flag.** After `etrm.registry.circuit.failures` consecutive transport failures to one registry, its
  dispatch pauses and the registry shows "paused". An operator resumes it. It is also resumed when a probe succeeds
  after `etrm.registry.circuit.cool-down`. Unknown outcomes still never resend (ADR 0012).
- **The DR drill** verifies `task_event` and the allowance movement chain too.
- **The lot journal verification** walks partition by partition, from the archive-cut anchor, instead of one sort
  over all of them.
- **Event republish** with several replicas is at-least-once to Kafka. ADR 0029 states it, not only the DR page.
- **A migration lint** (a build test) refuses SQL that names a schema other than the module's own. Ledger V3 is
  listed as the one historical exception.

### M4: tests and code hygiene

- `position` gets its own tests, covering its query logic without `apps/api`.
- The registry HTTP tests use a test clock and a controllable stub instead of `Thread.sleep`.
- Property tests assert values (`shouldBe`), not booleans, so a failure shows its operands.
- `allocation/api` and `product/api` lose their Jackson annotations. Serialisation is configured in `platform` JSON
  (mixins), so the contracts are plain Kotlin.
- **ADRs:** 0052 virtual threads; 0053 the coverage level and ratchets; 0054 api nullability; 0055 cursor encoding.
- **Contributor handbook.** `docs/contributing.md` becomes the reference for contributors. `CLAUDE.md` keeps the
  assistant-specific rules and links to it.
- **detekt:** `UnsafeCallOnNullableType` is on with a baseline file. CI fails when the baseline grows.

### M5: web

- **Page tests with msw** for the five heaviest pages: month end, reference data, risk, ledger and trade detail.
  They render the real grid, not a mock.
- **Journeys that create their own subjects** through `e2e/api.ts` instead of named demo rows (payments,
  confirmations, reconciliation, registry, risk). The suite then runs twice on one database.
- **Playwright retries off in CI.** A flake gets an issue, not a retry.
- **Settlement items** page by keyset like invoices and payments, with "Load more".

### M6: close

- A status line per finding in the review: fixed in 0.16.0, or still open with the reason.
- arc42 sections 6 and 8 for the async runs, the circuit flag and retention.
- The roadmap row; this plan's Delivered section; release 0.16.0.

## Outside the code

These stay with the owner (issues #16–#21):

- the external penetration test, UAT, and the dedicated load-test environment;
- the accounting, VAT and PFE sign-offs;
- the registry, GL and EMIR specifications.

## Verification

**Every pull request:**
- `./gradlew build`, `./gradlew :apps:web:check` and `ktlintFormat detekt`;
- the OpenAPI snapshot, when a controller changes;
- Playwright against the jar, for UI changes;
- green CI before merge.

**By milestone:**
- **M0:** each concurrency test fails on `main` before its fix.
- **M2:** `MonthEndIT` and `GoldenPathIT` pass with the async runs, polling to completion.
- **M5:** the Playwright suite passes twice in a row on one database, with retries off.
