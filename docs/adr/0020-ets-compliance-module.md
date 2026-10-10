# ADR 0020: EU ETS compliance per installation, surrenders attributed from holding movements

- Status: accepted
- Date: 2026-10-10

## Context
Increment 1 kept emissions and expected free allocation as numbers typed in per legal entity and year
(`position.compliance_input`). An operator's obligation, however, belongs to an installation (or an aircraft
operator) with its own permit, monitoring plan and operator holding account in the Union Registry. Verified emissions
are a reported figure that a second person should check before it drives the position. Surrenders can be instructed
from this system or directly in the registry, and both must count exactly once. The regulatory deadlines changed in
the 2023 revision and may change again.

## Decision
- A new module `etscompliance` owns installations, emissions, free allocation, surrender attribution and requests,
  and deadlines. It depends on referencedata, product, inventory, registry and workflow; position depends on it.
- **Installations**: kind `INSTALLATION` or `AIRCRAFT_OPERATOR`, EUTL id, permit, monitoring plan reference and
  version, eligible products (EUA by default). Each is linked to one registry account, which must be the entity's own
  `OPERATOR_HOLDING` (or `AIRCRAFT_OPERATOR_HOLDING`) account; an account belongs to at most one installation.
- **Emissions** per installation and compliance year:
  - `FORECAST` and `MONTHLY_ESTIMATE` take effect at once and supersede the previous figure of the same kind and month.
  - `VERIFIED` records the verifier, the verification date and a document reference, and stays `PENDING_APPROVAL`
    until a `VERIFIED_EMISSIONS_APPROVAL` task (MIDDLE_OFFICE or RISK, four eyes) approves it. Approval supersedes
    any earlier verified figure.
  - The figure that counts: approved verified, else the sum of monthly estimates, else the forecast.
- **Free allocation**: the allocated quantity per installation and year (an adjusted quantity overrides it; status
  `EXPECTED`, `ISSUED` or `WITHHELD`). What was received is not typed in: it is the `FREE_ALLOCATION` movements on the
  installation's account for the year, so the ladder never counts issued allowances twice.
- **Surrender attribution**: every `SURRENDER` movement on an installation's account becomes one `surrender_record`
  (keyed by the movement sequence, so a replay counts nothing twice). The movement's compliance year is used when
  given (`EXPLICIT`); otherwise the surrender goes to the earliest earlier year still owed (`EARLIEST_OPEN_YEAR`).
  Surrenders instructed outside the system therefore count too.
- **Surrender requests**: `POST /api/v1/ets/installations/{id}/surrenders` (BACK_OFFICE or REGISTRY_OPERATOR) submits a
  registry `SURRENDER` instruction for the quantity still owed (or a given one) with the idempotency key
  `surrender:<installation>:<year>:<n>`. The registry's own approval, outbox and confirmation apply unchanged; the
  request follows the instruction's confirmation, failure or cancellation.
- **Deadlines as data** (`compliance_deadline_rule`, latest rule in force for the compliance year):
  - verified emissions report by 31 March of the following year;
  - surrender by 30 April of the following year, 30 September from compliance year 2024;
  - free allocation issued by 28 February of the year, 30 June from 2024 (first year to be confirmed by compliance).

  A daily job opens a `COMPLIANCE_DEADLINE` reminder 30, 14 and 7 days before each deadline that is not met, and one
  more once it is overdue (each stage once, `deadline_alert`). Reminders close themselves when the deadline is met.
- **Status** per installation and year: `OPEN` (not verified), `VERIFIED` (verified, not fully surrendered),
  `COMPLIANT`, or `NON_COMPLIANT` (deadline passed with emissions not surrendered).
- **Position**: the compliance ladder takes emissions, free allocation and surrenders from this module and the
  surrender deadline from its rules. `compliance_input` and `PUT /positions/ets/inputs` are removed.

## Consequences
- Emissions are maintained per installation; an entity without installations shows no obligation.
- The emission provision at period end (M6) and the compliance-short limit (M7) read the same figures.
- Not modelled: CBAM-linked phase-out of free allocation, the aviation and maritime specifics beyond the account type,
  ETS2, and bank-holiday adjustment of deadlines.
