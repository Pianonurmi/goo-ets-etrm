# 6. Runtime view

## Golden path: GoO sale from capture to ledger

```mermaid
sequenceDiagram
  participant Tr as Trader
  participant T as trade
  participant W as workflow
  participant A as allocation
  participant I as inventory
  participant R as registry
  participant L as ledger
  Tr->>T: capture(sale, criteria, deliveries)
  T-->>W: TradeCaptured (PENDING_APPROVAL) → approval task
  Note over W: second user approves (segregation of duties)
  W->>T: approve
  Tr->>T: confirm(counterparty confirmation)
  T-->>A: DeliveryObligationOpened
  T-->>L: TradeConfirmed → commitment memo posting
  Tr->>A: propose(plan)
  A->>I: snapshot(live lots)
  A->>A: classify, eligibility, score, greedy solve, coverage
  A-->>W: AllocationProposed → approval task
  W->>A: approve (second user)
  A->>I: reserve lots (splits as needed)
  A->>R: submitTransfer(lot, target account)
  R->>I: startTransfer
  R->>R: gateway executes (API / file / manual)
  R->>I: confirmTransferOut
  R-->>A: InstructionConfirmed
  A->>T: recordDelivery
  A-->>L: AllocationLineConfirmed → revenue and COGS postings
  I-->>L: LotTransferred
```

## Backdated correction (bitemporal)

A label certificate arrives in October for a lot received in February, effective from January: the correction is
recorded with `effectiveAt = 2026-01-01`. The version trigger closes the current row in transaction time, re-records
the superseded version for valid time before January, and inserts the new version from January. Queries "as known
before October" still see the old labels; queries "as known now, effective December 2025" see the old labels; queries
"as known now, effective today" see the new labels.

## Registry failure

A transfer rejected by the registry returns the lot from `IN_TRANSFER` to `RESERVED`, marks the allocation line
`FAILED`, and leaves the plan `INSTRUCTED`. The operator retries the instruction or cancels the plan, which releases the
remaining reservations.
