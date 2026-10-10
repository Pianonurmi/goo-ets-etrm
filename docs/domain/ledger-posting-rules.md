# Posting rules

Facts (`PostingFacts`): `amounts` (`cost`, `proceeds`, `notional`, `quantity`), `attributes` (`acquisitionSource`,
`side`, `movementType`, `allowanceClassification`, `freeAllocationPolicy`, derived `hasCost`, `hasProceeds`), `dimensions`
(trade, lot, device, counterparty, registry account, bucket).

| Rule | Event | Condition | Debit | Credit |
|---|---|---|---|---|
| GOO_ISSUANCE | LotReceived | ISSUANCE, cost | 1400 Inventory GoO | 4400 Own generation |
| GOO_PURCHASE_RECEIPT | LotReceived | TRANSFER_IN, cost | 1400 | 2200 Accrued payables |
| GOO_SALE_DELIVERY_REVENUE | SaleDelivered | proceeds | 1500 Accrued receivables | 3000 Revenue |
| GOO_SALE_DELIVERY_COGS | SaleDelivered | cost | 4000 COGS | 1400 |
| GOO_DISCLOSURE_CANCELLATION | LotCancelled | cost | 4100 Disclosure cost | 1400 |
| GOO_EXPIRY_WRITE_OFF | LotExpired | cost | 4300 Write-off | 1400 |
| TRADE_SALE_COMMITMENT / TRADE_PURCHASE_COMMITMENT | TradeConfirmed | side | 9000 / 9001 memo | 9009 memo contra |
| EUA_PURCHASE_INTANGIBLE / _INVENTORY | HoldingChanged | PURCHASE, classification | 1610 / 1410 | 2200 |
| EUA_FREE_ALLOCATION_FAIR_VALUE | HoldingChanged | FREE_ALLOCATION, FAIR_VALUE | 1610 | 2400 Deferred income |
| EUA_SURRENDER | HoldingChanged | SURRENDER, cost | 2300 Emission provision | 1610 |
| EUA_SALE_COST / EUA_SALE_PROCEEDS | HoldingChanged | SALE | 4010 / 1500 | 1610 / 3010 |

Costing: certificates use specific identification (lot cost basis). Allowances use cost layers per account and
product consumed FIFO or weighted average per the entity's `allowanceCostingMethod`; nominal free allocation creates a
zero-cost layer, fair-value free allocation a layer at the fair value supplied with the movement.

Periods: `OPEN` accepts all postings; `SOFT_CLOSED` accepts manual adjustments and reversals; `CLOSED` accepts nothing
and cannot be reopened. Reversals of entries in closed periods post on the current date.
