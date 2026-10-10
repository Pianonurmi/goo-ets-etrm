-- Immutable store of business documents. Content and metadata never change after insert; corrections are new
-- documents (e.g. a credit note), so the hash in sha256 always describes what was issued.

CREATE TABLE document (
    id              UUID        PRIMARY KEY,
    kind            TEXT        NOT NULL,
    owner_type      TEXT        NOT NULL,
    owner_ref       UUID        NOT NULL,
    legal_entity_id UUID,
    title           TEXT        NOT NULL,
    file_name       TEXT        NOT NULL,
    mime_type       TEXT        NOT NULL,
    size_bytes      BIGINT      NOT NULL CHECK (size_bytes > 0),
    sha256          CHAR(64)    NOT NULL,
    content         BYTEA       NOT NULL,
    created_by      TEXT        NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL
);
CREATE INDEX document_owner_idx ON document (owner_type, owner_ref, created_at);
CREATE INDEX document_entity_idx ON document (legal_entity_id, kind, created_at);

CREATE OR REPLACE FUNCTION document_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'document is immutable';
END $$;
CREATE TRIGGER document_immutable BEFORE UPDATE OR DELETE ON document FOR EACH ROW EXECUTE FUNCTION document_immutable();
