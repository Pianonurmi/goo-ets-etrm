# ADR 0029: Selected domain events go to Kafka from the publication registry

- Status: accepted
- Date: 2026-10-11

## Context
Other systems need the application's business events: trade lifecycle, deliveries, issued invoices and payments,
registry outcomes, certificate receipts and transfers, journal entries and limit breaches. Typical consumers are a
data platform, the treasury system and surveillance. Inside the application, events stay synchronous
(ADR 0004).

Sending to a broker inside the command transaction would make every command depend on the broker. Sending after
the commit without a record could lose events.

## Decision
Spring Modulith's externalisation sends a selected set of events to Kafka after the producing transaction
commits. The event publication registry (`etrm_platform.event_publication`) records each one in the same
transaction, so an event is never lost.

- **Selection and routing:** `ExternalEvents` in `apps/api` selects the events, picks the topic per area
  (`etrm.trade`, `etrm.settlement`, `etrm.registry`, `etrm.inventory`, `etrm.ledger`, `etrm.risk`) and uses the
  aggregate id as the key, so one trade's or lot's events stay in order in one partition. Modules do not depend on
  Kafka.
- **Message:** the event as JSON from the application's mapper, with the headers `etrm-event-type`,
  `etrm-event-id` (consumers deduplicate on it) and `etrm-occurred-at`.
- **Off by default:** `ETRM_KAFKA_ENABLED=true` with `ETRM_KAFKA_BOOTSTRAP_SERVERS` turns it on. When it is on,
  the application creates the topics: six partitions by default, replication from `etrm.events.replicas`.
- **Producer:** idempotent, `acks=all`.
- **Recovery:**
  - A publication that has not completed within 15 minutes, because the broker was down or a send failed, is sent
    again by `EventPublicationMaintenance` (ShedLock, every ten minutes).
  - Incomplete publications are also republished on restart.
  - Completed publications are deleted after seven days.
  - The gauge `etrm.events.publications.incomplete` is the alerting signal.

## Consequences
- Delivery is at least once: consumers must be idempotent on `etrm-event-id`.
- An event's JSON is a public contract. Renaming a field of a view used in an externalised event breaks consumers,
  so treat these views like the OpenAPI document (ADR 0027).
- `KafkaExternalizationIT` runs a broker in Testcontainers, so CI needs Docker; the self-hosted runner has it
  (ADR 0041), as GitHub's hosted runners do.
