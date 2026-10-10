# ADR 0009: Registry integration as instruction, execution, confirmation

- Status: accepted
- Date: 2026-10-10

## Context
Registries differ: some offer APIs, some accept files, the Union Registry offers account holders only its web UI with
its own four-eyes approval. The system must track every transfer and cancellation regardless.

## Decision
Every registry action is a `registry_instruction` with an idempotency key and a state machine
(`PENDING → SENT → ACKNOWLEDGED → CONFIRMED | FAILED | CANCELLED`). A gateway per integration mode executes it: the
simulator confirms synchronously, the file gateway writes an instruction pack, the manual gateway creates work for an
operator who confirms in the application after acting in the registry. Statement imports reconcile what the registry
holds against internal lots and raise breaks.

## Consequences
- Lots move to `IN_TRANSFER` when instructed and only leave inventory on confirmation.
- Registry-specific adapters (Grexel API, hub message formats) plug in as gateways and statement parsers without touching the allocation or inventory modules.
