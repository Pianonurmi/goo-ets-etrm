# ADR 0015: Obligations open per entity policy; deliveries are idempotent per source

- Status: accepted
- Date: 2026-10-10

## Context
Delivery obligations used to open only when a trade was confirmed. That keeps allocation away from trades whose
terms the counterparty has not yet agreed. Some desks, though, deliver as soon as a trade is approved. They match
confirmations later and must not have allocation blocked meanwhile.

Deliveries are now reported from several places: registry confirmations, statements, receipt matching and
operators. The same delivery can therefore be reported more than once.

## Decision
- Each legal entity sets `obligationsOpenAt`:
  - `CONFIRMED` (the default): obligations open when the trade is confirmed.
  - `APPROVED`: obligations open as soon as the trade is approved.
- Each trade copies the entity's policy when it is captured. A later policy change affects only trades captured after it.
- `TradeView.obligationsOpen` is the single rule. Obligation queries, opening and closing obligations on approval,
  confirmation, amendment and cancellation, and the allowed delivery statuses all follow it.
- Under `APPROVED`, a trade can be delivered while it is still `APPROVED`. A confirmation that arrives after
  delivery started is recorded (reference and time) without changing the delivery status. Obligations are never
  opened twice.
- Every recorded delivery carries an idempotency key that names its source:
  - `instruction:<id>` for registry instructions;
  - `lot-receipt:<lot>` and `holding-receipt:<holding>:<seq>` for receipts.

  Recording the same key again changes nothing and publishes nothing. `DeliveryRecorded` carries the delivery id
  for downstream consumers such as settlement.

## Consequences
- Under `APPROVED`, positions (which count firm trades) and obligations differ until the trade is confirmed. This is
  intended: the desk accepts the risk of delivering before the counterparty has confirmed.
- Listeners that record deliveries (allocation on registry confirmations, receipt matching) run in a savepoint. A
  refused delivery is logged and never rolls back the event that triggered it.
