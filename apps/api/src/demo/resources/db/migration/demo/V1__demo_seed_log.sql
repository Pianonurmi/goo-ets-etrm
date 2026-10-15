-- The demo seeder's run log (demo profile only, etrm.migrations.additional-modules). Not business data.
CREATE TABLE seed_run (
    id           UUID PRIMARY KEY,
    seed_date    DATE        NOT NULL,
    status       TEXT        NOT NULL CHECK (status IN ('RUNNING', 'COMPLETED', 'FAILED')),
    started_at   TIMESTAMPTZ NOT NULL,
    finished_at  TIMESTAMPTZ,
    step_count   INTEGER     NOT NULL,
    current_step TEXT,
    error        TEXT
);

CREATE TABLE seed_step (
    run_id      UUID        NOT NULL REFERENCES seed_run (id),
    ordinal     INTEGER     NOT NULL,
    name        TEXT        NOT NULL,
    status      TEXT        NOT NULL CHECK (status IN ('RUNNING', 'COMPLETED', 'FAILED')),
    started_at  TIMESTAMPTZ NOT NULL,
    finished_at TIMESTAMPTZ,
    duration_ms BIGINT,
    outputs     JSONB,
    error       TEXT,
    PRIMARY KEY (run_id, name)
);
