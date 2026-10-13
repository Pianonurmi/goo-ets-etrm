-- Discounted MtM (ADR 0044): each result keeps the payment date, the discount factor and the discount curve snapshot
-- it came from, and the undiscounted value next to the discounted one. Rows of earlier runs have none of these
-- (they were undiscounted: a factor of 1). The P&L explain gains TIME (the old discount curve a day closer to
-- payment) and RATES (the move of the discount curve).

ALTER TABLE valuation_result ADD COLUMN pay_date DATE;
ALTER TABLE valuation_result ADD COLUMN discount_factor NUMERIC(18, 10);
ALTER TABLE valuation_result ADD COLUMN discount_curve_code TEXT;
ALTER TABLE valuation_result ADD COLUMN discount_snapshot_id UUID;
ALTER TABLE valuation_result ADD COLUMN mtm_undiscounted NUMERIC(18, 2);

ALTER TABLE pnl_explain DROP CONSTRAINT pnl_explain_component_check;
ALTER TABLE pnl_explain ADD CONSTRAINT pnl_explain_component_check CHECK (
    component IN ('NEW', 'CANCELLED', 'DELIVERED', 'AMENDED', 'TIME', 'PRICE', 'RATES', 'FX', 'UNEXPLAINED')
);
