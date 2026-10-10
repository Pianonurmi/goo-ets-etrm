# ADR 0001: Modular monolith on Spring Modulith

- Status: accepted
- Date: 2026-10-10

## Context
One team builds trade, inventory, allocation and ledger capabilities that need strong consistency: an approved
allocation reserves lots, instructs the registry, records the delivery and posts to the ledger as one business
transaction. Network partitions between these steps would add distributed-transaction complexity for no gain.

## Decision
Build one deployable from Gradle subprojects, one per bounded context, with Spring Modulith enforcing that modules
only use another module's declared named interfaces (`api`, `criteria`) and declared dependencies. Each module owns a
database schema with no cross-schema foreign keys. Cross-module side effects flow through domain events.

## Consequences
- `ModularityTests` fails the build on cycles or access to module internals.
- A module can be extracted into a service later: it already owns its schema and talks through its API and events.
- Event listeners run in the publishing transaction today; Spring Modulith's event registry provides the outbox when externalisation is needed.
