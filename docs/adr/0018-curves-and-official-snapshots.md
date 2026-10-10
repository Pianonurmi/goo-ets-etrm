# ADR 0018: Curves are immutable snapshots, official only after a second person signs them off

- Status: accepted
- Date: 2026-10-10

## Context
Valuation, allocation marks, the NRV test and the emission provision all need one agreed price per product and
date. Prices arrive from several sources and are sometimes corrected. A valuation must be reproducible: a P&L figure
reported yesterday has to be explainable from the prices that were official yesterday, even if a price has since
been corrected.

## Decision
- A **curve** is defined per allowance product (`EUA_FORWARD`) or per GoO bucket (`GOO_VINTAGE`). The definition
  holds the currency, quote types in order of preference, a staleness window, and an optional proxy curve with a
  spread.
- A **snapshot** is built from prices known at build time:
  - Forward curves take the product's listed futures still to deliver, plus spot at two working days.
  - Vintage curves take the bucket's vintage instruments.
  - Each pillar takes a price of the snapshot date in the preferred quote type order. Failing that, it takes the
    latest price within the staleness window, marked `STALE`. Pillars with neither are recorded as gaps.
  - A proxy curve's official points fill pillars the curve lacks, shifted by the spread (`PROXY`).
- Snapshot points are immutable (trigger). Overriding a pillar creates a new version (`OVERRIDE`, with a reason).
- **Sign-off** goes through the inbox (`CURVE_SIGN_OFF`, RISK or MIDDLE_OFFICE), by someone other than the builder.
  A database check enforces this too. The signed-off snapshot becomes `OFFICIAL` for its date and supersedes the
  previous official one, which keeps its sign-off and supersession times.
- **Reading** prices:
  - `official(curve, date, knownAt)` returns the snapshot that was official at an instant.
  - Forward prices are linear in calendar days between dated pillars and flat beyond them, reported as
    `EXTRAPOLATED`.
  - Vintage prices use the vintage's own point, else the nearest earlier vintage (a cautious mark).
- A daily job builds and submits every active curve after the prices are in. Curves without prices are skipped.
- **Consumers:**
  - Allocation marks come from the official vintage curves (EUR only); the hand-kept `bucket_mark` table is gone.
  - Settlement prices INDEX legs at the index fixing for the delivery date plus the leg's spread. Waiting items are
    re-evaluated when prices are imported.

## Consequences
- A valuation records the snapshot ids it used. Explaining it later needs no copy of prices.
- Until someone signs off, the previous official curve stays in use, possibly from an earlier date. Valuation flags
  this.
- Extrapolation and earlier-vintage fallbacks are visible in every price read, so reports can show how much value
  rests on them.
