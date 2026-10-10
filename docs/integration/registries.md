# Registry integration

Pattern (ADR 0009, ADR 0012): instruction → (four-eyes) → execution → confirmation, with the instruction row as
the outbox for channels that do external I/O.

## Channels

| Channel | Execution | Confirmation | Registries (default) |
|---|---|---|---|
| SIMULATOR | inline, confirms at once | immediate | SIMULATOR (tests, demos) |
| MANUAL | inline hand-over: the instruction waits for an operator who acts in the registry UI (with the registry's own four-eyes) | operator confirms with the registry reference, or a statement | UNION_REGISTRY |
| FILE | outbox: an RFC 4180 instruction pack is written to `etrm.registry.outbox-dir/<REGISTRY>/` | statement or transaction export, or operator | FINGRID, CESAR, NECS, ENERGINET, ELERING, CERTIQ, HKNR |
| API | outbox: adapter call with timeouts; unknown outcomes resolved by status query | adapter result, status query, statements | none until credentials exist (increment 2, M8) |

A registry's channel changes through a four-eyes change request:
`POST /api/v1/reference/registries/{code}/change-requests` with `{"integration":"MANUAL","reason":"…","expectedVersion":n}`,
approved by a second person in the inbox (`REFERENCE_DATA_CHANGE`). In-flight instructions keep their channel.

## Instruction states

`PENDING_APPROVAL → PENDING → DISPATCHING → SENT → (ACKNOWLEDGED) → CONFIRMED`, with `FAILED`, `CANCELLED` and
`UNKNOWN` (outcome not known; never resent automatically). Instructions from an approved allocation plan skip
`PENDING_APPROVAL`; manual instructions and retries need a second person (`INSTRUCTION_APPROVAL` task).

Settings (`etrm.registry.dispatch.*`): `unplanned-needs-approval` (true), `lease` (5 min), `retry-backoff` (1 min ×
attempt), `max-attempts` (5), `batch-size` (20), `confirm-from-statements` (true), `confirmation-sla.<CHANNEL>`
(MANUAL 48 h, FILE 72 h, API 4 h). Jobs: `etrm.registry.dispatch-cron` (every 30 s) and `etrm.registry.recovery-cron`
(every 5 min: expired leases, unknown outcomes, overdue instructions).

## Statements

Statement layouts are profiles in `etrm_registry.statement_format`: registry code (`*` for the generic fallback),
profile code, kind (`GOO_HOLDINGS`, `GOO_TRANSACTIONS`, `ALLOWANCE_*`), delimiter, lines to skip before the header,
date format, and a column mapping from field to header. Constants map registry words (`status.<WORD>`, `type.<WORD>`)
and the separator of a combined range column (`rangeSeparator`). Manage them with
`GET/PUT /api/v1/registry/statement-formats`.

| Endpoint | Effect |
|---|---|
| `POST /api/v1/registry/statements/csv?registryAccountId=&statementDate=&profile=` | Holdings: parsed, stored in inventory and reconciled. Breaks: `MISSING_INTERNALLY`, `MISSING_IN_REGISTRY`, `ATTRIBUTE_MISMATCH`. An in-flight lot that is gone from the account confirms its instruction. |
| `POST /api/v1/registry/statements/transactions?registryCode=&profile=` | Transactions: lines matched to open instructions by client reference (our instruction key), registry reference or certificate range; completed lines confirm, failed lines fail. The file is kept as a document; unmatched lines are reported. |

Generic holdings columns: `issuance_ref,cert_no_start,cert_no_end,device_gsrn,production_start,production_end[,extra]`.
Generic transaction columns: `transaction_id,type,issuance_ref,cert_no_start,cert_no_end,status,date,client_reference`.

## Routes

Within one registry free and next day; between two hub members via the AIB hub at the configured fee and expected
days; otherwise infeasible. Overrides in `etrm_registry.transfer_route`.

## Status of registry-specific integrations (October 2026)

- Grexel's G-REX platform offers account holders a REST API under contract; NECS (Statnett) runs Unicorn Certigy with a machine-to-machine API. Adapters follow in M8 once the manuals are in hand; until then these registries use FILE.
- The AIB Hub has no account-holder interface: cross-border transfers are instructed in the exporting registry.
- The Union Registry has no account-holder API: instructions are MANUAL (trusted account list, 26-hour delay to non-trusted accounts, registry four-eyes); holdings statements are imported as files.

Counterparty accounts on trades use the form `REGISTRY_CODE:account-number` until structured counterparty registry
accounts arrive (M3).
