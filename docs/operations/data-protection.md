# Data protection

What personal data the system keeps, where it goes, how long it stays and how a request to see or erase it is
handled. The review found beneficiary data on Kafka and no written procedure (finding S7); this page is that
procedure. Settle the retention periods with your data protection officer and legal counsel before production use;
the figures below are the system's defaults, not legal advice.

## What is personal data here

| Data | Where | Why it is kept |
|---|---|---|
| **Cancellation beneficiary** (name, identifier) when the beneficiary is a natural person: a GoO cancelled on behalf of a household or a sole trader | `etrm_inventory.lot.cancellation`, `etrm_registry.registry_instruction.cancellation` (JSONB); the lot's event journal (`lot_event`, hash-chained); the audit trail (`audit_event`, hash-chained) | The registry's cancellation statement names the beneficiary; disclosure evidence for the supplier's fuel mix |
| **User identifiers** (the token's subject or user name) | `actor` columns: audit trail, lot events, journal entries, approvals and every `created_by` | Who did what: segregation of duties and the audit trail |
| **Contacts of counterparties** (names, addresses, VAT ids, bank accounts) | `etrm_referencedata` tables, versioned | Contracts, invoices and payments. Mostly legal persons; a sole trader's are personal data |
| **Showcase demo accounts** | `ETRM_DEMO_USERS` (environment), not the database | Sign-in to the public demo; wiped on every restart (ADR 0033) |

Logs (ECS JSON) carry the actor and the trace id but no request or response bodies. Traces carry span attributes
without bodies.

## What leaves the application

- **Kafka (ADR 0029).** Since 0.14.0 the externalised events carry no beneficiary name or identifier: the
  cancellation details of `InstructionConfirmed`, `InstructionFailed`, `LotReceived` and `LotTransferred` have
  `beneficiaryName` and `beneficiaryId` set to null (`ExternalEvents.payload`, tested by `ExternalPayloadTest`).
  The instruction and lot ids are the reference: a consumer that needs the beneficiary reads it through the API,
  under that API's roles and entity scope. A topic cannot forget what it was sent, so the rule is to send nothing
  that would have to be forgotten. Events sent by 0.13.x and earlier did carry the beneficiary; delete or compact
  those topics, or let their retention expire.
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

**Access.** Find the person's data by name or identifier:

```sql
SELECT id, status, cancellation FROM etrm_inventory.lot WHERE cancellation::text ILIKE '%<name or id>%';
SELECT id, status, cancellation FROM etrm_registry.registry_instruction WHERE cancellation::text ILIKE '%<name or id>%';
```

For a user, the audit trail by actor answers it: `GET /api/v1/audit/events?actor=<user>` (AUDITOR).

**Erasure.** Records that bookkeeping or registry rules require are kept until their retention ends; an erasure
request is answered with that legal ground and the retention end. When nothing requires the data any more:

1. The current-state columns (`lot.cancellation`, `registry_instruction.cancellation`) are not chained; a database
   owner may set the beneficiary fields to null there, recording the change and its reason in the audit trail
   (`AuditCommand.record`).

2. The hash-chained rows (`audit_event`, `lot_event`) keep their copy: they cannot change without breaking the
   chain. The audit trail is one chain across its monthly partitions, so even a partition past its retention cannot
   be dropped yet: the nightly verification starts at the first row.

What the system cannot do yet, and increment 15 adds: beneficiary data moves out of chained payloads into a table
referenced by id (or is encrypted under a per-subject key that can be destroyed), so the chain covers the reference
and the data can go; and the verification starts from a signed checkpoint (the daily chain-head digest), so
partitions past their retention can be archived and dropped.

## Who to involve

A request goes to the data protection officer, who decides; the database owner role carries out step 1, and the
change is reviewed by a second person, like any other change to master data.
