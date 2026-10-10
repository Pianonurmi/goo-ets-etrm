-- Regulatory reporting data. The full terms travel in each version's JSON snapshot; the UTI is also a column so that
-- reports and counterparties' references can be looked up, and it is unique per reporting entity.
ALTER TABLE trade ADD COLUMN uti TEXT CHECK (uti ~ '^[A-Z0-9]{1,52}$');
CREATE UNIQUE INDEX trade_uti_uq ON trade (legal_entity_id, uti) WHERE uti IS NOT NULL;

ALTER TABLE trade_leg
    ADD COLUMN futures_contract_code TEXT,
    ADD COLUMN upi TEXT,
    ADD COLUMN isin TEXT;
