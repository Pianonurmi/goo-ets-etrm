-- Options on EUA futures (ADR 0047): the option's terms on its leg; an option leg has no delivery schedule.
ALTER TABLE trade_leg ADD COLUMN option_type TEXT CHECK (option_type IN ('CALL', 'PUT'));
ALTER TABLE trade_leg ADD COLUMN strike NUMERIC(18, 6) CHECK (strike > 0);
ALTER TABLE trade_leg ADD COLUMN expiry_date DATE;
ALTER TABLE trade_leg ADD COLUMN underlying_contract_code TEXT;
ALTER TABLE trade_leg ADD CONSTRAINT trade_leg_option_terms_ck CHECK (
    (option_type IS NULL AND strike IS NULL AND expiry_date IS NULL AND underlying_contract_code IS NULL)
    OR (option_type IS NOT NULL AND strike IS NOT NULL AND expiry_date IS NOT NULL AND underlying_contract_code IS NOT NULL)
);
-- The expiry job finds the options expiring on a day.
CREATE INDEX trade_leg_expiry_idx ON trade_leg (expiry_date) WHERE expiry_date IS NOT NULL;
