-- Deliveries a plan counts on from supply still to come (ADR 0038): a forward purchase or a device's expected
-- issuance. Never reserved or instructed; a change to the source marks proposed plans stale and these lines CHANGED.
CREATE TABLE allocation_virtual_line (
    id              UUID           NOT NULL PRIMARY KEY,
    plan_id         UUID           NOT NULL REFERENCES allocation_plan (id) ON DELETE CASCADE,
    line_no         INT            NOT NULL,
    obligation_id   UUID           NOT NULL REFERENCES obligation (id),
    kind            TEXT           NOT NULL CHECK (kind IN ('CONTRACTED', 'EXPECTED')),
    source_ref      TEXT           NOT NULL,
    description     TEXT           NOT NULL,
    quantity_mwh    BIGINT         NOT NULL CHECK (quantity_mwh > 0),
    available_from  DATE           NOT NULL,
    score           NUMERIC(14, 6) NOT NULL,
    score_breakdown JSONB          NOT NULL,
    status          TEXT           NOT NULL CHECK (status IN ('PLANNED', 'CHANGED')),
    CONSTRAINT allocation_virtual_line_uq UNIQUE (plan_id, line_no)
);
CREATE INDEX allocation_virtual_line_source_idx ON allocation_virtual_line (source_ref);
