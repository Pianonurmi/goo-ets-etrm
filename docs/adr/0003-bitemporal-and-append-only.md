# ADR 0003: Bitemporal versions and append-only journals

- Status: accepted
- Date: 2026-10-10

## Context
Auditors and regulators ask "what did you know on date K about the state on date D". Registry corrections, label
certificates and trade amendments arrive late and backdated.

## Decision
- Master data (`reference_history`), trades (`trade_version`) and certificate lots (`certificate_lot_version`) keep every version with a transaction-time period; lots also keep a valid-time period. A backdated change re-records the superseded version for the valid time before the change (see the trigger in the inventory schema).
- Lot events, journal entries and audit events are append-only tables protected by triggers and chained with SHA-256 hashes; verification endpoints report broken chains.
- Corrections to the ledger are reversals, never edits.

## Consequences
- Storage grows with history; partitioning the version and event tables by time is the planned mitigation (increment 5).
- Services must pass the effective time explicitly; the tests cover the backdated-correction case.
