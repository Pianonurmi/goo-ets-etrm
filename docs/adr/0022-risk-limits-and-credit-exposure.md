# ADR 0022: Risk limits under four eyes, credit exposure from MtM, receivables and PFE, pre-trade check through an SPI

- Status: accepted (the PFE add-ons are placeholders until risk calibrates them)
- Date: 2026-10-10

## Context
- Credit limits were a number on the counterparty, maintained by middle office and read by nothing.
- Risk needs limits that:
  - are approved by a second person;
  - are measured against current figures;
  - raise breaches that someone has to act on;
  - are checked before a trade is approved.
- Credit exposure needs the market value of open trades (valuation), what the counterparty owes and is owed
  (settlement), and what could change before delivery.
- The trade module must not depend on risk. Risk depends on trade, and Spring Modulith refuses cycles.

## Decision
- A new module `risk` (schema `etrm_risk`). It depends on referencedata, product, inventory, trade, marketdata,
  valuation, settlement, position and workflow. Nothing depends on it.
- **Limits** (`risk_limit`) per legal entity, or across all entities for credit:

  | Type | Measures | Unit |
  |---|---|---|
  | `CREDIT_EXPOSURE` | exposure to a counterparty and everything below it in its group | currency |
  | `POSITION_NET` | open bought less sold quantity plus inventory, by product family (and product, and book) | t or MWh |
  | `TENOR_MAX_MONTHS` | months to the last open delivery | months |
  | `STOP_LOSS_MTD` | loss of MtM since the end of last month (P&L explain), optionally per book | currency |
  | `COMPLIANCE_SHORT` | allowances short of the year's obligation (position ladder) | t |

  - Inventory belongs to a book through its registry account's new `default_book_id`.
  - A request (RISK) is `PENDING_APPROVAL` until a `LIMIT_CHANGE` task is approved by someone else in risk. A new
    version names the limit it replaces; the replaced limit is retired and any breach of it ends.
  - Utilisation warns from the warning percentage (default 80) and breaches above the amount.
- **Credit exposure** per entity and counterparty, in base currency:
  max(0, MtM + receivables − netted payables + PFE − collateral).
  - MtM: positive values of the latest official valuation, per trade, or netted over the counterparty when the
    counterparty has a netting agreement (`nettingAgreed`).
  - Receivables and payables: invoiced and still open plus delivered and not yet invoiced, gross of VAT (settlement's
    new `counterpartyBalances`). Payables are set off only under netting.
  - PFE: the remaining notional of open trades (fixed price, or for index legs today's curve price plus the spread)
    times an add-on by product family and months to delivery (`pfe_addon`: allowances 6/10/15 %, certificates
    10/15/20 % up to 3 months, up to 12 months, beyond). The add-ons are stored as uncalibrated data.
  - Collateral: approved credit support. Cash, guarantees and letters of credit received reduce exposure; cash
    posted adds to it. Each item is approved by a second person (`CREDIT_SUPPORT_CHANGE`).
- **Measurement** stores each counterparty's current exposure and each limit's utilisation. It runs:
  - when a valuation is signed off;
  - when a trade is approved, confirmed or cancelled;
  - when a delivery is recorded;
  - when a limit is activated;
  - twice a day on weekdays (job).

  Event-driven measurement runs in a savepoint and never fails the transaction that published the event. A breach
  opens a `LIMIT_BREACH` task: risk acknowledges it with what is being done, and it closes itself when utilisation
  falls back under the limit.
- **Pre-trade check**:
  - `trade.api.TradeApprovalCheck` is an SPI owned by trade. Trade resolves it lazily, so the dependency runs
    risk → trade only.
  - Trade runs every check on capture and amendment and stores the findings on the trade (`approvalFindings`).
  - Risk's check uses the last measured exposure to find the credit headroom left after the new trade's PFE. It also
    flags position and tenor limits the trade would exceed, and limits already in breach.
  - A finding of severity `REQUIRES_RISK_APPROVAL` makes the trade need approval even below the approval threshold.
    Only the RISK role may then approve it.
  - A counterparty without a credit limit gives a warning. Intercompany, exchange and clearing house counterparties
    are not credit-checked.
- **Credit limits leave reference data**:
  - On first start, each counterparty `creditLimit` becomes an active group-wide `CREDIT_EXPOSURE` limit in the
    settlement currency (`risk_seed` makes this run once).
  - Setting a credit limit on a counterparty is refused from then on.

## Consequences
- An exposure is only as current as its last measurement. The pre-trade check does not re-read settlement, so
  receivables changed since then are seen at the next measurement.
- Not modelled: wrong-way risk, exposure profiles over time, margining of exchange trades (variation margin since ADR 0046; the exposure follows in increment 12 M3), limits on concentration
  and on uncovered expiring certificates (planned for a later increment), and bank holidays in tenors.

## Amendment (ADR 0040)
Three GoO limit types measure the book netted through the eligibility flow: concentration by product bucket,
unused certificates expiring within a horizon, and basis (give-away handed over). They apply to a whole legal entity,
never to a book.
