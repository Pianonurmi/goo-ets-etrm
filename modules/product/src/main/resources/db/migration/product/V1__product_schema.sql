-- Product definitions: the commodity / instrument catalogue, stored GoO criteria documents and the product bucket
-- catalogue used for positions, valuation curves and allocation scoring.

CREATE TABLE criteria (
    id                 UUID           NOT NULL PRIMARY KEY,
    schema_version     INT            NOT NULL,
    body               JSONB          NOT NULL,
    body_hash          TEXT           NOT NULL UNIQUE,
    static_specificity NUMERIC(10, 4) NOT NULL,
    summary            TEXT           NOT NULL,
    created_at         TIMESTAMPTZ    NOT NULL DEFAULT now(),
    created_by         TEXT
);
CREATE INDEX criteria_body_gin_idx ON criteria USING gin (body jsonb_path_ops);

CREATE TABLE product_bucket (
    id             UUID        NOT NULL PRIMARY KEY,
    code           TEXT        NOT NULL UNIQUE CHECK (code ~ '^[A-Z0-9_]{2,40}$'),
    name           TEXT        NOT NULL,
    energy_carrier TEXT        NOT NULL CHECK (energy_carrier IN ('ELECTRICITY', 'GAS', 'HYDROGEN', 'HEAT_COOLING')),
    criteria_id    UUID        NOT NULL REFERENCES criteria (id),
    mark_curve_ref TEXT,
    sort_order     INT         NOT NULL DEFAULT 100,
    active         BOOLEAN     NOT NULL DEFAULT TRUE,
    version        INT         NOT NULL DEFAULT 1,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE allowance_product (
    code         TEXT    NOT NULL PRIMARY KEY,
    name         TEXT    NOT NULL,
    jurisdiction TEXT    NOT NULL,
    phase        TEXT,
    unit         TEXT    NOT NULL DEFAULT 'TCO2E',
    registry_code TEXT   NOT NULL,
    active       BOOLEAN NOT NULL DEFAULT TRUE
);

INSERT INTO allowance_product (code, name, jurisdiction, phase, registry_code, active) VALUES
    ('EUA', 'EU Allowance (general)', 'EU_ETS', 'PHASE_4', 'UNION_REGISTRY', TRUE),
    ('EUAA', 'EU Aviation Allowance', 'EU_ETS', 'PHASE_4', 'UNION_REGISTRY', TRUE),
    ('ETS2', 'EU ETS2 Allowance (buildings, road transport, small industry)', 'EU_ETS2', 'PHASE_1', 'UNION_REGISTRY', FALSE),
    ('UKA', 'UK Allowance', 'UK_ETS', NULL, 'UK_REGISTRY', FALSE);

-- Weights for the static specificity score of criteria predicates. Kept as data so they can be tuned without release.
CREATE TABLE specificity_weight (
    predicate TEXT           NOT NULL PRIMARY KEY,
    weight    NUMERIC(10, 4) NOT NULL
);
INSERT INTO specificity_weight (predicate, weight) VALUES
    ('DEVICE_EQUALS', 10), ('TAXONOMY_PER_LEVEL', 2), ('COUNTRY', 4), ('LABEL', 5), ('VINTAGE_WITHIN_12_MONTHS', 3),
    ('VINTAGE_OTHER', 1.5), ('DEVICE_AGE', 3), ('SUPPORT', 2), ('CARRIER', 0.5), ('GENERIC_EQUALS', 2), ('RANGE', 1.5), ('NOT_FACTOR', 0.5);
