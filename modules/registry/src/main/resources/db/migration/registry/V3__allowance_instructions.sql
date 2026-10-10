-- Instructions for fungible allowances (Union Registry): transfers of a quantity of a product from a holding and
-- surrenders for compliance. Certificate lots keep their range; allowance instructions reference the holding.

ALTER TABLE registry_instruction DROP CONSTRAINT registry_instruction_instruction_type_check;
ALTER TABLE registry_instruction ADD CONSTRAINT registry_instruction_instruction_type_check
    CHECK (instruction_type IN ('TRANSFER', 'CANCELLATION', 'ALLOWANCE_TRANSFER', 'SURRENDER'));

ALTER TABLE registry_instruction ADD COLUMN asset_kind TEXT NOT NULL DEFAULT 'GOO_LOT' CHECK (asset_kind IN ('GOO_LOT', 'ALLOWANCE'));
ALTER TABLE registry_instruction ADD COLUMN product_code TEXT;
ALTER TABLE registry_instruction ADD COLUMN holding_ref UUID;
ALTER TABLE registry_instruction ADD COLUMN unit TEXT NOT NULL DEFAULT 'MWH' CHECK (unit IN ('MWH', 'TCO2E'));
ALTER TABLE registry_instruction ALTER COLUMN lot_id DROP NOT NULL;
ALTER TABLE registry_instruction ALTER COLUMN cert_no_start DROP NOT NULL;
ALTER TABLE registry_instruction ALTER COLUMN cert_no_end DROP NOT NULL;
ALTER TABLE registry_instruction RENAME COLUMN quantity_mwh TO quantity;

-- What the confirmation records on the holding (SALE with the trade price, TRANSFER_OUT, SURRENDER for a year).
ALTER TABLE registry_instruction ADD COLUMN movement_type TEXT;
ALTER TABLE registry_instruction ADD COLUMN unit_price NUMERIC(18, 6);
ALTER TABLE registry_instruction ADD COLUMN price_currency CHAR(3);
ALTER TABLE registry_instruction ADD COLUMN compliance_year INT;
-- When the registry is expected to execute (Union Registry: 26 hours later for accounts not on the trusted list).
ALTER TABLE registry_instruction ADD COLUMN expected_effective_at TIMESTAMPTZ;

ALTER TABLE registry_instruction ADD CONSTRAINT registry_instruction_asset_shape CHECK (
    (asset_kind = 'GOO_LOT' AND lot_id IS NOT NULL AND cert_no_start IS NOT NULL AND cert_no_end IS NOT NULL AND unit = 'MWH')
    OR (asset_kind = 'ALLOWANCE' AND lot_id IS NULL AND product_code IS NOT NULL AND holding_ref IS NOT NULL AND unit = 'TCO2E'));
CREATE INDEX registry_instruction_holding_idx ON registry_instruction (holding_ref) WHERE holding_ref IS NOT NULL;

INSERT INTO statement_format (id, registry_code, profile_code, kind, column_mapping, updated_by) VALUES
    ('01900000-0000-7000-8000-000000000003', '*', 'GENERIC', 'ALLOWANCE_HOLDINGS', '{"productCode":"product_code","quantity":"quantity"}', 'migration');
