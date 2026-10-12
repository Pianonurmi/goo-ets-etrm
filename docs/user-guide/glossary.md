# Glossary

Terms used on the screens and in this manual.

| Term | Meaning |
|---|---|
| AIB / EECS | Association of Issuing Bodies; European Energy Certificate System, the standard behind GoO registries and the hub |
| Ancillary activity | MiFID II test of whether trading allowances is ancillary to the main business |
| Book | A portfolio of trades within a legal entity, e.g. trading or own consumption |
| Break fingerprint | Identity of a reconciliation discrepancy across statements (account, type, issuance and range, or product and difference) |
| Bucket | Standard product definition (criteria) used as the aggregation dimension for positions, marks and scoring |
| Change request | A four-eyes change to sensitive master data, applied when a second person approves it in the inbox |
| Cheapest to deliver | For a GoO sale, the average value per MWh of the certificates that would deliver it; shown next to the official mark, never booked |
| Concentration (GoO) | Certificates of one product held but not needed for any sale: the book's open exposure to that product, watched by a GoO concentration limit |
| Confirmation break | A counterparty confirmation that differs from ours; overridden or disputed by a second person |
| Coverage | Max-flow based measure of how much open demand live inventory can satisfy, per obligation |
| Credit support | Collateral or guarantees that reduce (received) or add to (posted) credit exposure |
| Criteria | Typed predicate tree describing which certificates satisfy a delivery |
| Curve | Forward prices by delivery period for one product, built from market quotes; an official snapshot needs a second person |
| Curve snapshot | A curve's prices for one date; official once a second person signs it off, superseded by a later official one |
| Delta export | A GL export carrying only entries posted after the period's earlier exports |
| Emission provision | Liability for emissions incurred but not yet surrendered (IAS 37 net liability approach) |
| EUA / EUAA | EU Allowance (general) / EU Aviation Allowance |
| Expected issuance | Certificates an own device is expected to have issued for a production month: its forecast, or capacity × load factor, less what is already issued. Views use the central figure (P50), plans the cautious one (P90) |
| Expiry haircut | Part of a certificate's value lost because it expires within 60 days of delivery; using such a lot costs less (ADR 0035) |
| Four eyes | A step that needs a second person: the requester can never approve their own request |
| Gap-free numbering | Invoice numbers taken in the issuing transaction so that a rollback leaves no hole |
| Give-away | The premium handed over when a certificate worth more than the sale's own product delivers it: value of the lot − mark of the sale's bucket |
| GoO | Guarantee of Origin, 1 certificate = 1 MWh |
| Hard-close gate | A condition a period must meet before it is closed for good |
| Index pricing | A price set later from an index fixing plus a spread agreed in the trade |
| Installation | An installation or aircraft operator under the EU ETS, with its operator holding account |
| Intercompany trade | A trade between two of our own legal entities; the mirror trade is booked in the other entity |
| Legal entity | One of our companies; data is scoped to the entities a user may access |
| Load factor | Typical share of a technology's capacity produced in a calendar month; used when a device has no forecast. The values shipped are illustrative |
| Lot | Contiguous certificate number range with identical attributes in one registry account |
| Lot class | Lots interchangeable for every criterion and for scoring |
| Marginal cost | Shadow price of an obligation: what one more MWh delivered to it would cost, rerouting other sales included (indicative) |
| MtM | Mark-to-market: remaining quantity × (market price − contract price), signed by side |
| MtM | Mark to market: the value of a trade at today's market prices against its contract price |
| Naive net | Held certificates of a bucket minus its obligations, ignoring which certificates can serve which obligation; can look long while the bucket is short |
| Netting statement | Offsets receivables and payables with one counterparty in one currency so that only the net amount is paid |
| NEWT / MODI / TERM / EROR / VALU | EMIR action types: new, modification, termination, error, valuation |
| NRV | Net realisable value: what inventory would sell for less cost to sell; inventory is carried at the lower of cost and NRV |
| Obligation | Quantity of certificates to deliver (sale, intercompany) or cancel (disclosure, own consumption) by a date under criteria |
| Obligations-open policy | Whether an entity's trades oblige delivery from approval or only from confirmation |
| Outbox | Instructions stored first and dispatched to the registry afterwards, outside any database transaction |
| P&L explain | The change in MtM between two official valuations split into new, cancelled, delivered, amended, price, FX and unexplained |
| Persona | In the demo, a person with a role that you act as |
| PFE | Potential future exposure: an add-on for how much a trade's value could move before delivery |
| Plan | Proposed assignment of lots to obligations; approved plans reserve lots and instruct registries |
| Portfolio value | What the GoO book is worth as a whole: certificates held at the best price each qualifies for, plus the trades' MtM, less what delivering the sales gives away and costs |
| Pre-trade check | Findings a module (risk) reports on a trade before approval; some require risk's approval |
| Reverse charge | VAT accounted for by the buyer instead of the seller: domestically for allowances (Art. 199a) or German GoOs, and for cross-border B2B supplies |
| Scarcity rent | Shadow price of supply: how much a plan's cost would fall with one more MWh of that bucket and year (indicative) |
| Self-billing | The buyer issues the invoice on the seller's behalf under an agreement |
| Settlement item | What is owed for one recorded delivery: price, net amount and VAT treatment; invoiced later |
| Shadow price | A dual value of the pricing linear program solved after a plan; marginal costs and scarcity rents, never booked |
| Short after netting | Obligations of a product bucket that its own certificates cannot cover (ADR 0037): what has to be bought or issued |
| Shortage group | Set of obligations jointly short, with the exhausted supply they compete for |
| Soft / hard reservation | Claim by a proposal (expires) / by an approved plan (lot status RESERVED) |
| Statement | Registry export of holdings used for reconciliation |
| Supply tier | How firm supply is: held, + purchases (contracted) or + expected issuance. Figures add up cumulatively by tier |
| Surrender | Delivery of allowances to the Union Registry against verified emissions |
| Surrender attribution | The compliance year a surrender counts for: as stated, else the earliest year still owed |
| Transfer route | Feasibility, fee and expected days of moving certificates between registries |
| Trusted account list | Union Registry list of accounts an account holder may transfer to without the 26-hour delay; entries activate after four working days |
| Unapplied cash | A received or made payment not yet allocated to invoices |
| Union Registry | The EU registry where emission allowances are held and transferred |
| UTI | Unique trade identifier for regulatory (EMIR) reporting |
| UTI / UPI | Unique transaction identifier (ISO 23897) / unique product identifier (ISO 4914) under EMIR |
| Valuation run | MtM of an entity's open trades on a date; official once signed off by risk |
| Value (allocation) | What a certificate is worth to allocation: the best official mark over every bucket it qualifies for |
| Vintage | Production period window of certificates |
| Virtual line | A plan line served by a purchase or by expected issuance: shown and valued, but not reserved or instructed until the certificates are held |
