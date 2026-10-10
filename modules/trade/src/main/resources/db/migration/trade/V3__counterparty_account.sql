-- Trade legs reference the counterparty's registry account as a structured record (reference data id); the textual
-- REGISTRY_CODE:account reference is kept for display and for legacy free-text entries.
ALTER TABLE trade_leg ADD COLUMN counterparty_account_id UUID;
