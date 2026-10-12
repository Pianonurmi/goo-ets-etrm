# ADR 0040: GoO limits on the book netted through the flow

- Status: accepted (M7 of increment 8)
- Date: 2026-10-12
- Amends: ADR 0022 (risk limits)

## Context
GoO risk was a product-blind MWh sum (`POSITION_NET`).

- **It misses the product.** A book long in Nordic hydro and short in generic EU renewable nets to zero there. Yet
  the hydro is exposed to its own premium, and the generic sales will hand that premium over when the hydro delivers
  them.
- **It misses expiry.** Certificates expiring unsold are a loss that no position shows.
- **The tools now exist.** Increment 8 has the eligibility flow (ADR 0036), the product hierarchy (ADR 0037) and the
  valuation-grade allocation (ADR 0039) to measure all three.

## Decision
Three limit types, each on one legal entity as a whole:

| Type | Measures | Unit | Scope |
|---|---|---|---|
| `GOO_CONCENTRATION` | held certificates of the bucket that no obligation uses in the entity-wide flow: the book's open exposure to that product | MWh, or EUR at the bucket's current-vintage mark | `bucketCode`, required |
| `GOO_EXPIRY` | held certificates expiring within the horizon that no obligation uses | MWh | `horizonDays` (1 to 730), required; `bucketCode` optional |
| `GOO_BASIS` | sales delivered with certificates worth more than their own product on the valuation-grade allocation, counting only give-away handed over | MWh, or the give-away in EUR | none |

- **No book scope:** a GoO limit rejects a book. Certificates serve obligations across books, so the flow nets the
  whole entity and cannot be split by book.
- **Measures come from allocation** (`allocation.api.GooRiskQuery`, implemented on the prepared held flow and the
  portfolio value).
  - **Wiring:** risk gains `allocation :: api`, resolved lazily. Allocation depends on neither risk nor valuation.
  - **Failure handling:** a measure runs in a savepoint. When allocation is not available or fails, the limit reads
    zero with a note, and the other limits are measured as before.
- **Pre-trade:**
  - **Concentration:** a GoO purchase of a concentration limit's bucket adds its open quantity to the last measured
    figure (at the mark for a limit in EUR). Over the limit gives `GOO_CONCENTRATION` with REQUIRES_RISK_APPROVAL;
    over the warning level gives WARN. A sale adds nothing to concentration.
  - **Breached limits:** GoO trades are told about GoO limits already in breach (`LIMIT_IN_BREACH`, WARN).
  - **Unchanged:** available to sell stays allocation's own WARN finding (`GOO_HEADROOM`, ADR 0036).
- **Events:** `LimitBreached` carries the new types and scope fields. The additions are additive in its Kafka JSON
  (ADR 0029), and a test checks them.
- **Measuring on activation:** a limit is now measured when it takes effect. `RiskService.evaluate` took the date as
  a default argument, which, called through the transactional proxy, read the proxy's unset clock, so measuring on
  activation had always failed. It is now an overload.

## Consequences
- **Product exposure:** risk sees exposure by product where the book is actually exposed: what nothing has been sold
  against.
- **Basis is a desk choice:** a book that keeps selling generic with specific certificates shows a growing basis
  figure, so that choice becomes a limit.
- **Concentration is all vintages together.** A limit on one vintage, or on future supply (ADR 0038), is not built;
  the scope can grow.
- **Measured only as often as limits are:** on the events and schedule of ADR 0022. The figures are those of the
  prepared flow at that moment.
- **Known issue, pre-existing:** measurements triggered by another person's event (a back office delivery, say) run
  as that person. Valuation's role check can refuse them, and the limit keeps its last figure until the next
  scheduled or explicit measurement.
