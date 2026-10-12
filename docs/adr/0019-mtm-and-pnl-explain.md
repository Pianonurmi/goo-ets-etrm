# ADR 0019: MtM per delivery schedule against official curves; P&L explained by exact decomposition

- Status: accepted
- Date: 2026-10-10

## Context
Risk, the period-end accounts and credit exposure need one agreed market value of open trades per day, and a P&L
that can be explained. Valuation must be reproducible from recorded inputs, must show what it could not value, and
must not depend on accounting choices (those live in the ledger, M6).

## Decision
- A **valuation run** values every open trade (`APPROVED`, `CONFIRMED`, `PARTIALLY_DELIVERED`) of a legal entity on
  a date, per delivery schedule, using its remaining quantity:
  - MtM = q × (market − contract), with q positive for purchases and negative for sales. It is undiscounted:
    deliveries are short-dated, and discounting is an option for later.
  - Allowances use the latest official forward curve on or before the date, priced at the delivery date. GoO use
    the leg's bucket's official vintage curve for the vintage year: the calendar year the criterion's window
    overlaps most, or the delivery year when there is no vintage.
  - The market price is converted into the contract currency, and the result into the entity's base currency, at
    the date's rates.
  - INDEX legs:
    - once fixed, the contract price is the fixing plus the spread;
    - until then, the forward price stands in for the fixing, so the leg is worth its spread.
  - FORMULA legs are left out (`EXCLUDED`).
  - A schedule that cannot be valued is an exception: missing bucket, curve or rate. The run records each
    result's curve snapshot, the curve date and how the price was obtained (point, interpolated, extrapolated,
    earlier vintage).
- **Sign-off**: runs are drafts until submitted, then a `VALUATION_SIGN_OFF` task (RISK) is decided by someone other
  than who ran the run. Signing off a run with exceptions needs a reason. A later official run for the same date
  supersedes the earlier one. An end-of-day job runs and submits every active entity on weekdays.
- **P&L explain** between two official runs is a sequential substitution per schedule:
  - **NEW / CANCELLED**: schedules only in the later or only in the earlier run. A schedule that disappears because
    it was delivered counts as DELIVERED.
  - **DELIVERED** (same trade version) or **AMENDED** (new version): the change in quantity and terms, valued at the
    earlier market and rate.
  - **PRICE**: the move of market against contract, at the earlier rate.
  - **FX**: the later value's change from the rate to base.
  - **UNEXPLAINED**: rounding, and schedules not valued in one of the runs.

  The components add up exactly to the change in MtM (property test). With undiscounted forwards, the passage of
  time shows in PRICE as the curve rolls; there is no separate time component.

## Consequences
- MtM by book type is the input for the period-end MtM posting policy (M6) and for credit exposure (M7). Both read
  only official runs.
- Valuation depends on curves being signed off. A missing curve makes exceptions visible instead of silently
  valuing at zero.

## Amendment (ADR 0039)
Runs also keep a portfolio value and, at sign-off, a portfolio explain: a management view in separate immutable
tables. The run total, the results' official MtM and the P&L explain above are unchanged; GoO sale results also
carry their cheapest-to-deliver price and MtM, shown only.
