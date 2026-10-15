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
- A build test forbids `now()` without a clock argument in production code (`TimeSourceTest` in `apps/api`, which
  scans the sources; detekt cannot run a type-aware rule here because it runs without type resolution). Its
  exceptions are listed in the test: jOOQ adapters writing `recorded_at`-style instants, and the event and bitemporal
  types' defaults. Tests use `TestClock` (platform test fixtures). Increment 15 extends the rule to tests.

## Consequences
- Period-end, expiry and delivery dates are reproducible in tests and under replay.
- Existing direct calls are removed module by module; the rule lists the remaining exceptions.

## Amendment (increment 15, M3)
- **No exceptions in production code.** Row stamps the application never reads (`updated_at`, `approved_at`,
  `reconciled_at` and the like) are written as the database's transaction time (`DSL.currentInstant()`), as the
  decision above already says of `recorded_at`. A time the application reads back (a production forecast's
  `recordedAt`) comes from the service's clock.
- **Event time and `AsOf.now` read `ApplicationClock`** (platform), which the application's `Clock` bean installs at
  start-up. Passing the clock into every one of the 134 places that publish an event would add a parameter to each
  for no difference in behaviour; a test that moves time can install its `TestClock` there too.
- **Tests follow the rule.** `TimeSourceTest` scans the test sources and fixtures as well: tests take time from a
  `TestClock`, a fixed instant, the application's `Clock` bean, or `TestDatabase.transactionTime()` when they compare
  with what the database recorded. The only exceptions are token tests, which Spring Security validates against the
  system clock.
- **Invoices are dated in the entity's time zone**, and the static specificity of criteria is computed in decimals
  (a table of log2(40 / n) replaces the floating-point logarithm), so a bucket's order is the same on every machine.
