# VAT

VAT rules are data (`etrm_settlement.vat_rule`), effective-dated, and approved by a second person. The seeds below
are **not tax advice**: the user's tax adviser must sign them off before production (`requires_sign_off`).

## Determination

1. The supplier is our entity on sales and the counterparty on purchases. Countries are the parties' countries of
   establishment.
2. The scope follows from the two countries:
   - `DOMESTIC`: same country;
   - `INTRA_EU`: two different EU countries;
   - `IMPORT`: from outside the EU to the EU;
   - `EXPORT`: from the EU to outside the EU.
3. Candidate rules are the approved rules valid on the tax point (the delivery date) whose product family, supplier
   country and scope match, where `*` and `ANY` match anything.
4. The most specific candidate wins: a named product family scores 4, a named supplier country 2, a named scope 1.
   A tie is an error, and the item needs attention.
5. Cross-border reverse charge requires the customer's VAT number.

## Seeded rules

| Code | Supply | Treatment | Valid |
|---|---|---|---|
| EUA-RC-FI / SE / DE | allowances, domestic | domestic reverse charge (Art. 199a) | to 31 Dec 2026 |
| EUA-STD-FI / SE / DE | allowances, domestic | standard 25.5 % / 25 % / 19 % | from 1 Jan 2027, unless Art. 199a is extended (a new rule version) |
| GOO-RC-DE | guarantees of origin, domestic DE | domestic reverse charge (§ 13b UStG) | open |
| GOO-STD-FI v1 / v2 | guarantees of origin, domestic FI | standard 24 % / 25.5 % | to / from 1 Sep 2024 |
| GOO-STD-SE | guarantees of origin, domestic SE | standard 25 % | open |
| B2B-INTRA-EU | any, EU to another EU country | reverse charge (Art. 44, 196) | open |
| B2B-IMPORT | any, from outside the EU | reverse charge (Art. 44, 196) | open |
| B2B-EXPORT | any, to outside the EU | out of scope (Art. 44) | open |

Other domestic supplies (e.g. Denmark) have no rule until one is added.

## Changing rules

1. `POST /api/v1/settlement/vat-rules` proposes a rule, or the next version of a code. Its status is DRAFT and a
   `VAT_RULE_APPROVAL` task opens.
2. A second accountant approves it. Rejecting retires the draft.
3. End a rule by adding a version with the new period; never edit an approved one.

Use `GET /api/v1/settlement/vat-rules/determination` to see which rule a supply would get.

## On invoices

- Reverse-charged lines print "Reverse charge" with the rule's legal text.
- Self-billed invoices print "Self-billing".
- VAT is computed per line, rounded half up to cents.
