-- Implied volatility (ADR 0047): VOLATILITY quotes in percent a year on futures instruments, and EUA_VOLATILITY curves
-- per allowance product with a pillar at each future's last trading day. Volatility is positive, so the positivity
-- triggers of V3 apply unchanged. A price index never fixes on a volatility.

ALTER TABLE quote DROP CONSTRAINT quote_quote_type_check;
ALTER TABLE quote ADD CONSTRAINT quote_quote_type_check CHECK (
    quote_type IN ('SETTLEMENT', 'MID', 'BID', 'ASK', 'CLOSE', 'FIXING', 'VOLATILITY')
);

ALTER TABLE curve DROP CONSTRAINT curve_kind_check;
ALTER TABLE curve ADD CONSTRAINT curve_kind_check CHECK (kind IN ('EUA_FORWARD', 'GOO_VINTAGE', 'DISCOUNT', 'EUA_VOLATILITY'));
ALTER TABLE curve DROP CONSTRAINT curve_shape_ck;
ALTER TABLE curve ADD CONSTRAINT curve_shape_ck CHECK (
    (kind IN ('EUA_FORWARD', 'EUA_VOLATILITY') AND allowance_product_code IS NOT NULL AND bucket_code IS NULL AND day_count IS NULL)
    OR (kind = 'GOO_VINTAGE' AND bucket_code IS NOT NULL AND allowance_product_code IS NULL AND day_count IS NULL)
    OR (kind = 'DISCOUNT' AND allowance_product_code IS NULL AND bucket_code IS NULL AND day_count IS NOT NULL)
);
-- One volatility curve per product: valuation finds it by the option's product.
CREATE UNIQUE INDEX curve_volatility_uq ON curve (allowance_product_code) WHERE kind = 'EUA_VOLATILITY' AND active;
