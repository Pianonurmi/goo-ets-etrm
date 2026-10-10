# ADR 0006: Certificates stored as contiguous ranges (lots)

- Status: accepted
- Date: 2026-10-10

## Context
Issuances arrive as ranges of certificate numbers sharing every attribute; a company handles millions of certificates
a year. One row per certificate would be slow and pointless.

## Decision
A lot is a contiguous range in one registry account from one issuance. Partial allocation splits a lot into children
(the parent closes with status `SPLIT`). The database enforces `EXCLUDE USING gist (issuance_ref WITH =, cert_range WITH &&)`
over live statuses so two live lots can never overlap. Attributes that criteria query are promoted, indexed columns;
the rest lives in JSONB.

## Consequences
- Splits are append-style: history of the range is traceable through `parent_lot_id` and `root_lot_id`.
- The allocation engine groups lots into classes (same attributes, account, expiry, cost basis) and only disaggregates when it writes a plan.
