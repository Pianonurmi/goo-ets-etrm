# ADR 0047: Options on EUA futures

- Status: accepted
- Date: 2026-10-13
- Amends: ADR 0019, ADR 0044, ADR 0046

## Context
- **Utilities buy EUA options** on EEX to cap the price of compliance purchases (a bought call) or to finance the
  cap (a collar of a bought call and a sold put). The trade model has accepted `OPTION` as an instrument type since
  increment 1, with no option terms: such a trade was valued as a linear forward, counted in full in positions and
  PFE, and nothing exercised it.
- **EEX EUA options are European options on the December future**, cleared by ECC and premium-style: the premium is
  paid in full, the option carries no variation margin, and at expiry an option in the money is exercised into the
  future at the strike, which is then margined like any future (ADR 0046).

## Decision
### Terms
- **An option leg names** its type (call or put), strike, expiry date and underlying futures contract
  (`underlyingContractCode`). It never sets `futuresContractCode`, so the futures logic (no discounting, variation
  margin, hedge refusal, delivery at the final settlement price) does not pick it up. The leg's price is the premium
  per tonne, its quantity the tonnes of the underlying.
- **Validation follows futures:** an exchange or clearing house as counterparty; an active underlying contract on the
  leg's allowance product and currency; whole lots; expiry on or after the trade date and on or before the contract's
  last trading day; a positive strike; cleared on the contract's venue with its clearing house. Option terms are
  refused on any other instrument type.
- **No delivery schedule:** an option delivers nothing; exercise creates the future, which delivers. Positions,
  obligations, settlement items and delivery recording therefore leave option legs alone.
- **EMIR:** option type, strike, exercise style (European) and maturity (the expiry) are reported; the layout stays
  unverified (ADR 0023).

### Volatility and valuation
- **Implied volatility is market data:** quote type `VOLATILITY`, percent a year, recorded or imported on the
  underlying futures instrument (refused on any other) and checked for moves like prices. A price index never fixes on
  it.
- **Curve kind `EUA_VOLATILITY`**, one per allowance product, built, overridden and signed off under four eyes like
  every curve. Its pillars are the product's active futures still trading, dated at their last trading day; it reads
  volatility quotes only. Between pillars it is linear in total variance `σ² t` (days from the valuation date), flat
  in volatility before the first and beyond the last pillar; no smile (one volatility per expiry, whatever the strike).
- **Black-76** in decimal arithmetic (ADR 0002: no floating point): the exponential, logarithm and normal distribution
  are series evaluated at 50 digits and returned at 34. Inputs: the underlying contract's forward price from the
  allowance curve at its delivery date (in the contract currency), the volatility for the expiry, years to expiry
  ACT/365, and the discount factor to expiry from the currency's discount curve (ADR 0044). On its expiry date an
  option is worth its discounted payoff; after it, it is not valued (exercise or abandonment closes it, M3).
- **The result:** one per option leg, keyed by the leg's id and dated at expiry; market price = the option's value
  per tonne, contract price = the premium, so `MtM = q (value − premium)`. It keeps the forward, volatility, years to
  expiry, the volatility snapshot and delta, gamma and vega (per volatility point) per tonne; the run's inputs list
  the volatility curve. Without an official volatility curve the result is `MISSING_PRICE`, never a linear value.
- **The explain revalues an option one input at a time** (terms, time, underlying, volatility, rates, FX): each
  step is the change from the one before, so the steps add up to the change in value. `TIME` is time decay together
  with the discount curve's roll; `VOL` is new.

### Premium and expiry
- **The premium is paid in full** through the clearing member's cash when the option is confirmed (rule
  `OPTION_PREMIUM`, event `OptionPremium`): a bought option debits 1810 option premiums paid and credits 1960 cash at
  the clearing member; a sold one debits 1960 and credits 2810 option premiums received. An amendment of a confirmed
  option posts the change against the version it replaces; a cancellation reverses it.
- **Period end keeps options in the MtM** (they are not margined, so not in `marginedTradeIds`): premium plus MtM is
  the option's fair value, and the post-and-reverse of ADR 0021 works unchanged.
- **Expiry** (`OptionExpiryJob`, 19:00 Frankfurt on weekdays, before the margin job; ShedLock) decides every confirmed
  option leg whose expiry has come, against the underlying's official settlement price of the expiry date (none yet:
  the leg waits for the next run, nothing is guessed):
  - **in the money** (a call above the strike, a put below it) it is **exercised**: the system captures the future at
    the strike, dated the expiry date, on the option's book, counterparty and clearing house, approved and confirmed
    at once (reference `exercise of <option>`). A bought call or sold put buys the future, a bought put or sold call
    sells it. The future is then margined like any other (ADR 0046): its first variation margin, `q (S − K)`, is the
    option's intrinsic value;
  - **at or out of the money** it is **abandoned**.
- The leg keeps the outcome (exercised or abandoned, the settlement price, the future), and the option trade is
  **closed** once every leg is decided. Events `OptionExpired` per leg. Valuation leaves decided legs alone.
- **On expiry the premium is released** to 3600 realised result on futures (rule `OPTION_EXPIRY`): the realised
  result over an exercised option's life is the intrinsic value from the future's margin less the premium.
- Options are exercised automatically only; giving up an in-the-money option or exercising one at the money is not
  modelled.

## Consequences
- Options are valued and explained from M2; risk counts them only once delta-equivalents exist (M4).
- The volatility curve has no smile: a deep in- or out-of-the-money option is valued at the at-the-money volatility of
  its expiry.
- A later section of this ADR records the risk treatment (M4).
- The clearing member's statement has no premium record: premiums move the cash balance it reports.
