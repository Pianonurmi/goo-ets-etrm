# Trade confirmations

Both parties confirm a trade's economic terms after EFET practice. We issue our confirmation. The counterparty sends
theirs. The two are matched field by field.

## Our confirmation

When an external trade is approved, the system issues our confirmation for that trade version:
- `ConfirmationTerms` as JSON;
- a PDF (template `trade-confirmation`, stored immutable in the `document` module).

The terms cover:
- our trade number and the counterparty's trade id, the trade date, the product family and the master agreement;
- per leg:
  - seller and buyer (name and LEI);
  - product;
  - quantity and unit, price, currency, and amount (quantity × price, two decimals);
  - deliveries (date, quantity, payment date);
  - both registry accounts;
  - for guarantees of origin, the product attributes taken from the criterion: region or countries, energy source,
    technology, vintage, support, labels.

A criterion that is not a simple conjunction is stated as a whole (`CRITERION`).

Intercompany trades get no confirmation. Re-issuing with unchanged terms returns the current confirmation. An
amendment withdraws it (`SUPERSEDED`), and re-approval issues the next one.

Endpoints:
- `GET /api/v1/trades/{id}/confirmations` lists our confirmations and theirs.
- `POST /api/v1/trades/{id}/confirmations` issues ours or returns the current one.
- The PDF is downloaded with `GET /api/v1/documents/{documentId}/content`.

## The counterparty's confirmation

It arrives in one of three ways:
- **JSON or manual entry:** `POST /api/v1/confirmations/counterparty` with the terms, their confirmation reference
  and `source`.
- **CSV:** `POST /api/v1/confirmations/counterparty/csv?counterpartyId=`. There is one line per delivery; lines
  with the same `confirmation_ref` form one confirmation. The columns are:
  - `our_trade_ref`, `counterparty_trade_ref`, `trade_date`, `product_family`;
  - `seller_lei`, `buyer_lei`, `seller_name`, `buyer_name`;
  - `product`, `quantity`, `unit`, `price`, `currency`, `amount`;
  - `delivery_date`, `payment_date`;
  - `seller_account`, `buyer_account`;
  - `attr_<name>` for product attributes.

The trade is found by our trade number, or by the counterparty and their trade id (`externalRef` on our trade). A
reference that was already received returns the earlier result. A newer confirmation supersedes the previous one.

## Matching

| Field | Rule |
|---|---|
| trade date, product family, our trade number (when quoted) | equal |
| product, unit, currency, registry accounts, attributes | equal, ignoring case and spacing |
| quantity, price, amount | within the tolerance of the product family |
| delivery dates | within the tolerance in days (default 0) |
| LEIs | equal when both are given; missing on their side is a note |
| their trade id, master agreement | a note when they differ |

Tolerances are configured in `etrm.trade.confirmation.tolerances.<FAMILY>.{price,amount,quantity,delivery-date-days}`.
The defaults are:
- GoO: price 0.0001, amount 0.01.
- EUA: price 0.001, amount 0.01.

`MATCHED` confirms the trade as `system` with their reference, unless it is already confirmed.

`MISMATCHED` opens a `CONFIRMATION_BREAK` task listing the differing fields. A second person (not the one who
received the confirmation) decides it:
- **Approve:** the confirmation is `OVERRIDDEN` and the trade is confirmed. The database refuses an override by the
  receiver.
- **Reject:** the confirmation is `DISPUTED` and the trade stays unconfirmed.

If the trade is amended, open matches go back to `RECEIVED` and are matched again against the next confirmation of
ours.

The existing manual confirmation (`POST /api/v1/trades/{id}/confirm`) remains available.

## Obligations-open policy

A legal entity's `obligationsOpenAt` (`CONFIRMED` by default, or `APPROVED`) decides when its trades oblige delivery.
See ADR 0015. Under `APPROVED`, allocation and deliveries can proceed while confirmation matching is still pending.
