# Increment 13: options on EUA futures

**Status: in progress.** The decisions go into ADR 0047.

## Context

`InstrumentType.OPTION` has been accepted at capture since increment 1, but nothing knows what an option is: an
option trade gets no terms, is valued as a linear forward, counts its full quantity in positions and PFE, and would
be margined as a future if it named a futures contract. Utilities hedge compliance purchases with EUA options on EEX
(calls to cap the price, collars of a bought call and a sold put), so the gap matters as soon as one is booked.

## Scope

European calls and puts on EEX EUA December futures, exchange-traded and cleared, premium-style: the buyer pays the
premium, there is no variation margin on the option, and at expiry an option in the money is exercised into its
underlying future at the strike. Out of scope: OTC options, American and Asian options, a volatility smile, and
futures-style option margining.

## Findings that shape the plan

- **Futures logic keys on `futuresContractCode`** (valuation without discounting, clearing, hedge designation,
  settlement at the final settlement price). Option legs name their underlying contract in a field of their own,
  so none of that picks them up by accident.
- **An option delivers nothing.** Exercise creates the future, which then delivers. Option legs have no delivery
  schedule, so positions, obligations, settlement items and delivery recording leave them alone.
- **Valuation is linear today** (`MtM = q(market − contract)`), and the explain decomposes linearly. With the option
  value per tonne as the market price and the premium as the contract price, MtM stays `q(V − premium)` and premium
  plus MtM is fair value, so the ledger's period-end MtM works unchanged.
- **Curves are signed off under four eyes**; implied volatility becomes a curve like the others instead of raw
  quotes read at valuation.
- **Premiums of cleared options move cash at the clearing member** (increment 12), so no settlement item is needed.

## Milestones

| Milestone | Content |
|---|---|
| M0 | Plan and ADR 0047 |
| M1 | Option terms on trades: type, strike, expiry, underlying contract; validation like futures (exchange or clearing house, active contract, whole lots, cleared on the contract's venue, expiry on or before the contract's last trading day); no delivery schedule; EMIR option fields; capture form |
| M2 | `VOLATILITY` quotes and an `EUA_VOLATILITY` curve (total variance in time); Black-76 with delta, gamma, vega; option valuation and a VOL component in the explain by sequential revaluation |
| M3 | Premium accounts and postings; expiry job: exercise in the money into the future at the strike, abandon out of the money; premium released to the realised result |
| M4 | Delta-equivalent quantity in net positions, PFE and the pre-trade check; option greeks report |
| M5 | Screens, demo options and volatility history, help, deck, Playwright; release 0.13.0 |

## Verification

Black-76 against reference values, put-call parity and finite differences; integration tests for capture rules,
valuation and explain, expiry with the created future and its first margin, ledger postings and period end, risk;
the demo seeding test, web checks, the full Playwright suite, CI and the release run.
