-- PDFs requested inside a business transaction are rendered after it commits, so no pool connection waits on page
-- layout. The job keeps the filled-in template until the immutable document row (same id) is stored, then goes away.
CREATE TABLE document_render_job (
    id              UUID        PRIMARY KEY,
    kind            TEXT        NOT NULL,
    owner_type      TEXT        NOT NULL,
    owner_ref       UUID        NOT NULL,
    legal_entity_id UUID,
    title           TEXT        NOT NULL,
    file_name       TEXT        NOT NULL,
    xhtml           TEXT        NOT NULL,
    requested_by    TEXT        NOT NULL,
    requested_at    TIMESTAMPTZ NOT NULL,
    attempts        INT         NOT NULL DEFAULT 0,
    last_error      TEXT,
    failed          BOOLEAN     NOT NULL DEFAULT FALSE
);
CREATE INDEX document_render_job_owner_idx ON document_render_job (owner_type, owner_ref);
CREATE INDEX document_render_job_due_idx ON document_render_job (requested_at) WHERE NOT failed;
