# ADR 0010: UUIDv7 identifiers, UTC instants, closed-open ranges, decimal money

- Status: accepted
- Date: 2026-10-10

## Decision
- Identifiers are UUIDv7 (time-ordered, index friendly).
- Instants are UTC; dates are business dates; every period is a closed-open `LocalDateRange`, including production periods, delivery windows and accounting periods.
- Money and quantities are `BigDecimal`-based value classes with explicit units; certificates are whole MWh and allowances whole tCO2e.
- Enums travel by constant name in JSON (Jackson's string form is disabled).

## Consequences
Adjacent periods never overlap by a day, amounts never lose precision, and sorting by id follows creation time.
