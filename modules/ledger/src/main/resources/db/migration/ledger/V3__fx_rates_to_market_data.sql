-- FX rates now live in market data (etrm_marketdata.fx_rate), which migrates before the ledger. Rates entered
-- through the ledger are carried over as manual rates; the ledger's own table is dropped.
DO $$
BEGIN
    IF to_regclass('etrm_marketdata.fx_rate') IS NOT NULL THEN
        INSERT INTO etrm_marketdata.fx_rate (id, rate_date, base_currency, quote_currency, rate, source, recorded_by)
        SELECT gen_random_uuid(), f.rate_date, f.from_currency, f.to_currency, f.rate, f.source, 'ledger-migration'
        FROM fx_rate f
        WHERE NOT EXISTS (
            SELECT 1 FROM etrm_marketdata.fx_rate m
            WHERE m.rate_date = f.rate_date AND m.base_currency = f.from_currency AND m.quote_currency = f.to_currency
              AND m.source = f.source AND m.superseded_at IS NULL
        );
    END IF;
END;
$$;

DROP TABLE fx_rate;
