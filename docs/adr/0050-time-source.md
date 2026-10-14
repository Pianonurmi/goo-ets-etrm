# ADR 0050: One source of time

- Status: accepted
- Date: 2026-10-14

## Context
Business dates decide periods, deliveries, expiries and the day a posting belongs to. The review (finding C7) found
`Instant.now()` and `LocalDate.now()` without a clock in repositories, event metadata and jobs, and jobs scheduled in
Europe/Berlin that dated their work in the clock's zone (UTC).

## Decision
- **Application time comes from the injected `java.time.Clock`** (one bean, UTC). Services, jobs and listeners take
  it in their constructor. Tests pass a fixed clock.
- **Database time is transaction time only**: `recorded_at` and `created_at` defaults (`clock_timestamp()`) record
  when a row was written, for audit and ordering; they never decide a business date.
- **Business dates are taken in the legal entity's time zone** (`LegalEntityView.timeZone`), or in the zone of the
  market for exchange jobs (Europe/Berlin for EEX), never in the JVM's default zone.
- **Events carry when they happened** (`occurredAt`); a listener dates its posting from the event, so a replay posts
  on the original date.
- A detekt rule forbids `now()` without a clock argument in production code (increment 15 tightens it to tests).

## Consequences
- Period-end, expiry and delivery dates are reproducible in tests and under replay.
- Existing direct calls are removed module by module; the rule lists the remaining exceptions.
