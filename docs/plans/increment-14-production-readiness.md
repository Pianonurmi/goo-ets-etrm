# Increment 14: production readiness

**Status: delivered in 0.14.0.** Preceded by two patch releases on the 0.13 line (0.13.1 security, 0.13.2 accounting).

## Context

The [enterprise readiness review](../reviews/2026-10-enterprise-readiness.md) of October 2026 found the system not yet
fit for business-critical use. It confirmed an authentication bypass through percent-encoded paths (S1), found
endpoints without role or entity checks (S2–S5), three accounting defects that put wrong figures in the books
(F1–F3), tamper evidence weaker than the documents claim (D1–D4, S6), failures that pass silently in jobs and
postings (O2, F4, C3), operations gaps (O1–O9) and no change control (P1). The public showcase is live, so S1 is
exposed today.

This plan acts on the review in three steps: patch releases for what is exposed or wrong now, this increment for the
rest of what an auditor or an operator would refuse, and increment 15 for the maintainability work. Finding ids (S,
D, O, F, P, C, W) refer to the review.

## Decisions

- **Patches first.** The critical defects ship as patches on a `release/0.13` branch from `v0.13.0`
  ([releases](../operations/releases.md#cutting-a-release), "a patch to an older line"), each carried forward to
  `main`.
- **The showcase is redeployed the same day** as 0.13.1, and the changelog states the exposure.
- **Pull requests from now on.** Every change goes through a branch and a pull request, merged only when CI is green.
  Branch protection is applied in the repository settings by the owner (M0 documents the ruleset).
- **Maintainability waits for increment 15**, except where a fix here needs it.

## Patch 0.13.1: security (same day)

| Item | Change |
|---|---|
| S1 path bypass | `SecurityConfiguration.SPA_ROUTES` and `SpaConfiguration.isServerPath` decide on the decoded, normalised path (`servletPath` + `pathInfo`), not `requestURI`. Server prefixes (`/api`, `/actuator`, `/v3`, `/swagger-ui`, `/error`) are matched before the shell and need authentication, except the existing permits. The showcase chain shares the fix. |
| No SYSTEM fallback | `SpringSecurityActorProvider.current()` answers a web request without a principal with a new `Actor.ANONYMOUS` (no roles, no entities), never `Actor.SYSTEM`; jobs and listeners outside a request keep SYSTEM. |
| S3 (part) | `parseRole` refuses `SYSTEM` from a token. |
| S4 (part) | `POST /api/v1/inventory/jobs/expire` needs BACK_OFFICE or REGISTRY_OPERATOR with all entities and refuses a future date. |
| Tests | `EncodedPathIT`: every path of the OpenAPI document, plain and percent-encoded, answers 401 without credentials in the secured and the showcase chain. `KafkaExternalizationIT` skips without Docker. |
| Release | Changelog Security section naming the exposure; redeploy `ops/demo` on `0.13.1`; the review's Appendix B probes against the demo. |

## Patch 0.13.2: accounting (within days)

| Item | Change |
|---|---|
| F1 index-priced revenue | The ledger listener for `AllocationLineConfirmed` and the period end's firm sale price resolve the delivered price as `SettlementItemService.evaluate` does (fixing through `IndexFixings` plus the spread, final settlement for futures). When it cannot be resolved, the proceeds are not posted rather than posted at the spread. |
| F2 FX revaluation | Realised FX is posted with the `revaluedCurrency` dimension, and `fxRevaluation` counts those lines in what is booked. |
| F3 allowance corrections | A positive `CORRECTION` adds a cost layer (zero, or the break's statement price), a negative one consumes layers; the difference goes to a reconciliation-difference account in the default rules. |
| Tests | Period end after an invoice paid at another rate posts nothing on 3910/4910; an INDEX GoO sale delivered through allocation posts (fixing + spread) × MWh and NRV uses that price; CORRECTION then SURRENDER consumes layers. Each fails on `v0.13.0`. |

## Milestones

| Milestone | Content |
|---|---|
| M0 | Process (P1, P3, P4): this plan and the roadmap row; `CODEOWNERS` (migrations, `ops/`, the OpenAPI snapshot, security configuration), a pull request template, `docs/contributing.md` with CLAUDE.md pointing at it, `SECURITY.md`; `permissions: contents: read` on every workflow; the branch-protection ruleset written down for the owner to apply; one issue per known gap in the roadmap; the stale Dependabot pull requests closed or merged |
| M1 | Authorisation (S2–S5, S9): audience validation as a required Helm value; ADMIN without business rights and not a workflow candidate; the remaining scope gaps (audit trail by entity, bank accounts and reference history, ledger entry, free allocation, change request, valuation submit, books and registry accounts, forecasts, `holderId` in `EntityScopeInterceptor`); `OperationGuardsIT` over all operations as a minimal-role, single-entity actor against a reviewed allow-list; `SecuredChainIT` on a real decoder (bad signature, expired, wrong audience, `alg=none`); `ProfileGuard` refuses `test`; actuator on the shared port for ADMIN only; problem details without internals; unknown request properties refused; the throttle map bounded; idempotency keys validated |
| M2 | Integrity (D1–D4, D8, D9, S6): separate owner and runtime database roles; the ledger trigger refuses everything but POSTED→REVERSED, and a chain v2 covers the header and base amounts (old entries keep verifying under v1); trigger and per-holding chain on `allowance_movement`; the reversal race closed; a closed-period insert guard and a deferred balance check; append-only version tables and an instruction event table; a chained audit trail with the actor set per transaction, the correlation id from the trace id, and sensitive reads recorded; a nightly chain verification job with a gauge; a daily signed digest of the chain heads |
| M3 | Money seams (D5–D7, F4–F11, C3): savepoints at every site that catches another service's `DomainException` and around every ledger listener; lot expiry one lot per transaction; one rounding rule and entries balanced in base currency; manual lines of more than two decimals refused; an unposted-event record with an attention task and a replay job; later-day margin recomputation; the settlement seams (credit-note refunds and netting, netting against current amounts, FX on netting and credit notes, month averages only after month end, whole-token remittance matching); exposure with a missing rate flagged; intercompany amendments and cancellations propagated; whole-unit average-cost layers; the smaller items of F11; a `NOT_MEASURED` limit status |
| M4 | Observability (O2, O3, O7, O8): a job-failure counter; a `PrometheusRule` in the chart and a Grafana dashboard; a metrics catalogue; readiness without the database, probe timeouts, a request bulkhead and a short pool timeout; one HTTP client per registry with TLS and proxy settings and an open-circuit flag; event publications with an attempt count and a parked state; runbooks linked from the alerts |
| M5 | Deployment and recovery (O1, O4–O6, D10, S7): migrations in a pre-upgrade job as the owner role; an expand/contract ADR and a CI job running the previous release against the current schema; scheduled jobs awaited at shutdown; chart values for Kafka security, configuration, registry secrets, the ECB feed, egress, ingress timeouts and `/tmp`; personal data out of events and chained rows, with a retention and erasure procedure; a CloudNativePG recovery drill with measured RPO and RTO and a reconciliation list per integration |
| M6 | Gates (S8, C8, C9, O9): dependency locking and verification; actions and images pinned by digest; Trivy failing on high with a fix; the OWASP job failing without its key; Kover at 90 % lines with a branch rule; a ledger balance property test, unseeded nightly property runs, concurrency tests around every row lock, a test clock; load scenarios for period end, valuation and exports |
| M7 | Web (W1–W7, W10): silent renew handled and tested; the token-store decision recorded in ADR 0026; grids open by keyboard and checked by axe; coverage over the whole tree with a ratchet and e2e journeys that create their own data; recovery from a version conflict; truncation notices and cursor paging for the long lists; the user's locale, status labels through the catalogue, instants in the entity's time zone |
| M8 | Close: arc42 brought to increment 13 with the module diagram generated by `ModularityTests`; ADRs for the test strategy, the time source and the version policy; a status per finding in the review; user guide and help for changed screens; release 0.14.0 |

## Delivered

Every milestone landed through its own pull request with green CI (#24–#43). What each finding came to is in the
review's [status table](../reviews/2026-10-enterprise-readiness.md#status-after-0140). Differences from the plan:

- **Not done, moved to increment 15:** Gradle dependency locking and verification metadata (Dependabot does not
  maintain them); beneficiary data out of hash-chained rows; Vitest coverage over the whole tree with a ratchet
  (W4); cursor paging for the long lists (W6 rest); the registry client's open-circuit flag; concurrency tests for
  the row locks beyond plan approval and netting; the dedicated-environment load run.
- **Found on the way and fixed:** a lot reserved outside a plan made period end fail (found by the new load
  scenario); an unreadable registry answer escaped the gateway's classification (found by a CI flake).
- **Found on the way, handed to increment 15:** listed in its plan, milestone M6.

## Increment 15 (outline)

Outlined in [increment 15](increment-15-maintainability.md).

## Outside the code

The owner applies the branch-protection ruleset and names a second maintainer. The external penetration test is
booked after M1 and M2. A UAT period with real users follows 0.14.0. The accounting, VAT and PFE sign-offs and the
registry, GL and EMIR specifications are tracked as issues from M0.

## Verification

- Every pull request: `./gradlew build`, `./gradlew :apps:web:check`, `ktlintFormat detekt`, the OpenAPI snapshot
  when a controller changes, Playwright against the jar for UI or guard changes, green CI before merge.
- 0.13.1: the encoded-path probes answer 401 against the jar in both chains and against the live demo after the
  redeploy.
- 0.13.2: the three new tests fail on `v0.13.0` and pass on the patch.
- Increment 14: each milestone's tests above; `OperationGuardsIT` and the immutability tests stay as regression
  gates; the recovery drill and the previous-release job run green in CI; the alert rules render and pass kubeconform.
