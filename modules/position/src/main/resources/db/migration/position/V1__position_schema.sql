-- Position read models: compliance inputs for the EU ETS ladder and persisted daily snapshots for as-of reporting.

CREATE TABLE compliance_input (
    legal_entity_id          UUID        NOT NULL,
    compliance_year          INT         NOT NULL,
    verified_emissions_tco2e BIGINT,
    forecast_emissions_tco2e BIGINT,
    free_allocation_expected BIGINT,
    updated_by               TEXT        NOT NULL,
    updated_at               TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (legal_entity_id, compliance_year)
);

CREATE TABLE position_snapshot (
    id              UUID        NOT NULL PRIMARY KEY,
    legal_entity_id UUID        NOT NULL,
    kind            TEXT        NOT NULL CHECK (kind IN ('GOO', 'ETS')),
    as_of_date      DATE        NOT NULL,
    taken_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    data            JSONB       NOT NULL,
    CONSTRAINT position_snapshot_uq UNIQUE (legal_entity_id, kind, as_of_date)
);
