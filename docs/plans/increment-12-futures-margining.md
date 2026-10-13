# Increment 12: EUA futures margining

**Status: in progress.** The decisions go into ADR 0046.

## Context

EUA futures are captured as cleared trades since increment 3: a FUTURE trade names an active contract of the product
catalogue (lot size, delivery and last trading day), trades in whole lots on one venue, against an exchange or a
clearing house, and is forced to `CLEARED` with its CCP. Nothing models what clearing does with cash:

| Open | Effect today |
|---|---|
| No variation margin | A future's value is paid out daily by the clearing house, but the system carries it as unrealised MtM until delivery, in the period-end MtM like a forward |
| No initial margin or clearing statements | The cash at the clearing member, the margin posted and the fees are invisible; nothing reconciles the CCP's figures |
| No margin calls | A shortfall of cash at the clearer is not raised or paid through the system |
| Delivery at the trade price | At expiry the settlement item bills the full notional at the trade price, although the variation margin already paid the difference to the final settlement price |
| CCP exposure | Risk measures the clearing house's MtM and PFE like a bilateral counterparty's |

## Findings that shape the plan

- **Settlement prices are already there:** `EUA_FUTURE` instruments carry the contract code, and the demo imports
  daily `SETTLEMENT` quotes (`GENERIC_SETTLEMENT_CSV`). Variation margin needs no new market data.
- **Valuation already leaves futures undiscounted** (ADR 0044), because they are margined.
- **A new `clearing` module** owns clearing accounts, daily margin, statements and margin calls. It depends on
  trade, product, market data, reference data and workflow; the ledger, settlement and risk read its `api`, and
  clearing depends on none of them.
- **Options stay out:** `InstrumentType.OPTION` exists but there are no option terms anywhere; that is a trade-model
  increment of its own.

## Milestones

| Milestone | Content |
|---|---|
| M0 | This plan; exploration of futures, clearing, settlement, ledger and risk |
| M1 | Clearing accounts and daily variation margin: per account, contract and business day from the official settlement prices (yesterday's position at the price move, today's trades against their trade price), immutable versioned runs, a daily job; the ledger posts the variation margin as realised futures result against cash at the clearer, and the cleared futures leave the period-end MtM |
| M2 | Clearing statements: the clearing member's daily statement (initial margin, variation margin, fees, cash balance) imported as CSV, reconciled against our runs, breaks for back office; initial margin and fees posted |
| M3 | Margin calls when the cash at the clearer falls short, paid under four eyes; risk counts initial margin as collateral posted and no longer treats margined futures as bilateral exposure |
| M4 | Expiry: delivery of a future settles at the final settlement price (the variation margin paid the rest) |
| M5 | Screens, demo statements, help, ADR, deck; release 0.12.0 |

## Verification

- Unit: the margin arithmetic against hand-computed lot moves; statement reconciliation.
- Integration: a clearing IT (runs, versions, job), ledger postings idempotent per run, period end without the
  margined futures, delivery at the final settlement price.
- Playwright: the clearing screens with demo data.
