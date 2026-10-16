-- Long operations started by a request and finished on their own thread (increment 16 M2): period end, valuation
-- runs, report exports. The instance running a run beats its heartbeat; a run whose heartbeat stops (the instance
-- went away) is failed by the sweep, so no client polls forever. Finished runs are kept for a week.
CREATE TABLE async_run (
    id              UUID        NOT NULL PRIMARY KEY,
    kind            TEXT        NOT NULL,
    legal_entity_id UUID,
    status          TEXT        NOT NULL CHECK (status IN ('RUNNING', 'SUCCEEDED', 'FAILED')),
    result_id       UUID,
    problem         JSONB,
    requested_by    TEXT        NOT NULL,
    requested_at    TIMESTAMPTZ NOT NULL,
    heartbeat_at    TIMESTAMPTZ NOT NULL,
    finished_at     TIMESTAMPTZ,
    CONSTRAINT async_run_finished CHECK ((status = 'RUNNING') = (finished_at IS NULL)),
    CONSTRAINT async_run_outcome CHECK (
        (status <> 'SUCCEEDED' OR result_id IS NOT NULL) AND (status <> 'FAILED' OR problem IS NOT NULL)
    )
);
CREATE INDEX async_run_running_idx ON async_run (heartbeat_at) WHERE status = 'RUNNING';
CREATE INDEX async_run_finished_idx ON async_run (finished_at) WHERE status <> 'RUNNING';
