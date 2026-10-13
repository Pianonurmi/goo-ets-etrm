# Valuation

## MtM
Every open trade (`APPROVED`, `CONFIRMED`, `PARTIALLY_DELIVERED`) is valued per delivery schedule on its remaining
quantity q (+ bought, − sold):

    MtM = q × (market − contract) × DF     in the contract currency
    MtM base = MtM × rate(contract currency → base) on the valuation date

**Discounting** (ADR 0044): DF is the factor to the schedule's payment date (else its delivery date) from the
contract currency's official discount curve: zero rates by tenor, linear in days between tenor dates, simple
compounding within a year and annual compounding with a simple stub beyond, ACT/360 for EUR. DF is 1 for futures
(margined daily) and when the currency has no official discount curve. Results keep the pay date, the factor, the
discount curve and snapshot, and the undiscounted MtM.

**Market price**
- **Allowances**: the latest official forward curve of the product on or before the date, at the delivery date.
  Beyond the last future a curve read with `CARRY` grows at the last segment's carry rate; otherwise it is flat.
- **GoO**: the official vintage curve of the leg's product bucket for the vintage year. The vintage year is the
  calendar year the criterion's window overlaps most (the later year on a tie), or the delivery year.

The market price is converted into the contract currency at the valuation date's rate.

**Contract price**
- FIXED: the trade price.
- INDEX: the fixing (once the delivery date has passed and the index is fixed) plus the spread; before that, the
  forward price plus the spread.
- FORMULA legs are `EXCLUDED`.

**Exceptions**: `MISSING_BUCKET`, `MISSING_PRICE`, `MISSING_FX`. Results record the curve, snapshot, curve date and
price origin (point, interpolated, extrapolated, earlier vintage).

## Runs
- **Lifecycle**: `DRAFT` → submit → `PENDING_SIGN_OFF` → sign-off by someone else in the inbox
  (`VALUATION_SIGN_OFF`, RISK) → `OFFICIAL`. A run with exceptions is signed off only with a reason. A later
  official run for the same date supersedes the earlier one (`SUPERSEDED`); rejected runs are `REJECTED`.
- **End-of-day job**: at 19:45 Europe/Berlin on weekdays (`etrm.valuation.eod-cron`) it runs and submits every
  active entity.

## P&L explain
`GET /api/v1/valuation/pnl-explain?legalEntityId&from&to` compares the official runs on or before each date, per
schedule:

| Component | Meaning |
|---|---|
| NEW | schedules only in the later run |
| CANCELLED | schedules of cancelled trades only in the earlier run |
| DELIVERED | quantity delivered (or a schedule completed), valued at the earlier market |
| AMENDED | the change from a new trade version, at the earlier market |
| PRICE | market against contract, at the earlier rate to base |
| FX | the later value's change from the rate to base |
| UNEXPLAINED | rounding, and schedules valued in only one run |

The components add up exactly to the change in MtM. Signing off a run also stores its explain against the previous
official run.

## Portfolio value (management view, ADR 0039)
Each run also keeps a portfolio value in EUR, from allocation's valuation-grade allocation of the entity's held
certificates against its open obligations (a min-cost max-flow: most coverage, priority first, least cost):

| Line | Meaning |
|---|---|
| Inventory value | held certificates, each at the best price it qualifies for on the run date |
| Trade MtM | the run's official MtM of its GoO trades, in EUR |
| Give-away | premium handed over where a certificate worth more than the sale's product delivers it |
| Transfer fees | registry fees to the obligations' target registries |
| Expiry haircut | value lost on certificates delivered close to expiry |
| Portfolio value | inventory value + trade MtM − give-away − transfer fees − expiry haircut |

A GoO sale's result also shows its cheapest to deliver (the average value per MWh of the certificates that deliver
it) and its MtM at that price. Neither changes the official MtM, the run total, the ledger, EMIR or credit. Sign-off
stores a portfolio explain against the previous official run: trade MtM, inventory price, inventory in and out,
give-away, reallocation (transfer fees), expiry and unexplained (rounding). The portfolio value is left out, never
failing the run, when allocation cannot value the book or the base currency has no rate to EUR.

## API
`/api/v1/valuation`:
- `POST runs`
- `POST runs/{id}/submit`
- `GET runs`, `runs/{id}`, `runs/{id}/results?bookId`
- `GET official?legalEntityId&date`
- `GET pnl-explain`
- `GET trades/{id}/mtm?date` (what-if, not stored)
- `GET runs/{id}/portfolio` (404 when the run has no portfolio value), `GET portfolio-explain?legalEntityId&from&to`

Reading needs RISK, MIDDLE_OFFICE, ACCOUNTANT, AUDITOR or TRADER, plus access to the entity.
