# ADR 0012: Registry instructions dispatched through an outbox, never inside a transaction

- Status: accepted
- Date: 2026-10-10

## Context
In increment 1 the registry gateway ran inside the transaction that created the instruction. That was harmless for
the simulator and the manual hand-over, but real registry APIs and file drops are slow and can fail in ways that
leave the outcome unknown: a timeout may mean the registry did or did not accept the transfer. Holding a database
transaction (and its connection) open across such a call, or blindly resending after a timeout, risks exhausted
connection pools and duplicate transfers of certificates.

## Decision
- Every gateway declares its channel (`SIMULATOR`, `MANUAL`, `FILE`, `API`) and whether it is `inline`. Inline channels (no external I/O) execute in the submitting transaction, so allocation approvals with the simulator still confirm in the same request.
- For other channels the instruction row is the outbox. It is committed as `PENDING`; after commit a nudge (and a ShedLock'd job every 30 seconds) claims due rows with `SELECT … FOR UPDATE SKIP LOCKED`, sets `DISPATCHING` with a lease, commits, calls the registry with no transaction open, and records the result in a new transaction.
- Gateway results: `Confirmed`, `Sent`, `Failed` (the registry refused), `Retry` (failed before reaching the registry: back to `PENDING` with linear backoff, `FAILED` after `max-attempts`), `Unknown` (may have reached the registry: `UNKNOWN`). A lease that expires while `DISPATCHING` also gives `UNKNOWN`.
- `UNKNOWN` is resolved by a status query (`InstructionStatusSource`, by our instruction key as client reference) or by an operator. It is never resent automatically.
- The channel is stored on the instruction when it is created; changing a registry's integration mode (a four-eyes master data change) does not strand in-flight instructions.
- Instructions that do not come from an approved allocation plan, and manual retries, need a second person (`PENDING_APPROVAL`, task `INSTRUCTION_APPROVAL`).
- Confirmation also comes from statements: an in-flight lot missing from its account's holdings statement confirms its instruction; a transaction export confirms or fails instructions matched by instruction key, registry reference or certificate range.
- Instructions with the registry beyond the channel's confirmation SLA, and those with an unknown outcome, open an `INSTRUCTION_FOLLOW_UP` task.
- Circuit per registry (increment 16): `Retry` and `Unknown` count as transport failures, any other answer resets the count. After `etrm.registry.circuit.failures` in a row the registry's pending instructions are no longer claimed; after `etrm.registry.circuit.cool-down` one is sent as a probe, and an answer (or an operator's resume) closes the circuit. The circuit only decides what is claimed; an `Unknown` outcome is still never resent.

## Consequences
- The approve response of an allocation plan on an API or file registry shows `INSTRUCTED`; confirmation arrives later, by the dispatcher or a statement.
- Metrics `etrm.registry.instructions.open{status}` and `etrm.registry.dispatch.lag.seconds` expose the backlog.
- Statement layouts are data (`statement_format` profiles), so a registry's new export format is configuration, not code.
