# Increment 11: valuation depth

**Status: delivered** (M0–M6, release 0.11.0). What differs from the plan is under [Delivered](#delivered); the decisions are in ADR 0044 (discounting) and ADR 0045 (hedge accounting), with amendments to ADRs 0019 and 0021.

## Context

Valuation has been undiscounted since increment 3 (ADR 0019): `MtM = q × (market − contract)`, and time decay is
hidden inside PRICE. There are no interest rate curves at all; `InstrumentKind.RATE` is declared but unused. Forward
curves interpolate linearly between pillars and stay flat beyond the last one. Hedge accounting is not modelled:
ADR 0021 offers `POST_OCI` per book type, and account 2950 "Hedging reserve (OCI)" exists, but nothing designates a
hedge, measures its effectiveness or recycles the reserve.

| Open | Effect today |
|---|---|
| No discounting | MtM, credit exposure, the ledger's MtM and EMIR valuations overstate long-dated positions; IFRS 13 fair value needs the time value of money |
| No TIME or RATES in the explain | A day's passage and a rate move both land in PRICE |
| Linear, flat forward curves | Between futures and beyond the last one, the EUA forward ignores cost of carry |
| No hedge accounting | A compliance buyer hedging forecast EUA purchases takes the hedge's MtM through P&L, or all of it through OCI by book type, with no effectiveness test or recycling |

## Findings that shape the plan

- **Delivery schedules already carry a payment date** (`DeliveryScheduleView.paymentDate`), so discounting needs no
  trade change; without one, the delivery date is the payment date.
- **Futures are margined daily.** Their MtM is paid out now and is not discounted (`TradeLegView.futuresContractCode`).
- **One discount curve per currency** keeps the lookup simple: valuation discounts in the contract currency.
- **Rates may be negative.** Quote and curve point positivity becomes a per-kind check (a trigger), not a column check.
- **Seasonality stays out.** GoO curves are per production year, and allowances carry no seasonal shape; monthly
  shaping needs monthly-delivery data the system does not have.
- **Ledger already depends on valuation, market data, trade and workflow**, so hedge accounting fits in the ledger
  module without new module dependencies.

## Milestones

| Milestone | Content |
|---|---|
| M0 | Discount curves (market data): `CurveKind.DISCOUNT` with a day count, zero rates by tenor from `RATE` instruments, `DiscountFactors` in decimals (simple within a year, annual compounding with a simple stub beyond), sign-off as for other curves, `GET /curves/discount-factor`; rate quotes may be negative and are held as suspect on a move in basis points; demo EUR and SEK curves |
| M1 | Discounted MtM (ADR 0044): each schedule discounted to its payment date in the contract currency, futures not; results keep pay date, factor, curve snapshot and the undiscounted value; the run's inputs list the discount snapshots |
| M2 | TIME and RATES in the P&L explain: the old discount curve rolled to the new date, then the curve's move; the components still add up exactly (property test) |
| M3 | Curve smoothing: `CARRY` interpolation for allowance forwards (implied carry rate between pillars and beyond the last), recorded with the snapshot |
| M4 | Hedge designation and effectiveness (ADR 0045): cash flow hedges of forecast purchases and sales, designated under four eyes with hedge documentation issued as a PDF, measured on every official run with a hypothetical derivative (lower-of test, dollar offset) |
| M5 | Hedge accounting at period end: effective portion to the hedging reserve, ineffective to P&L, reclassification when the forecast transaction occurs, discontinuation under four eyes |
| M6 | Screens, demo, help, documentation; release 0.11.0 |

## Delivered

- **Seasonality is left out.** GoO curves are per production year and allowances have no seasonal shape; there is no
  monthly-delivery data to shape them with (ADR 0044, addendum).
- **Carry only beyond the last future.** Between pillars, linear in days already is a constant carry rate under simple
  compounding, so `CARRY` changes only the extrapolation.
- **Reclassification by construction.** Hedging instruments are physically settled fixed-price forwards: on delivery
  the allowances are booked at the hedged contract price, and the reserve, reversed each month like all unrealised
  MtM, leaves nothing to recycle. Cash-settled instruments are not designable (ADR 0045).
- **No backdating.** A hedge is measured from its approval date on. The demo therefore has one active hedge with its
  documentation and one designation in the inbox, and shows measurements once a visitor signs off a new valuation.
- **Hedge period end** posts the designated part of each hedging trade through its hedge (event `PeriodEndHedge`) and
  leaves the rest of the trade to its book type.
- **Open, small:** no guided-tour step for hedges; the hedge form offers a production year only for GoO; its
  instrument picker filters trades by product family and side, and the server's PRODUCT check compares the code.

## Conventions that apply

- No floating point: discount factors in `BigDecimal` (DECIMAL128), ten places.
- Rates are read only through `marketdata.api.DiscountFactors`; nothing else stores them.
- Designations, discontinuations and curve snapshots need a second person (`TaskHandler`, service check, database check).
- Period-end postings are the difference between what is required and what is booked, so a re-run is idempotent.

## Verification

- Unit: discount factors against hand-computed values; explain property test with TIME and RATES; carry
  interpolation; lower-of effectiveness.
- Integration: `CurveServiceIT` (discount curve built, signed off and read), `ValuationServiceIT` (discounted results,
  futures not, explain figures), `HedgeAccountingIT`, `MonthEndIT` and `GoldenPathIT`.
- Playwright: discount curve sign-off, explain with TIME and RATES, a hedge designated by one person and approved by
  another.
