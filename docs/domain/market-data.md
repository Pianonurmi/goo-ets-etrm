# Market data

| Concept | Meaning |
|---|---|
| Instrument | Something with a price: `EUA_SPOT`, `EUA_FUTURE` (a listed futures contract), `EUA_FORWARD`, `GOO_VINTAGE` (a product bucket and production year), `INDEX`, `RATE` |
| Alias | A vendor's or broker's code for an instrument, per source, so their files import unchanged |
| Quote | A price of an instrument on a date: `SETTLEMENT`, `MID`, `BID`, `ASK`, `CLOSE` or `FIXING`, with source and quality |
| FX rate | Units of the quote currency per unit of the base currency on a date, per source (ECB, an import, manual) |
| Import format | How a file is read (see below) |
| Price index | An instrument's quotes fixed by a rule (`ON_DATE`, `PRIOR_DAY`, `MONTH_AVERAGE`) for INDEX-priced trade legs |

## Quality and corrections
- A price moving more than `etrm.marketdata.max-move-pct` (default 25 %) from the previous one is `SUSPECT`. A
  second person acknowledges it with a note (`POST /quotes/{id}/acknowledge`). Until then readers do not use it.
- A new value for the same instrument, date, type and source supersedes the current row. Reads take `knownAt` to see
  values as they were known then (P&L explain and restated valuations rely on this).

## FX
`FxRates.rate(date, from, to)` tries, in order:
- the direct rate;
- the inverse of the opposite rate;
- a cross through EUR.

Each rate used is the latest on or before the date, and at most `etrm.marketdata.fx-max-staleness-days` (default 10)
old. The result says which path was used and how old the oldest rate is.

## Import formats
| Kind | File | Mapping |
|---|---|---|
| `FX_ECB_XML` | ECB reference rates (`eurofxref-daily.xml`, `-hist-90d.xml`, `-hist.xml`) | constant `base` (EUR) |
| `FX_WIDE_CSV` | one row per date, one column per currency (ECB `eurofxref-hist.csv`); `N/A` means no rate | `date` column, constant `base` |
| `QUOTES_LONG_CSV` | one price per row | `date`, `instrument`, `value`, optional `currency` and `quoteType` columns; constants for what the file lacks |

Each format sets the delimiter, header lines to skip, date format and decimal separator.

Seeded formats:
- `ECB_DAILY_XML`
- `ECB_EUROFXREF_HIST`
- `GENERIC_SETTLEMENT_CSV` (`Date,Contract,Settlement,Currency`)
- `GENERIC_BROKER_GOO_CSV` (`Date,Product,Mid` in EUR/MWh)

Each file is imported once per format (by content hash). Lines that cannot be read, or that name an unknown
instrument, are reported with their line number; the rest are imported.

## Curves
| Kind | Pillars | Reading a price |
|---|---|---|
| `EUA_FORWARD` (per allowance product) | listed futures still to deliver, plus spot at two working days | linear in calendar days between pillars, flat beyond (`EXTRAPOLATED`) |
| `GOO_VINTAGE` (per product bucket) | the bucket's vintage instruments by production year | the vintage's point, else the nearest earlier vintage (`EARLIER_VINTAGE`) |

Building a snapshot works pillar by pillar:
1. A price of the snapshot date, taking quote types in the curve's order of preference.
2. Otherwise the latest price within `stalenessDays`, marked `STALE`.
3. Otherwise the pillar is recorded as a gap.

A proxy curve's official points, plus the spread, fill the pillars the curve lacks.

Lifecycle:
- A snapshot is immutable. An override is a new version with a reason.
- `submit` opens a `CURVE_SIGN_OFF` task. Someone other than the builder signs it off.
- The signed-off snapshot becomes `OFFICIAL` for its date and supersedes the previous one.
- Readers take the official snapshot, as known at an instant if they ask (ADR 0018).
- A job builds and submits every active curve at 18:15 Europe/Berlin on weekdays
  (`etrm.marketdata.curve-build-cron`).

Consumers:
- Allocation scoring marks are the official vintage prices in EUR.
- Settlement prices INDEX legs at the fixing for the delivery date plus the leg's spread.

## API
`/api/v1/market-data/...`:
- `instruments`
- `aliases`
- `quotes` (with `knownAt`), `quotes/suspect`
- `fx-rates`, `fx-rates/convert`
- `import-formats`
- `imports` (multipart upload)
- `indices`, `indices/{code}/fixings/{date}`
- `ecb/pull` (ADMIN)
- `curves`: create; `{code}/snapshots` (build); `snapshots/{id}/override`; `snapshots/{id}/submit`; `{code}/official`; `forward-price`; `vintage-price`

Futures contracts are maintained at `/api/v1/products/futures-contracts`.
