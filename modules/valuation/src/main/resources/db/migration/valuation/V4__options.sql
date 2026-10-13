-- Options (ADR 0047): an option leg's result keeps how Black-76 valued it, so the explain can revalue it step by
-- step: type, strike, underlying, forward, volatility (percent a year), years to expiry and the sensitivities per
-- tonne. The option columns go together. The explain gains VOL.

ALTER TABLE valuation_result ADD COLUMN option_type TEXT CHECK (option_type IN ('CALL', 'PUT'));
ALTER TABLE valuation_result ADD COLUMN strike NUMERIC(18, 6);
ALTER TABLE valuation_result ADD COLUMN underlying_contract_code TEXT;
ALTER TABLE valuation_result ADD COLUMN underlying_price NUMERIC(18, 6);
ALTER TABLE valuation_result ADD COLUMN volatility NUMERIC(12, 6);
ALTER TABLE valuation_result ADD COLUMN time_to_expiry NUMERIC(12, 8);
ALTER TABLE valuation_result ADD COLUMN delta NUMERIC(18, 8);
ALTER TABLE valuation_result ADD COLUMN gamma NUMERIC(18, 8);
ALTER TABLE valuation_result ADD COLUMN vega NUMERIC(18, 8);
ALTER TABLE valuation_result ADD COLUMN vol_curve_code TEXT;
ALTER TABLE valuation_result ADD COLUMN vol_snapshot_id UUID;
ALTER TABLE valuation_result ADD CONSTRAINT valuation_result_option_ck CHECK (
    (option_type IS NULL AND strike IS NULL AND underlying_contract_code IS NULL AND underlying_price IS NULL AND volatility IS NULL
        AND time_to_expiry IS NULL AND delta IS NULL AND gamma IS NULL AND vega IS NULL AND vol_curve_code IS NULL AND vol_snapshot_id IS NULL)
    OR (option_type IS NOT NULL AND strike IS NOT NULL AND underlying_contract_code IS NOT NULL AND underlying_price IS NOT NULL
        AND volatility IS NOT NULL AND time_to_expiry IS NOT NULL AND delta IS NOT NULL AND gamma IS NOT NULL AND vega IS NOT NULL
        AND vol_curve_code IS NOT NULL AND vol_snapshot_id IS NOT NULL)
);

ALTER TABLE pnl_explain DROP CONSTRAINT pnl_explain_component_check;
ALTER TABLE pnl_explain ADD CONSTRAINT pnl_explain_component_check CHECK (
    component IN ('NEW', 'CANCELLED', 'DELIVERED', 'AMENDED', 'TIME', 'PRICE', 'VOL', 'RATES', 'FX', 'UNEXPLAINED')
);
