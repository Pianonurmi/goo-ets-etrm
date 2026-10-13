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

## Consequences
- Until options are valued (increment 13 M2) they produce no valuation results instead of a linear one; risk counts
  them only once delta-equivalents exist (M4).
- Later sections of this ADR record the volatility curve and Black-76 valuation (M2), the premium and exercise
  (M3) and the risk treatment (M4).
