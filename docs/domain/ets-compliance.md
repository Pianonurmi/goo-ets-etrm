# EU ETS

## Increment 1

- Products: EUA and EUAA (phase 4) active; ETS2 and UKA defined but inactive.
- Holdings: fungible balance per Union Registry account and product with a signed movement journal (free allocation, purchase, sale, transfer, surrender, deletion, correction) and reservations.
- Compliance ladder (position module): held allowances + open purchases − open sales + expected free allocation − emissions (verified, else forecast) = net position; hedge ratio = covered / emissions. Inputs per entity and year are maintained through the API until the compliance module (increment 3) supplies them from installations and verified reports.
- Key dates to model in increment 3: free allocation by 30 June, verified emissions report by 31 March, surrender by 30 September; Union Registry trusted account list and transfer delays; MiFID II / EMIR data for allowance derivatives.

## Increment 2: deliveries through the Union Registry

- Sales are delivered by an `ALLOWANCE_TRANSFER` instruction to the buyer's registered Union Registry account (four eyes here, then executed in the registry). Untrusted accounts settle 26 hours later; the instruction shows `expectedEffectiveAt`. See [registry integration](../integration/registries.md#union-registry-allowance-instructions).
- Surrenders are `SURRENDER` instructions with the compliance year; confirmation records the movement that the ladder and ledger use.
- Purchases are delivered when the allowances arrive with the trade number as reference, or by a manual match.
- Union Registry holdings statements reconcile balances per product (`BALANCE_MISMATCH` breaks).
- Not yet: intercompany allowance trades record the sale on the selling entity and the receipt on the buying entity, but the mirror purchase trade is matched by hand.

## Increment 3

- The compliance ladder counts EU ETS products only (EUA, EUAA) and works per compliance year:
  - obligation = emissions (verified, else forecast) − allowances already surrendered for the year;
  - supply = held + firm purchases − firm sales delivering by the surrender deadline + free allocation still to be
    issued (expected − already received, which is in the holdings);
  - net position = supply − obligation.
- The surrender deadline is 30 September of the following year for compliance years from 2024 (30 April before).
