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
| `POST /api/v1/registry/statements/csv?registryAccountId=&statementDate=&profile=` | Holdings: parsed, stored in inventory and reconciled. Breaks: `MISSING_INTERNALLY`, `MISSING_IN_REGISTRY`, `ATTRIBUTE_MISMATCH`. An in-flight lot that is gone from the account confirms its instruction. For an account in an emission allowance registry the `ALLOWANCE_HOLDINGS` profile is used and balances are reconciled (below). |
| `POST /api/v1/registry/statements/transactions?registryCode=&profile=` | Transactions: lines matched to open instructions by client reference (our instruction key), registry reference or certificate range; completed lines confirm, failed lines fail. The file is kept as a document; unmatched lines are reported. |

Generic holdings columns: `issuance_ref,cert_no_start,cert_no_end,device_gsrn,production_start,production_end[,extra]`.
Generic transaction columns: `transaction_id,type,issuance_ref,cert_no_start,cert_no_end,status,date,client_reference`.
Generic allowance holdings columns: `product_code,quantity`. Lines for the same product are added up; constants
`product.<WORD>` map registry unit names to product codes (for example `product.GENERAL ALLOWANCES = EUA`).

Allowance balances are compared per product with the holding of the account. A registry balance between the free
quantity (held minus reserved) and the held quantity agrees, because reserved allowances may already have left in the
registry. Anything else, including a product only one side knows, is a `BALANCE_MISMATCH` break with both quantities.

## Routes

Within one registry free and next day; between two hub members via the AIB hub at the configured fee and expected
days; otherwise infeasible. Overrides in `etrm_registry.transfer_route`.

## Status of registry-specific integrations (October 2026)

- Grexel's G-REX platform offers account holders a REST API under contract; NECS (Statnett) runs Unicorn Certigy with a machine-to-machine API. Adapters follow in M8 once the manuals are in hand; until then these registries use FILE.
- The AIB Hub has no account-holder interface: cross-border transfers are instructed in the exporting registry.
- The Union Registry has no account-holder API: instructions are MANUAL (trusted account list, 26-hour delay to non-trusted accounts, registry four-eyes); holdings statements are imported as files.

## Union Registry allowance instructions

Allowance instructions carry `assetKind = ALLOWANCE`, a product code, a quantity in `TCO2E` and the holding they
reserve; certificate lot instructions carry `GOO_LOT`, the lot and its range.

| Endpoint | Instruction |
|---|---|
| `POST /api/v1/registry/instructions/allowance-transfers` | `ALLOWANCE_TRANSFER` from one of our accounts to a registered account in the same registry. `movementType` `SALE` (with `unitPrice`) or `TRANSFER_OUT`. |
| `POST /api/v1/registry/instructions/surrenders` | `SURRENDER` for a compliance year. |
| `POST /api/v1/deliveries/allowances` | The transfer for an allowance sale schedule of a confirmed trade: from the leg's delivery account to the counterparty's registered account, as a `SALE` at the trade price. |

Submission reserves the allowances. Both kinds need a second person (`INSTRUCTION_APPROVAL`), then go to the MANUAL
gateway: the instruction pack (`InstructionPack`) carries the product, quantity, unit, receiving account, compliance
year and `expectedEffectiveAt`. That is the approval time for our own accounts and for counterparty accounts trusted
on that day, and 26 hours later otherwise. The operator executes it in the Union Registry (which applies its own
four-eyes) and confirms with the registry transaction id.

Confirmation releases the reservation and records the movement on the holding: `SALE` with the unit price (the ledger
books proceeds and cost of sale), `TRANSFER_OUT` or `SURRENDER` with the compliance year. A transfer to another of our
accounts also records the receipt there (`PURCHASE` at the sale price, or `TRANSFER_IN`). A confirmed delivery
instruction records the trade delivery, keyed by the instruction id. Failure or cancellation releases the reservation.

## Purchase receipts

A purchase is delivered when what we receive is matched to its delivery schedule. A lot received with
`acquisitionSource = TRANSFER_IN`, or an allowance `PURCHASE` / `TRANSFER_IN` movement, whose reference is a
purchase trade number is matched automatically to the earliest open schedule of that trade that fits: same legal
entity, product, receiving account when the leg names one, and no more than the remaining quantity. Anything else
stays unmatched (logged) and is matched by hand with `POST /api/v1/deliveries/receipts` (`scheduleId` plus `lotId`,
or `holdingId` and `movementSeq`). Each receipt is recorded at most once (idempotency key per lot or movement), and a
refused match never fails the receipt itself.

## Counterparty registry accounts

Counterparty accounts are reference data records held by the counterparty (`holderType = COUNTERPARTY`). They are
created, deactivated and put on or taken off the trusted account list only through a four-eyes change request:
`POST /api/v1/reference/counterparties/{id}/registry-accounts/change-requests` with an action (`CREATE`,
`DEACTIVATE`, `REACTIVATE`, `ADD_TO_TRUSTED_LIST`, `REMOVE_FROM_TRUSTED_LIST`). For Union Registry accounts a
trusted-list entry becomes effective four TARGET2 working days after approval (`trustedSince`); transfers to accounts
not trusted on the delivery date are delayed 26 hours by the registry.

Trade legs reference the account by id (`counterpartyAccountId`); `counterpartyAccountRef` (`REGISTRY_CODE:account`)
is filled from it. A textual reference that matches a registered account of the counterparty is linked
automatically; one that matches nothing stays free text. Intercompany mirror trades point at the original entity's
own delivery account.
