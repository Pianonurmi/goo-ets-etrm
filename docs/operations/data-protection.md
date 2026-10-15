# Data protection

What personal data the system keeps, where it goes, how long it stays and how a request to see or erase it is
handled. The review found beneficiary data on Kafka and no written procedure (finding S7); this page is that
procedure. Settle the retention periods with your data protection officer and legal counsel before production use;
the figures below are the system's defaults, not legal advice.

## What is personal data here

| Data | Where | Why it is kept |
|---|---|---|
| **Cancellation beneficiary** (name, identifier) when the beneficiary is a natural person: a GoO cancelled on behalf of a household or a sole trader | The beneficiary register (`etrm_inventory.beneficiary`), and the editable cancellation copies: `etrm_inventory.certificate_lot.cancellation`, `etrm_registry.registry_instruction.cancellation` and `request_payload`, `etrm_allocation.obligation.cancellation` (JSONB). Records that cannot forget (lot journal, audit trail, lot versions, instruction events) keep only the `beneficiaryRef` since 0.15.0 | The registry's cancellation statement names the beneficiary; disclosure evidence for the supplier's fuel mix |
| **User identifiers** (the token's subject or user name) | `actor` columns: audit trail, lot events, journal entries, approvals and every `created_by` | Who did what: segregation of duties and the audit trail |
| **Contacts of counterparties** (names, addresses, VAT ids, bank accounts) | `etrm_referencedata` tables, versioned | Contracts, invoices and payments. Mostly legal persons; a sole trader's are personal data |
| **Showcase demo accounts** | `ETRM_DEMO_USERS` (environment), not the database | Sign-in to the public demo; wiped on every restart (ADR 0033) |

Logs (ECS JSON) carry the actor and the trace id but no request or response bodies. Traces carry span attributes
without bodies.

## What leaves the application

- **Kafka (ADR 0029).** Since 0.14.0 the externalised events carry no beneficiary name or identifier: the
  cancellation details of `InstructionConfirmed`, `InstructionFailed`, `LotReceived` and `LotTransferred` have
  `beneficiaryName` and `beneficiaryId` set to null (`ExternalEvents.payload`, tested by `ExternalPayloadTest`).
  Since 0.15.0 they carry the `beneficiaryRef`. The instruction and lot ids are the reference: a consumer that needs
  the beneficiary reads it through the API, under that API's roles and entity scope. A topic cannot forget what it was
  sent, so the rule is to send nothing that would have to be forgotten. Events sent by 0.13.x and earlier did carry
  the beneficiary; delete or compact those topics, or let their retention expire.
- **Kafka transport.** Set `kafka.securityProtocol` (e.g. `SASL_SSL`) and the SASL values in the chart
  ([kubernetes.md](kubernetes.md)); without them the client speaks plaintext.
- **Registry instructions** carry the beneficiary to the registry, which needs it for the cancellation statement.
- **GL exports and reports** carry no beneficiary; invoices carry counterparty contact data.

## Retention

| Data | Kept | Then |
|---|---|---|
| Journal, invoices, payments and their audit trail | as long as bookkeeping law requires (in Finland, for example, ten years for the books and six for vouchers) | archived offline; see below for why nothing is dropped yet |
| Cancellation details and lot events | as long as the disclosure evidence and the registry's rules require, and at least as long as the journal entries they support | as above |
| Completed event publications | `etrm.events.retention`, 7 days by default | deleted by `EventPublicationMaintenance` |
| Application logs | the log platform's retention | the log platform deletes them |
| Kafka topics | the topic's retention, set by the platform team | the broker deletes them |

## Requests from a data subject

**Where the beneficiary is kept.** A cancellation records its beneficiary once in the register and carries the
register's id as `beneficiaryRef` (`BeneficiaryCommand.record`, on opening an obligation, submitting a cancellation
instruction or cancelling a lot). The editable copies of the cancellation keep the name for daily work. Records that
cannot forget keep only the reference: the lot journal and the audit trail, both hash-chained, leave the name and
identifier out (`PersonalData.redact`), and the database triggers that copy rows into the lots' version history and
the instructions' event history do the same (`without_beneficiary`).

**Access.** Find the person's register entries by name or identifier, then read them through the API (ADMIN or
AUDITOR):

```sql
SELECT id, recorded_at, erased_at FROM etrm_inventory.beneficiary WHERE name ILIKE '%<name>%' OR identifier = '<id>';
```

`GET /api/v1/inventory/beneficiaries/{id}` returns the entry; the lots, instructions and obligations that name it carry
its id in `cancellation.beneficiaryRef`. For a user, the audit trail by actor answers it:
`GET /api/v1/audit/events?actor=<user>` (AUDITOR).

**Erasure.** Records that bookkeeping or registry rules require are kept until their retention ends; an erasure
request is answered with that legal ground and the retention end. When nothing requires the data any more:

1. An administrator asks for the erasure with the request's reference as the reason:
   `POST /api/v1/inventory/beneficiaries/{id}/erasure` with `{"reason": "…"}`.
2. An auditor approves the `BENEFICIARY_ERASURE` task in the inbox; never the administrator who asked. A rejection
   withdraws the request.
3. The approval removes the name and identifier from the register, the lots, the registry instructions (their
   cancellation and request) and the obligations, in one transaction. The register entry stays as a tombstone (who
   asked, why, who approved, when), and so do the references to it. The hash chains are not touched and stay intact.

**Rows written before 0.15.0.** The lot journal and audit trail rows of earlier versions keep their copy of the name:
they cannot change without breaking the chain. They leave with their partition once the retention has ended, as
described below.

## Archiving old partitions

The audit trail and the lot journal are partitioned by month (ADR 0030) and hash-chained across their partitions. When
the retention of a month has ended (see [Retention](#retention)), its partitions can leave the database without
breaking the verification:

1. **Declare an archive cut** (ADMIN): `POST /api/v1/admin/integrity/archive-cuts` with
   `{"cut": "2016-01-01T00:00:00Z", "reason": "…"}`. The cut is the first instant of a UTC month and at least
   `etrm.integrity.archive-min-age` old (ten years by default). Cuts only move forward. The request is refused unless
   the audit trail and the lot journal verify now. Before the cut is recorded, the hash of each lot's last event before
   it is kept (`lot_event_anchor`). The declaration goes to the audit trail, and the nightly digest names the latest
   cut.
2. **Archive the partitions before the cut** as the database owner: `pg_dump --table` each
   `audit_event_pYYYYMM` and `lot_event_pYYYYMM` to offline storage under the retention of the archive.
3. **Drop them:** `ALTER TABLE etrm_audit.audit_event DETACH PARTITION …; DROP TABLE …;` and the same for
   `etrm_inventory.lot_event`.
4. **Check** that `GET /api/v1/audit/verify` and `GET /api/v1/inventory/journal/verify` still report no breaks. The
   nightly verification checks them again.

The verification accepts what the cut allows and nothing more:

- **Audit trail.** The seals stay, so the links are still checked end to end. A sealed row may be missing only when it
  occurred before the cut.
- **Lot journal.** A lot's chain may start from its anchor.

A row removed after the cut, or a lot's chain that starts from anything but its anchor, is still reported. The
ledger journal is not partitioned: the books stay whole for their retention.

## Who to involve

A request goes to the data protection officer, who decides. An administrator asks for the erasure and an auditor
approves it, so no single person can erase a beneficiary.
