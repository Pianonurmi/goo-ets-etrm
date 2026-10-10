-- Shared platform schema: PostgreSQL extensions used across modules and the Spring Modulith event publication
-- registry (transactional outbox). Extensions live in `public` so every module schema can reference the types.
CREATE EXTENSION IF NOT EXISTS ltree WITH SCHEMA public;
CREATE EXTENSION IF NOT EXISTS btree_gist WITH SCHEMA public;

CREATE TABLE IF NOT EXISTS event_publication (
    id                 UUID        NOT NULL PRIMARY KEY,
    listener_id        TEXT        NOT NULL,
    event_type         TEXT        NOT NULL,
    serialized_event   TEXT        NOT NULL,
    publication_date   TIMESTAMPTZ NOT NULL,
    completion_date    TIMESTAMPTZ,
    status             TEXT,
    completion_attempts INT,
    last_resubmission_date TIMESTAMPTZ
);
CREATE INDEX IF NOT EXISTS event_publication_serialized_event_hash_idx ON event_publication USING hash (serialized_event);
CREATE INDEX IF NOT EXISTS event_publication_by_completion_date_idx ON event_publication (completion_date);
CREATE INDEX IF NOT EXISTS event_publication_by_status_idx ON event_publication (status);

-- Distributed scheduler lock table (ShedLock) so scheduled jobs run once across application instances.
CREATE TABLE IF NOT EXISTS shedlock (
    name       VARCHAR(64)  NOT NULL PRIMARY KEY,
    lock_until TIMESTAMPTZ  NOT NULL,
    locked_at  TIMESTAMPTZ  NOT NULL,
    locked_by  VARCHAR(255) NOT NULL
);
