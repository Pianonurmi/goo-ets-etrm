# Increment 17: finishing the review's code items

**Status: done (0.17.0).** Follows [increment 16](increment-16-review-leftovers.md) (0.16.0).

## Context

After 0.16.0 the [enterprise readiness review](../reviews/2026-10-enterprise-readiness.md#status-after-0160) has no
open finding in the code except one part of W4, and two measures from increment 16 stop short of where they should
be:

- **W4:** the clearing and hedge journeys use single-use demo rows (a clearing break, an undesignated EUA forward),
  so the whole Playwright suite does not yet run twice on one database.
- **Web coverage:** the page tests of increment 16 brought whole pages under measurement, and the floors were reset to
  59 % statements, 57 % branches, 48 % functions and 58 % lines (ADR 0053). Tabs and drawers the page tests reach
  only in passing (policies, the GL interface, payments, VAT rules, netting, accounts, books, breaches, exposures,
  devices, the entry and item drawers, posting rules, PFE) are near zero.
- **`!!`:** `detektMain` refuses a new `!!` on a nullable value, but 126 older ones sit in per-module baselines.

What remains outside the code (the penetration test, UAT, the load-test environment, the sign-offs and the external
specifications, #16–#21) is the owner's and stays as it is.

## Decisions

- **No product changes.** No REST contract, schema or screen changes beyond what a test needs.
- **No `!!` is replaced by a silent default.** Each becomes a named check (`checkNotNull` with a message, or a helper
  on the view that owns the value) that fails the same way, with a message that says what was missing.
- **Coverage floors only rise** (ADR 0053): each milestone that adds tests raises them to the measured value.
- **One pull request per milestone**, merged with green CI.

## Milestones

| Milestone | Content |
|---|---|
| M0 | This plan and the roadmap rows |
| M1 | The clearing and hedge journeys create their own subjects; the whole suite runs twice on one database (W4) |
| M2 | Web coverage: tests for the tabs and drawers near zero, floors raised toward 70 % lines |
| M3 | `!!` burn-down: every baseline entry fixed, the baselines deleted |
| M4 | Close: status per finding, roadmap, release 0.17.0 |

### M1: clearing and hedge journeys

- `e2e/api.ts` gains helpers, as in increment 16:
  - a clearing statement import for a new account record with a variation margin difference, which opens a break
    (CSV `Date,Account,Record,Contract,Position,SettlementPrice,Amount,Currency`, as
    `DemoHistory.clearingStatements` writes it), and a margin call on it;
  - an EUA forward bought, approved and designated as a hedge (`POST /api/v1/ledger/hedges`, as
    `DemoHedges.designate` does).
- The journeys assert on the subjects they made and on KPI changes measured before and after, never on demo counts.
- Check: the whole Playwright suite twice in a row on one database.

### M2: web coverage

- Tests through `renderPage` (msw, the real grid) for `Policies`, `GlInterface`, `PaymentsTab`, `VatRulesTab`,
  `NettingTab`, `AccountsTab`, `Books`, `BreachesTab`, `ExposuresTab`, `DevicesTab`, `EntryDrawer`, `RulesTab`,
  `PfeTab` and `ItemDrawer`: each renders its rows, opens its form or drawer, and sends one action with the expected
  body.
- Floors in `vite.config.ts` raised to the measured values.

### M3: `!!` burn-down

- Approval handlers read the decider through `ApprovalTaskView.decider()`.
- Views that carry optional columns get named accessors (`RiskLimitView.entity()`, `RegistryInstructionView`
  `requireHolding()` / `requireProduct()`).
- jOOQ `fetchOne()!!` becomes `fetchSingle()`; date ranges from PostgreSQL go through one helper in `platform/db`.
- Every remaining one becomes `checkNotNull(...) { message }`.
- The per-module `detekt-baseline-main.xml` files are deleted; `ops/ci/detekt-baseline-check.sh` stays for any
  future rule.

### M4: close

- The review's status table after 0.17.0, the roadmap rows, the delivered section of this plan.
- Release 0.17.0 ([releases](../operations/releases.md)).

## Verification

- Every pull request: `ktlintCheck detekt detektMain`, the touched modules' tests, and the web checks (typecheck,
  lint, format, Vitest with coverage, `coverage:api`, `check:help`, `budget`).
- M1: the whole Playwright suite twice in a row on one database.
- M3: the full backend build; no baseline file left.

## Delivered

Every milestone landed through its own pull request with green CI: M0 (#88), M1 (#92), M2 (#91), M3 (#90). What each
finding came to is in the review's [status table](../reviews/2026-10-enterprise-readiness.md#status-after-0170).

| Measure | At 0.16.0 | At 0.17.0 |
|---|---|---|
| Playwright on one database | five journeys repeatable; clearing and hedge single-use | every journey creates its subjects; the whole suite passes twice in a row |
| Web coverage (statements, branches, functions, lines) | 59 %, 57 %, 48 %, 58 % | 68 %, 63 %, 57 %, 67 % |
| `!!` on a nullable value | 126 in per-module detekt baselines | none; no baseline files |

Differences from the plan:

- **Done differently:**
  - **Coverage** reached 67 % lines, not 70 %: the tabs and drawers the plan named are covered; the rest of the
    gap is in screens with page tests already, in branches the tests do not take.
  - **Page tests** each do one action: a CI runner takes several seconds per interaction with a page that holds a grid,
    so a test that did three actions ran past the 15 s limit there.
- **Found and left alone:** after preparing a netting, the modal's navigation does not settle in the test router
  (a bare navigation in the same harness does). The test asserts the request; the browser journey covers the rest.
- **Not done, open:** outside the code, as before: the penetration test, UAT, the load-test environment, the
  sign-offs and the external specifications (#16–#21).

