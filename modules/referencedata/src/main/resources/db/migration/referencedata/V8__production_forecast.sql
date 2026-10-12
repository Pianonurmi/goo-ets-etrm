-- Production forecasts (ADR 0038): what a device is expected to produce per month, with a P90 figure, and the
-- technology load factors used where no forecast has been entered. Forecasts are versioned; a superseded row is
-- kept in an append-only history.

ALTER TABLE production_device
    ADD COLUMN issuance_lag_days INT NOT NULL DEFAULT 30 CHECK (issuance_lag_days >= 0);

CREATE TABLE production_forecast (
    device_id    UUID        NOT NULL REFERENCES production_device (id),
    month        DATE        NOT NULL CHECK (month = date_trunc('month', month)::date),
    expected_mwh BIGINT      NOT NULL CHECK (expected_mwh >= 0),
    p90_mwh      BIGINT      NOT NULL CHECK (p90_mwh >= 0),
    source       TEXT        NOT NULL CHECK (source IN ('ENTERED', 'IMPORTED')),
    version      INT         NOT NULL DEFAULT 1,
    recorded_by  TEXT        NOT NULL,
    recorded_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (device_id, month),
    CONSTRAINT production_forecast_p90_ck CHECK (p90_mwh <= expected_mwh)
);

-- Every superseded row, as it stood until the change that replaced it.
CREATE TABLE production_forecast_history (
    device_id     UUID        NOT NULL,
    month         DATE        NOT NULL,
    version       INT         NOT NULL,
    expected_mwh  BIGINT      NOT NULL,
    p90_mwh       BIGINT      NOT NULL,
    source        TEXT        NOT NULL,
    recorded_by   TEXT        NOT NULL,
    recorded_at   TIMESTAMPTZ NOT NULL,
    superseded_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (device_id, month, version)
);

-- Copies the row an update replaces into the history, in the same statement and transaction as the update.
CREATE OR REPLACE FUNCTION production_forecast_keep_history() RETURNS trigger LANGUAGE plpgsql SET search_path FROM CURRENT AS $$
BEGIN
    INSERT INTO production_forecast_history (device_id, month, version, expected_mwh, p90_mwh, source, recorded_by, recorded_at)
    VALUES (OLD.device_id, OLD.month, OLD.version, OLD.expected_mwh, OLD.p90_mwh, OLD.source, OLD.recorded_by, OLD.recorded_at);
    RETURN NEW;
END;
$$;

CREATE TRIGGER production_forecast_keep_history
    BEFORE UPDATE ON production_forecast
    FOR EACH ROW EXECUTE FUNCTION production_forecast_keep_history();

CREATE OR REPLACE FUNCTION production_forecast_history_append_only() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'production forecast history is append-only';
END;
$$;

CREATE TRIGGER production_forecast_history_append_only
    BEFORE UPDATE OR DELETE ON production_forecast_history
    FOR EACH ROW EXECUTE FUNCTION production_forecast_history_append_only();

-- Typical capacity factor per technology and calendar month: the central figure and the P90 one (the figure
-- production should exceed nine years in ten).
CREATE TABLE load_factor (
    technology_code TEXT          NOT NULL,
    month_of_year   INT           NOT NULL CHECK (month_of_year BETWEEN 1 AND 12),
    factor          NUMERIC(5, 4) NOT NULL CHECK (factor BETWEEN 0 AND 1),
    p90_factor      NUMERIC(5, 4) NOT NULL CHECK (p90_factor BETWEEN 0 AND 1),
    PRIMARY KEY (technology_code, month_of_year),
    CONSTRAINT load_factor_p90_ck CHECK (p90_factor <= factor)
);

-- ILLUSTRATIVE DEFAULTS, NOT SOURCED DATA. These are rough monthly capacity factors for a Nordic climate (wind
-- strongest in winter, solar near zero in winter and highest in June, run-of-river with a spring flood, storage
-- hydro fairly flat, nuclear flat, biomass-fired steam turbines and CHP higher in winter) with P90 at 0.75 x the
-- central figure. Replace them with your own figures (PUT /api/v1/reference/load-factors/{technology}/{month})
-- before relying on an expected-production figure for a position.
INSERT INTO load_factor (technology_code, month_of_year, factor, p90_factor)
SELECT t.code, m, t.f[m], round(t.f[m] * 0.75, 4)
FROM (VALUES
    ('WIND_TURBINE',              ARRAY[0.38, 0.36, 0.34, 0.28, 0.24, 0.21, 0.20, 0.22, 0.27, 0.33, 0.37, 0.40]),
    ('WIND_TURBINE_ONSHORE',      ARRAY[0.38, 0.36, 0.34, 0.28, 0.24, 0.21, 0.20, 0.22, 0.27, 0.33, 0.37, 0.40]),
    ('WIND_TURBINE_OFFSHORE',     ARRAY[0.50, 0.47, 0.44, 0.37, 0.32, 0.29, 0.28, 0.31, 0.37, 0.44, 0.49, 0.52]),
    ('HYDRO_TURBINE',             ARRAY[0.40, 0.40, 0.38, 0.34, 0.34, 0.35, 0.34, 0.34, 0.36, 0.38, 0.40, 0.41]),
    ('HYDRO_TURBINE_STORAGE',     ARRAY[0.40, 0.40, 0.38, 0.34, 0.34, 0.35, 0.34, 0.34, 0.36, 0.38, 0.40, 0.41]),
    ('HYDRO_TURBINE_ROR',         ARRAY[0.30, 0.27, 0.30, 0.50, 0.65, 0.60, 0.45, 0.38, 0.38, 0.37, 0.34, 0.32]),
    ('SOLAR_PV',                  ARRAY[0.010, 0.030, 0.080, 0.130, 0.170, 0.180, 0.180, 0.140, 0.090, 0.040, 0.010, 0.005]),
    ('SOLAR_PV_ROOFTOP',          ARRAY[0.010, 0.030, 0.080, 0.130, 0.170, 0.180, 0.180, 0.140, 0.090, 0.040, 0.010, 0.005]),
    ('SOLAR_PV_GROUND',           ARRAY[0.010, 0.030, 0.080, 0.130, 0.170, 0.180, 0.180, 0.140, 0.090, 0.040, 0.010, 0.005]),
    ('NUCLEAR_REACTOR',           ARRAY[0.85, 0.85, 0.85, 0.85, 0.85, 0.85, 0.85, 0.85, 0.85, 0.85, 0.85, 0.85]),
    ('THERMAL_STEAM_TURBINE',     ARRAY[0.60, 0.58, 0.54, 0.48, 0.42, 0.38, 0.36, 0.38, 0.44, 0.50, 0.56, 0.60]),
    ('THERMAL_STEAM_TURBINE_CHP', ARRAY[0.60, 0.58, 0.54, 0.48, 0.42, 0.38, 0.36, 0.38, 0.44, 0.50, 0.56, 0.60]),
    ('GEOTHERMAL_PLANT',          ARRAY[0.80, 0.80, 0.80, 0.80, 0.80, 0.80, 0.80, 0.80, 0.80, 0.80, 0.80, 0.80])
) AS t(code, f)
CROSS JOIN generate_series(1, 12) AS m;
