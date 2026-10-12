# Glossary

| Term | Meaning |
|---|---|
| AIB / EECS | Association of Issuing Bodies; European Energy Certificate System, the standard behind GoO registries and the hub |
| Bucket | Standard product definition (criteria) used as the aggregation dimension for positions, marks and scoring |
| Cheapest to deliver | For a GoO sale, the average value per MWh of the certificates that deliver it on the valuation-grade allocation; a management view next to the bucket mark (ADR 0039) |
| Coverage | Max-flow based measure of how much open demand live inventory can satisfy, per obligation |
| Criteria | Typed predicate tree describing which certificates satisfy a delivery |
| EUA / EUAA | EU Allowance (general) / EU Aviation Allowance |
| Expected issuance | Certificates an own device is expected to have issued for a production month: its forecast, or capacity × load factor, less what is already issued; P50 for views, P90 for plans (ADR 0038) |
| Expiry haircut | Part of a certificate's value lost because it expires within 60 days of delivery; using such a lot costs less (ADR 0035) |
| Give-away | The premium handed over when a certificate worth more than the sale's own product delivers it: value of the lot − mark of the sale's bucket |
| GoO | Guarantee of Origin, 1 certificate = 1 MWh |
| Load factor | Typical capacity factor of a technology for a calendar month, central and P90; the fallback when a device has no forecast. The seeded values are illustrative |
| Lot | Contiguous certificate number range with identical attributes in one registry account |
| Lot class | Lots interchangeable for every criterion and for scoring |
| Marginal cost | Shadow price of an obligation: what one more MWh delivered to it would cost, rerouting other sales included (indicative) |
| Naive net | Held certificates of a bucket minus its obligations, ignoring which certificates can serve which obligation; can look long while the bucket is short |
| Obligation | Quantity of certificates to deliver (sale, intercompany) or cancel (disclosure, own consumption) by a date under criteria |
| Plan | Proposed assignment of lots to obligations; approved plans reserve lots and instruct registries |
| Portfolio value | Held certificates at the best price each qualifies for, plus the GoO trades' MtM, less give-away, transfer fees and expiry haircut on the valuation-grade allocation; never booked (ADR 0039) |
| Scarcity rent | Shadow price of supply: how much a plan's cost would fall with one more MWh of that bucket and year (indicative) |
| Shadow price | A dual value of the pricing linear program solved after a plan; marginal costs and scarcity rents, never booked |
| Short after netting | Obligations of a product bucket that its own certificates cannot cover (ADR 0037): what has to be bought or issued |
| Shortage group | Set of obligations jointly short, with the exhausted supply they compete for |
| Soft / hard reservation | Claim by a proposal (expires) / by an approved plan (lot status RESERVED) |
| Statement | Registry export of holdings used for reconciliation |
| Supply tier | How firm supply is: held, contracted (open GoO purchases) or expected (own devices' issuance); figures are cumulative by tier (ADR 0038) |
| Surrender | Delivery of allowances to the Union Registry against verified emissions |
| Transfer route | Feasibility, fee and expected days of moving certificates between registries |
| Value (allocation) | What a certificate is worth to allocation: the best official mark over every bucket it qualifies for |
| Vintage | Production period window of certificates |
| Trusted account list | Union Registry list of accounts an account holder may transfer to without the 26-hour delay; entries activate after four working days |
| Change request | A four-eyes change to sensitive master data, applied when a second person approves it in the inbox |
| Outbox | Instructions stored first and dispatched to the registry afterwards, outside any database transaction |
| Settlement item | What is owed for one recorded delivery: price, net amount and VAT treatment; invoiced later |
| Reverse charge | VAT accounted for by the buyer instead of the seller: domestically for allowances (Art. 199a) or German GoOs, and for cross-border B2B supplies |
| Self-billing | The buyer issues the invoice on the seller's behalf under an agreement |
| Netting statement | Offsets receivables and payables with one counterparty in one currency so that only the net amount is paid |
| Unapplied cash | A received or made payment not yet allocated to invoices |
| Break fingerprint | Identity of a reconciliation discrepancy across statements (account, type, issuance and range, or product and difference) |
| Confirmation break | A counterparty confirmation that differs from ours; overridden or disputed by a second person |
| Obligations-open policy | Whether an entity's trades oblige delivery from approval or only from confirmation |
| Gap-free numbering | Invoice numbers taken in the issuing transaction so that a rollback leaves no hole |
| Curve snapshot | A curve's prices for one date; official once a second person signs it off, superseded by a later official one |
| MtM | Mark-to-market: remaining quantity × (market price − contract price), signed by side |
| P&L explain | The change in MtM between two official valuations split into new, cancelled, delivered, amended, price, FX and unexplained |
| Valuation run | MtM of an entity's open trades on a date; official once signed off by risk |
| NRV | Net realisable value: what inventory would sell for less cost to sell; inventory is carried at the lower of cost and NRV |
| Emission provision | Liability for emissions incurred but not yet surrendered (IAS 37 net liability approach) |
| Installation | An installation or aircraft operator under the EU ETS, with its operator holding account |
| Surrender attribution | The compliance year a surrender counts for: as stated, else the earliest year still owed |
| PFE | Potential future exposure: an add-on for how much a trade's value could move before delivery |
| Credit support | Collateral or guarantees that reduce (received) or add to (posted) credit exposure |
| Pre-trade check | Findings a module (risk) reports on a trade before approval; some require risk's approval |
| UTI / UPI | Unique transaction identifier (ISO 23897) / unique product identifier (ISO 4914) under EMIR |
| NEWT / MODI / TERM / EROR / VALU | EMIR action types: new, modification, termination, error, valuation |
| Ancillary activity | MiFID II test of whether trading allowances is ancillary to the main business |
| Delta export | A GL export carrying only entries posted after the period's earlier exports |
| Hard-close gate | A condition a period must meet before it is closed for good |
| Virtual line | A plan line served by future supply (a purchase or expected issuance): shown and valued, never reserved or instructed (ADR 0038) |
