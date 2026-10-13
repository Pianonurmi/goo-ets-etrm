-- Discount curves (ADR 0044): zero rates by tenor in one currency, built from quotes of RATE instruments into
-- snapshots signed off like the price curves. Rates are percent a year and may be zero or negative, so the
-- positivity of quotes and curve points is checked per kind instead of per column.

ALTER TABLE instrument ADD COLUMN tenor TEXT;
ALTER TABLE instrument ADD CONSTRAINT instrument_tenor_ck CHECK (
    tenor IS NULL OR (kind = 'RATE' AND tenor ~ '^(ON|[1-9][0-9]*[DWMY])$')
);

ALTER TABLE curve DROP CONSTRAINT curve_kind_check;
ALTER TABLE curve ADD CONSTRAINT curve_kind_check CHECK (kind IN ('EUA_FORWARD', 'GOO_VINTAGE', 'DISCOUNT'));
ALTER TABLE curve ADD COLUMN day_count TEXT CHECK (day_count IN ('ACT_360', 'ACT_365F'));
ALTER TABLE curve DROP CONSTRAINT curve_shape_ck;
ALTER TABLE curve ADD CONSTRAINT curve_shape_ck CHECK (
    (kind = 'EUA_FORWARD' AND allowance_product_code IS NOT NULL AND bucket_code IS NULL AND day_count IS NULL)
    OR (kind = 'GOO_VINTAGE' AND bucket_code IS NOT NULL AND allowance_product_code IS NULL AND day_count IS NULL)
    OR (kind = 'DISCOUNT' AND allowance_product_code IS NULL AND bucket_code IS NULL AND day_count IS NOT NULL)
);
-- One discount curve per currency: valuation finds it by the currency it discounts.
CREATE UNIQUE INDEX curve_discount_uq ON curve (currency) WHERE kind = 'DISCOUNT' AND active;

ALTER TABLE quote DROP CONSTRAINT quote_price_check;
ALTER TABLE curve_point DROP CONSTRAINT curve_point_price_check;

CREATE OR REPLACE FUNCTION quote_price_positive() RETURNS trigger LANGUAGE plpgsql SET search_path FROM CURRENT AS $$
BEGIN
    IF NEW.price <= 0 AND (SELECT kind FROM instrument WHERE code = NEW.instrument_code) <> 'RATE' THEN
        RAISE EXCEPTION 'a price of % must be positive', NEW.instrument_code USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER quote_price_positive
    BEFORE INSERT ON quote
    FOR EACH ROW EXECUTE FUNCTION quote_price_positive();

CREATE OR REPLACE FUNCTION curve_point_price_positive() RETURNS trigger LANGUAGE plpgsql SET search_path FROM CURRENT AS $$
BEGIN
    IF NEW.price <= 0 AND (
        SELECT c.kind FROM curve_snapshot s JOIN curve c ON c.code = s.curve_code WHERE s.id = NEW.snapshot_id
    ) <> 'DISCOUNT' THEN
        RAISE EXCEPTION 'a curve price must be positive' USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER curve_point_price_positive
    BEFORE INSERT ON curve_point
    FOR EACH ROW EXECUTE FUNCTION curve_point_price_positive();
