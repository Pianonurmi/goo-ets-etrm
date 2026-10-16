# ADR 0052: Virtual threads for requests, jobs and background work

- Status: accepted
- Date: 2026-10-16

## Context
Most of the application's time is spent waiting: on PostgreSQL, on registries, on the identity provider, on PDF
layout. Since increment 6 the application runs on Java 25 with `spring.threads.virtual.enabled`, so request handling
and scheduled jobs run on virtual threads. The choice was recorded in a plan, not an ADR, and increment 16 added more
work that leaves the request (deferred PDF rendering, async runs), so the rules need one place.

## Decision
- **Virtual threads everywhere the application waits**: requests and `@Scheduled` jobs (Spring), PDF rendering after
  commit (`DocumentService`), async runs (`AsyncRunner`), each on its own named virtual thread. A pool is used only to
  bound concurrency (`etrm.async-runs.max-concurrent`, the registry dispatch executor), never to save threads.
- **Java 24 or later is required** (JEP 491): before it a virtual thread blocking inside `synchronized` pinned its
  carrier, and concurrent first serialisations (Jackson's serializer cache under kotlin-reflect's locks) hung the
  server. `ConcurrencyIT` covers that case.
- **No lock is held across blocking I/O.** A `synchronized` block or `ReentrantLock` guards memory only; database and
  HTTP calls happen outside it. Locks that serialise business work are database locks (row locks, advisory locks).
- **Tests run with two carrier threads** (`jdk.virtualThreadScheduler.parallelism=2` in `apps/api`), so pinning or a
  lock held across a blocking call shows up as a hang in CI instead of under production load.
- **Context does not follow a thread.** Work started on a new thread takes what it needs explicitly: the actor
  (`ActorBinding`, a `ScopedValue`), the trace (a child span of the request's), never thread locals set elsewhere.

## Consequences
- Thread counts stop being a sizing question; the connection pool and the concurrency limits are the bounds.
- A native call (the CP-SAT solver) holds its carrier for as long as it runs; such calls are bounded by their own
  time limits (`etrm.allocation.exact.time-limit`, `etrm.allocation.pricing.time-limit`), so they cannot starve the scheduler for long.
