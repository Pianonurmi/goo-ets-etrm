# Regulatory data (EMIR, MiFID II)

## Scope
`TradeView.emirReportable` marks EMIR-reportable derivatives:
- forwards, futures, swaps and options on emission allowances;
- not allowance forwards that deliver entirely within two working days of the trade date, which are spot
  transactions;
- not GoO trades, which are not financial instruments.

The rule (`RegulatoryScope`) is to be confirmed by compliance for each entity.

## Data captured on trades
| Field | Where | Checks |
|---|---|---|
| UTI | `regulatory.uti` | ISO 23897: up to 52 characters starting with a valid LEI. Unique per reporting entity. Never changes once assigned. |
| UTI source | `regulatory.utiSource` | `OWN`, `COUNTERPARTY` or `VENUE`. For a reportable trade with source `OWN` (or none), the UTI is generated from the entity's LEI and the trade number. An intercompany mirror reports the original's UTI. |
| Execution timestamp | `regulatory.executionTimestamp` | UTC instant |
| Venue | `regulatory.venueMic` | ISO 10383 MIC, `XOFF` off venue. Futures take their contract's venue. |
| Clearing | `regulatory.clearingStatus`, `ccpCounterpartyId` | A cleared trade names a clearing house as its CCP. Futures are always cleared. |
| Trading capacity | `regulatory.tradeCapacity` | `DEAL`, `MTCH`, `AOTC` |
| Reporting responsibility | `regulatory.reportingResponsibility` | `SELF`, `COUNTERPARTY`, `BOTH` |
| UPI | `leg.upi` | 12 characters (ISO 4914) |
| ISIN | `leg.isin` | ISO 6166 with check digit |
| Futures contract | `leg.futuresContractCode` | see below |

EMIR classification (`FC`, `NFC_PLUS`, `NFC_MINUS`, `THIRD_COUNTRY_ENTITY`, `OTHER`) is held on legal entities and
counterparties. Counterparties can name a group parent; credit exposure rolls up to it.

## Futures
A `FUTURE` trade:
- is done with an exchange or clearing house;
- names a listed contract (`/api/v1/products/futures-contracts`) on the leg's allowance product and currency;
- delivers once, on the contract's delivery date, in whole lots;
- is captured no later than the last trading day;
- is cleared, on the contract's venue.

Margining is not modelled (increment 3 scope).

Report exports and completeness checks follow in the reporting module.
