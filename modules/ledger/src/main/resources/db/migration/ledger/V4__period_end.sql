-- Period end (ADR 0021): MtM treatment per book type, net realisable value policy, policy changes under four eyes,
-- and a record of each period-end run with what it measured and posted.

-- How unrealised MtM of a book type is accounted for. A row without an entity is the default for all entities.
CREATE TABLE mtm_policy (
    id                 UUID        NOT NULL PRIMARY KEY,
    legal_entity_id    UUID,
    book_type          TEXT        NOT NULL CHECK (book_type IN ('TRADING', 'OWN_USE', 'HEDGING', 'COMPLIANCE')),
    treatment          TEXT        NOT NULL CHECK (treatment IN ('POST_PL', 'POST_OCI', 'DISCLOSE_ONLY')),
    -- Seeded policies are proposals until accounting signs them off; runs report unsigned policies.
    signed_off_by      TEXT,
    signed_off_at      TIMESTAMPTZ,
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX mtm_policy_uq ON mtm_policy (COALESCE(legal_entity_id, '00000000-0000-0000-0000-000000000000'::uuid), book_type);

INSERT INTO mtm_policy (id, legal_entity_id, book_type, treatment) VALUES
    ('0192a000-0000-7000-8000-000000000001', NULL, 'TRADING', 'POST_PL'),
    ('0192a000-0000-7000-8000-000000000002', NULL, 'HEDGING', 'POST_PL'),
    ('0192a000-0000-7000-8000-000000000003', NULL, 'OWN_USE', 'DISCLOSE_ONLY'),
    ('0192a000-0000-7000-8000-000000000004', NULL, 'COMPLIANCE', 'DISCLOSE_ONLY');

-- Lower of cost and net realisable value for inventory.
CREATE TABLE nrv_policy (
    id                         UUID           NOT NULL PRIMARY KEY,
    legal_entity_id            UUID,
    goo_cost_to_sell_per_mwh   NUMERIC(18, 6) NOT NULL CHECK (goo_cost_to_sell_per_mwh >= 0),
    allowance_cost_to_sell     NUMERIC(18, 6) NOT NULL CHECK (allowance_cost_to_sell >= 0),
    -- Unreserved certificates expiring within this many days of the period end are valued at nil.
    expiry_days                INT            NOT NULL CHECK (expiry_days >= 0),
    currency                   CHAR(3)        NOT NULL,
    signed_off_by              TEXT,
    signed_off_at              TIMESTAMPTZ,
    updated_at                 TIMESTAMPTZ    NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX nrv_policy_uq ON nrv_policy (COALESCE(legal_entity_id, '00000000-0000-0000-0000-000000000000'::uuid));

INSERT INTO nrv_policy (id, legal_entity_id, goo_cost_to_sell_per_mwh, allowance_cost_to_sell, expiry_days, currency) VALUES
    ('0192a000-0000-7000-8000-000000000010', NULL, 0.02, 0, 60, 'EUR');

-- A requested policy change, applied when a second person approves it (ACCOUNTING_POLICY_CHANGE).
CREATE TABLE accounting_policy_change (
    id              UUID        NOT NULL PRIMARY KEY,
    legal_entity_id UUID,
    kind            TEXT        NOT NULL CHECK (kind IN ('MTM', 'NRV')),
    payload         JSONB       NOT NULL,
    reason          TEXT        NOT NULL,
    status          TEXT        NOT NULL CHECK (status IN ('PENDING', 'APPLIED', 'REJECTED')),
    requested_by    TEXT        NOT NULL,
    requested_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    decided_by      TEXT,
    decided_at      TIMESTAMPTZ,
    CONSTRAINT policy_change_four_eyes_ck CHECK (decided_by IS NULL OR decided_by <> requested_by)
);

-- Each run of the period end for an entity and period; a re-run posts only the difference to what is booked.
CREATE TABLE period_end_run (
    id                UUID        NOT NULL PRIMARY KEY,
    legal_entity_id   UUID        NOT NULL,
    period            CHAR(7)     NOT NULL,
    seq               INT         NOT NULL,
    valuation_run_id  UUID,
    components        JSONB       NOT NULL,
    entry_ids         JSONB       NOT NULL,
    warnings          JSONB       NOT NULL,
    run_by            TEXT        NOT NULL,
    run_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT period_end_run_uq UNIQUE (legal_entity_id, period, seq)
);

CREATE OR REPLACE FUNCTION period_end_run_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'period end runs are append-only';
END $$;
CREATE TRIGGER period_end_run_append_only BEFORE UPDATE OR DELETE ON period_end_run FOR EACH ROW EXECUTE FUNCTION period_end_run_immutable();

CREATE INDEX journal_line_dimensions_idx ON journal_line USING gin (dimensions);
