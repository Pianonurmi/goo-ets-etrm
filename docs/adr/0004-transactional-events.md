# ADR 0004: Domain events published in the writing transaction

- Status: accepted
- Date: 2026-10-10

## Context
Ledger postings, obligation synchronisation, approval tasks and audit records must never diverge from the business
change that caused them.

## Decision
Modules publish `DomainEvent`s through Spring's `ApplicationEventPublisher` inside the command transaction. In
increment 1 listeners are synchronous (`@EventListener`), so the whole chain commits or rolls back together. Spring
Modulith's JDBC event publication registry (table `etrm_platform.event_publication`) is configured for asynchronous
and externalised delivery when it is enabled per listener.

## Consequences
- Listeners must be idempotent on `eventId` (journal postings are unique per event and rule; audit ignores duplicates).
- Long-running work (exact solver, registry calls) must not run in listeners; it runs in explicit commands or jobs.
