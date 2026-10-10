-- Inventory on a registry account belongs to a book for position limits (risk, ADR 0022).
ALTER TABLE registry_account ADD COLUMN default_book_id UUID REFERENCES book (id);

-- Credit limits are risk limits now (CREDIT_EXPOSURE, four eyes); the old amount is read-only and only seeds them.
COMMENT ON COLUMN counterparty.credit_limit_amount IS 'Read-only since ADR 0022: seeds CREDIT_EXPOSURE risk limits once';
