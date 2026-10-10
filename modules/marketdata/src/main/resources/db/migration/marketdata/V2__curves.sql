-- Forward curves per allowance product and vintage curves per GoO bucket, built from quotes into immutable
-- snapshots. A snapshot becomes OFFICIAL when a second person signs it off; a later official snapshot for the same
-- date supersedes it, and the superseded one stays readable as known before.

CREATE TABLE curve (
    code                   TEXT           NOT NULL PRIMARY KEY CHECK (code ~ '^[A-Z0-9_]+$'),
    kind                   TEXT           NOT NULL CHECK (kind IN ('EUA_FORWARD', 'GOO_VINTAGE')),
    description            TEXT           NOT NULL,
    allowance_product_code TEXT,
    bucket_code            TEXT,
    currency               CHAR(3)        NOT NULL,
    -- Quote types to use, most preferred first, e.g. ["SETTLEMENT", "CLOSE", "MID"].
    quote_types            JSONB          NOT NULL,
    staleness_days         INT            NOT NULL DEFAULT 5 CHECK (staleness_days >= 0),
    proxy_curve_code       TEXT REFERENCES curve (code),
    proxy_spread           NUMERIC(18, 6) NOT NULL DEFAULT 0,
    active                 BOOLEAN        NOT NULL DEFAULT TRUE,
    created_at             TIMESTAMPTZ    NOT NULL DEFAULT now(),
    CONSTRAINT curve_shape_ck CHECK (
        (kind = 'EUA_FORWARD' AND allowance_product_code IS NOT NULL AND bucket_code IS NULL)
        OR (kind = 'GOO_VINTAGE' AND bucket_code IS NOT NULL AND allowance_product_code IS NULL)
    ),
    CONSTRAINT curve_no_self_proxy_ck CHECK (proxy_curve_code IS NULL OR proxy_curve_code <> code)
);
CREATE UNIQUE INDEX curve_bucket_uq ON curve (bucket_code) WHERE kind = 'GOO_VINTAGE' AND active;
CREATE UNIQUE INDEX curve_product_uq ON curve (allowance_product_code) WHERE kind = 'EUA_FORWARD' AND active;

CREATE TABLE curve_snapshot (
    id            UUID        NOT NULL PRIMARY KEY,
    curve_code    TEXT        NOT NULL REFERENCES curve (code),
    as_of_date    DATE        NOT NULL,
    version       INT         NOT NULL,
    status        TEXT        NOT NULL CHECK (status IN ('DRAFT', 'PENDING_SIGN_OFF', 'OFFICIAL', 'SUPERSEDED', 'REJECTED')),
    inputs_hash   TEXT        NOT NULL,
    -- Pillars that had no usable price when the snapshot was built.
    gaps          JSONB       NOT NULL DEFAULT '[]'::jsonb,
    note          TEXT,
    built_by      TEXT        NOT NULL,
    built_at      TIMESTAMPTZ NOT NULL,
    signed_off_by TEXT,
    signed_off_at TIMESTAMPTZ,
    superseded_at TIMESTAMPTZ,
    CONSTRAINT curve_snapshot_version_uq UNIQUE (curve_code, as_of_date, version),
    CONSTRAINT curve_snapshot_four_eyes_ck CHECK (signed_off_by IS NULL OR signed_off_by <> built_by),
    CONSTRAINT curve_snapshot_official_ck CHECK (status NOT IN ('OFFICIAL', 'SUPERSEDED') OR signed_off_by IS NOT NULL)
);
CREATE UNIQUE INDEX curve_snapshot_official_uq ON curve_snapshot (curve_code, as_of_date) WHERE status = 'OFFICIAL';

CREATE TABLE curve_point (
    snapshot_id     UUID           NOT NULL REFERENCES curve_snapshot (id),
    pillar          TEXT           NOT NULL,
    pillar_date     DATE,
    production_year INT,
    price           NUMERIC(18, 6) NOT NULL CHECK (price > 0),
    origin          TEXT           NOT NULL CHECK (origin IN ('QUOTE', 'STALE', 'PROXY', 'OVERRIDE')),
    quote_ids       JSONB          NOT NULL DEFAULT '[]'::jsonb,
    note            TEXT,
    PRIMARY KEY (snapshot_id, pillar),
    CONSTRAINT curve_point_key_ck CHECK (pillar_date IS NOT NULL OR production_year IS NOT NULL)
);

CREATE OR REPLACE FUNCTION curve_point_append_only() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'curve points are immutable; build a new snapshot';
END;
$$;

CREATE TRIGGER curve_point_append_only
    BEFORE UPDATE OR DELETE ON curve_point
    FOR EACH ROW EXECUTE FUNCTION curve_point_append_only();
