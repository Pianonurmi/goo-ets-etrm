# Risk

See [ADR 0022](../adr/0022-risk-limits-and-credit-exposure.md) for the decisions.

## Limits

| Type | Scope | Value |
|---|---|---|
| CREDIT_EXPOSURE | counterparty (its group below it); one entity or all | exposure in the limit's currency |
| POSITION_NET | product family, optionally product and book | absolute net quantity: open purchases − open sales + inventory |
| TENOR_MAX_MONTHS | optionally product family and book | months to the last open delivery |
| STOP_LOSS_MTD | optionally book | loss of MtM since the end of last month |
| COMPLIANCE_SHORT | compliance year (default: current) | allowances short in the ETS ladder |
| GOO_CONCENTRATION | product bucket; whole entity | held GoO certificates of the bucket no obligation uses in the flow, MWh or EUR at its mark (ADR 0040) |
| GOO_EXPIRY | horizon in days, optionally a bucket; whole entity | held GoO certificates expiring within it that no obligation uses, MWh |
| GOO_BASIS | whole entity | sales delivered with certificates worth more than their own product, MWh or the give-away handed over in EUR |

- Limits change through `POST /api/v1/risk/limits` (RISK) and the `LIMIT_CHANGE` inbox task (another RISK user).
- Utilisation is OK below the warning level, WARNING from it, and BREACH above the amount. A breach opens a
  `LIMIT_BREACH` task.

## Credit exposure

`exposure = max(0, MtM + receivables − payables (netting only) + PFE − collateral)`, per legal entity and
counterparty in base currency: `GET /api/v1/risk/exposures?legalEntityId=`.

| Component | Source |
|---|---|
| MtM | latest official valuation; positive per trade, or net per counterparty under netting |
| Receivables, payables | settlement: open invoices plus delivered, not invoiced (gross) |
| PFE | remaining notional × add-on % by family and months to delivery (`GET /api/v1/risk/pfe-add-ons`) |
| Collateral | approved credit support (`POST /api/v1/risk/credit-support`, `CREDIT_SUPPORT_CHANGE` task) |

## Pre-trade check

Run on every captured or amended trade; findings are on the trade and in the approval task:

| Code | Severity | When |
|---|---|---|
| CREDIT_LIMIT | REQUIRES_RISK_APPROVAL | last measured exposure + the trade's PFE over a credit limit |
| CREDIT_LIMIT_WARNING | WARN | over the warning level |
| NO_CREDIT_LIMIT | WARN | an external counterparty without a credit limit |
| POSITION_LIMIT | REQUIRES_RISK_APPROVAL | the net position after the trade over a position limit |
| TENOR_LIMIT | REQUIRES_RISK_APPROVAL | the trade delivers beyond a tenor limit |
| LIMIT_IN_BREACH | WARN | a stop loss on the trade's book, the compliance short limit (allowances) or a GoO limit (GoO trades) is in breach |
| GOO_CONCENTRATION | REQUIRES_RISK_APPROVAL / WARN | a GoO purchase of a concentration limit's bucket would take its free certificates over the limit / the warning level |
| GOO_HEADROOM | WARN / INFO | (allocation, ADR 0036) a GoO sale's delivery, or its deliveries together, exceed what held certificates can still serve; INFO "not checked" while the figures are prepared |

A trade with a REQUIRES_RISK_APPROVAL finding needs approval regardless of the threshold, and only RISK may give it.
`POST /api/v1/risk/pre-trade-check?tradeId=` re-runs the checks for an existing trade: risk's own and every other
module's (`TradeApprovalCheck`), as on submission.
