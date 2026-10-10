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

## EMIR events (reporting module)
The reporting module records an EMIR event for each lifecycle step of a reportable trade (`emir_event`, append-only):

| Action | When |
|---|---|
| NEWT | the trade is confirmed for the first time |
| MODI | an amended trade is confirmed again |
| TERM | a reported trade is cancelled |
| EROR | middle office reports that a reported trade should not have been (`POST /api/v1/reports/emir/trades/{id}/error`) |
| VALU | a valuation run is signed off, for FC and NFC+ entities |

- Each event carries the report fields as they were at that moment. A replayed event records nothing.
- Recording never fails the trade's transaction; a gap shows up in completeness.
- `GET /api/v1/reports/emir/completeness` lists, per trade, the fields that its latest event (and latest valuation)
  is missing, under the rules in `emir_field_rule`.
- `EMIR_TRADE_ACTIVITY` exports the events as CSV or JSON.
- **The field names follow the EMIR Refit report but have not been verified against the regulatory technical
  standards** (`verified = false` on every rule). There is no XML (ISO 20022) output and no trade repository
  connection: the entity, or its delegate, reports from the export.

## MiFID II ancillary activity
`MIFID_ANCILLARY_ACTIVITY` totals a year's emission allowance trades (not cancelled) in EUR, by purpose and by spot or
derivative:
- `INTRAGROUP`: with an intercompany counterparty;
- `HEDGING`: in a hedging book;
- `COMPLIANCE`: in a compliance or own-use book;
- `TRADING`: everything else.

These are the inputs to the ancillary activity test. The test itself needs market-size data and is not computed.
