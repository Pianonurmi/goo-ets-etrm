# ADR 0044: Discount curves and discounted MtM

- Status: accepted
- Date: 2026-10-13
- Amends: ADR 0019

## Context
- **MtM was undiscounted** (ADR 0019): deliveries were short-dated and discounting was left for later. EUA forwards
  now run to the next two Decembers, and fair value under IFRS 13 includes the time value of money. Credit exposure,
  the ledger's MtM posting and EMIR valuations all read the same official MtM.
- **No interest rates in the system.** `InstrumentKind.RATE` was declared and never used. Prices must come from market
  data only, so rates have to as well.
- **The explain hid time.** With undiscounted forwards, a day's passage showed in PRICE as the curve rolled.

## Decision
### Discount curves
- **A new curve kind `DISCOUNT`**, one active per currency, with a day count: ACT/360 for EUR (money market
  convention), ACT/365F otherwise, set when the curve is created.
- **Pillars** are the currency's `RATE` instruments that have a tenor (`ON`, `1M` … `10Y`). A pillar ends its tenor
  after the snapshot date; the points are zero rates in percent a year.
- **Rates may be zero or negative.** Positivity of quotes and curve points becomes a per-kind check (a trigger),
  and a rate quote is held as suspect when it moves more than `max-rate-move-bp` (50 bp) rather than a percentage.
- **Snapshots, overrides and sign-off** are the same as for price curves: four eyes, immutable, superseded by a later
  official version.

### Discount factors
- **Rates** interpolate linearly in calendar days between pillar dates and stay flat beyond.
- **Factor** for `d` days at rate `r` and basis `B`: within a year `1 / (1 + r·d/B)`; beyond, annual compounding for
  the whole years with a simple stub, `1 / ((1 + r)^n · (1 + r·f))`. Decimal arithmetic (DECIMAL128), ten places; no
  floating point. A payment on or before the date seen from is not discounted.
- **Read only through `marketdata.api.DiscountFactors`**: by currency (the latest official curve on or before the
  date) or by snapshot (seen from another date, for the explain).

### Discounted MtM
- `MtM = q × (market − contract) × DF(pay date)`. The pay date is the schedule's payment date, else its delivery date.
  DF is in the **contract currency**, where the cash flow is.
- **Futures are not discounted**: they are margined daily, so their value is paid out now.
- **Without an official discount curve** for the currency the factor is 1; nothing fails.
- **Each result keeps** its pay date, factor, discount curve and snapshot, and the undiscounted MtM. The run's inputs
  list the discount snapshots next to the price curves.
- Cheapest-to-deliver MtM (ADR 0039) uses the same factor.

### The explain
Between DELIVERED/AMENDED and FX, with `d0` and `d1` the two runs' factors and `d0r` the earlier curve seen from the
later date for the later payment date:

- **TIME** = q₁ (f₀ − k) x₀ (d0r − d0)
- **PRICE** = q₁ ((f₁ − k₁) − (f₀ − k)) x₀ d0r
- **RATES** = q₁ (f₁ − k₁) x₀ (d1 − d0r)
- **FX** = q₁ (f₁ − k₁) (x₁ − x₀) d1

They add up exactly to the change in MtM (property test with random factors).

## Consequences
- Official MtM, and so credit exposure, the period-end MtM posting and EMIR valuations, are discounted from the first
  run after a discount curve is signed off. Earlier runs are not restated; the first discounted run's explain shows the
  switch in RATES.
- Without discount curves (the tests' default, and any currency without one) everything is as before.
- Valuation reads one discount factor per currency and payment date per run.
- Rate instruments and their quotes come in as files, like prices. A vendor feed is still to come (architecture
  plan).

## Addendum: carry beyond the last future (increment 11 M3)
Forward curves were flat beyond their last pillar. Allowances carry: a forward is spot grown at the cost of carry, so a
delivery after the last listed December was undervalued. A forward curve can now be read with `CARRY`: unchanged
between pillars (linear in days is already a constant carry rate under simple compounding), and beyond the last
pillar `F_n × (1 + (F_n / F_n-1 − 1) × (T − T_n) / (T_n − T_n-1))`, the last segment's carry continued. The method is
set on the curve by middle office or risk and recorded with each snapshot, so an official price is reproducible after
it changes. Seasonal shaping stays out: GoO curves are per production year and allowances have no seasonal shape.
