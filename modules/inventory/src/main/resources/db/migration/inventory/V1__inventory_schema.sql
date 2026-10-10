-- Certificate inventory. A lot is a contiguous certificate-number range from one EECS issuance held in one registry
-- account; all certificates in a lot share every attribute. Lots split on partial allocation and never overlap.

CREATE TABLE certificate_lot (
    id                        UUID           NOT NULL PRIMARY KEY,
    holder_entity_id          UUID           NOT NULL,
    registry_account_id       UUID           NOT NULL,
    registry_code             TEXT           NOT NULL,
    issuance_ref              TEXT           NOT NULL,
    cert_no_start             NUMERIC(40, 0) NOT NULL,
    cert_no_end               NUMERIC(40, 0) NOT NULL,
    cert_range                NUMRANGE       GENERATED ALWAYS AS (numrange(cert_no_start, cert_no_end, '[]')) STORED,
    quantity_mwh              BIGINT         GENERATED ALWAYS AS ((cert_no_end - cert_no_start + 1)::bigint) STORED,
    energy_carrier            TEXT           NOT NULL CHECK (energy_carrier IN ('ELECTRICITY', 'GAS', 'HYDROGEN', 'HEAT_COOLING')),
    certificate_scheme        TEXT           NOT NULL DEFAULT 'EECS_GO',
    issuing_body              TEXT           NOT NULL,
    issuing_country           CHAR(2)        NOT NULL,
    device_id                 UUID,
    device_gsrn               TEXT           NOT NULL,
    device_name               TEXT,
    device_country            CHAR(2)        NOT NULL,
    device_region             TEXT,
    device_commissioning_date DATE,
    device_capacity_kw        NUMERIC(14, 3),
    energy_source_code        TEXT           NOT NULL,
    energy_source_path        public.ltree   NOT NULL,
    technology_code           TEXT,
    technology_path           public.ltree,
    production_period         DATERANGE      NOT NULL CHECK (NOT isempty(production_period) AND lower_inc(production_period) AND NOT upper_inc(production_period)),
    issue_date                DATE           NOT NULL,
    expiry_date               DATE           NOT NULL,
    support_production        BOOLEAN,
    support_investment        BOOLEAN,
    support_scheme_code       TEXT,
    labels                    TEXT[]         NOT NULL DEFAULT '{}',
    status                    TEXT           NOT NULL CHECK (status IN ('HELD', 'RESERVED', 'IN_TRANSFER', 'TRANSFERRED_OUT', 'CANCELLED', 'EXPIRED', 'WITHDRAWN', 'SPLIT')),
    root_lot_id               UUID           NOT NULL,
    parent_lot_id             UUID,
    acquisition_source        TEXT           NOT NULL CHECK (acquisition_source IN ('ISSUANCE', 'TRANSFER_IN', 'OPENING_BALANCE')),
    acquisition_ref           TEXT,
    acquisition_date          DATE           NOT NULL,
    cost_basis_per_mwh        NUMERIC(18, 6),
    cost_currency             CHAR(3),
    reservation_ref           UUID,
    instruction_ref           UUID,
    attributes                JSONB          NOT NULL DEFAULT '{}'::jsonb,
    cancellation              JSONB,
    version                   INT            NOT NULL DEFAULT 1,
    created_seq               BIGINT,
    updated_seq               BIGINT,
    -- Valid time of the latest change and who made it; consumed by the version trigger below.
    effective_at              TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_by                TEXT           NOT NULL DEFAULT 'system',
    created_at                TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at                TIMESTAMPTZ    NOT NULL DEFAULT now(),
    CONSTRAINT certificate_lot_range_ck CHECK (cert_no_start <= cert_no_end),
    CONSTRAINT certificate_lot_expiry_ck CHECK (expiry_date >= upper(production_period)),
    CONSTRAINT certificate_lot_status_refs_ck CHECK (
        (status = 'RESERVED') = (reservation_ref IS NOT NULL) OR status = 'IN_TRANSFER'
    ),
    -- The invariant: no two live lots from the same issuance may overlap in certificate numbers.
    CONSTRAINT certificate_lot_no_overlap EXCLUDE USING gist (issuance_ref WITH =, cert_range WITH &&)
        WHERE (status IN ('HELD', 'RESERVED', 'IN_TRANSFER'))
);

CREATE INDEX certificate_lot_live_idx ON certificate_lot (holder_entity_id, registry_account_id, status)
    WHERE status IN ('HELD', 'RESERVED', 'IN_TRANSFER');
CREATE INDEX certificate_lot_expiry_idx ON certificate_lot (expiry_date) WHERE status IN ('HELD', 'RESERVED');
CREATE INDEX certificate_lot_energy_source_idx ON certificate_lot USING gist (energy_source_path);
CREATE INDEX certificate_lot_technology_idx ON certificate_lot USING gist (technology_path);
CREATE INDEX certificate_lot_production_idx ON certificate_lot USING gist (production_period);
CREATE INDEX certificate_lot_labels_idx ON certificate_lot USING gin (labels);
CREATE INDEX certificate_lot_attributes_idx ON certificate_lot USING gin (attributes jsonb_path_ops);
CREATE INDEX certificate_lot_device_idx ON certificate_lot (device_gsrn);
CREATE INDEX certificate_lot_country_idx ON certificate_lot (device_country);
CREATE INDEX certificate_lot_root_idx ON certificate_lot (root_lot_id);
CREATE INDEX certificate_lot_reservation_idx ON certificate_lot (reservation_ref) WHERE reservation_ref IS NOT NULL;

-- Append-only journal with a per-lot hash chain. Transaction time is `recorded_at`; valid time is `effective_at`.
CREATE TABLE lot_event (
    seq            BIGINT      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    lot_id         UUID        NOT NULL,
    event_type     TEXT        NOT NULL,
    recorded_at    TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    effective_at   TIMESTAMPTZ NOT NULL,
    actor          TEXT        NOT NULL,
    correlation_id TEXT,
    payload        JSONB       NOT NULL,
    prev_hash      TEXT,
    hash           TEXT        NOT NULL
);
CREATE INDEX lot_event_lot_idx ON lot_event (lot_id, seq);
CREATE INDEX lot_event_recorded_idx ON lot_event (recorded_at);

CREATE OR REPLACE FUNCTION lot_event_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'lot_event is append-only (% attempted on seq %)', TG_OP, OLD.seq;
END $$;
CREATE TRIGGER lot_event_no_update BEFORE UPDATE OR DELETE ON lot_event FOR EACH ROW EXECUTE FUNCTION lot_event_immutable();

-- Bitemporal projection history of lots: every version with transaction-time (sys_period) and valid-time
-- (valid_period). A change effective at T closes the current rows in transaction time and re-records the
-- superseded version for the valid time before T, so "what was true on D as known at K" has one answer for any D, K.
CREATE TABLE certificate_lot_version (
    id           BIGINT    GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    lot_id       UUID      NOT NULL,
    version      INT       NOT NULL,
    sys_period   TSTZRANGE NOT NULL,
    valid_period TSTZRANGE NOT NULL,
    event_seq    BIGINT,
    actor        TEXT,
    data         JSONB     NOT NULL
);
CREATE INDEX certificate_lot_version_idx ON certificate_lot_version USING gist (lot_id, sys_period, valid_period);

CREATE OR REPLACE FUNCTION certificate_lot_version_trigger() RETURNS trigger LANGUAGE plpgsql SET search_path FROM CURRENT AS $$
DECLARE
    v_now TIMESTAMPTZ := clock_timestamp();
    v_effective TIMESTAMPTZ := NEW.effective_at;
    r RECORD;
BEGIN
    IF TG_OP = 'UPDATE' THEN
        FOR r IN SELECT * FROM certificate_lot_version WHERE lot_id = OLD.id AND upper_inf(sys_period) LOOP
            UPDATE certificate_lot_version SET sys_period = tstzrange(lower(sys_period), v_now, '[)') WHERE id = r.id;
            -- The superseded version stays our knowledge for valid times before the change became effective.
            IF lower(r.valid_period) < v_effective AND (upper_inf(r.valid_period) OR upper(r.valid_period) > v_effective) THEN
                INSERT INTO certificate_lot_version (lot_id, version, sys_period, valid_period, event_seq, actor, data)
                VALUES (r.lot_id, r.version, tstzrange(v_now, NULL, '[)'), tstzrange(lower(r.valid_period), v_effective, '[)'), r.event_seq, r.actor, r.data);
            END IF;
        END LOOP;
    END IF;
    INSERT INTO certificate_lot_version (lot_id, version, sys_period, valid_period, event_seq, actor, data)
    VALUES (NEW.id, NEW.version, tstzrange(v_now, NULL, '[)'), tstzrange(v_effective, NULL, '[)'), NEW.updated_seq, NEW.updated_by, to_jsonb(NEW));
    RETURN NEW;
END $$;
CREATE TRIGGER certificate_lot_version AFTER INSERT OR UPDATE ON certificate_lot FOR EACH ROW EXECUTE FUNCTION certificate_lot_version_trigger();

-- Emission allowances are fungible: a balance per account and product with a signed movement journal.
CREATE TABLE allowance_holding (
    id                  UUID        NOT NULL PRIMARY KEY,
    holder_entity_id    UUID        NOT NULL,
    registry_account_id UUID        NOT NULL,
    product_code        TEXT        NOT NULL,
    quantity            BIGINT      NOT NULL DEFAULT 0 CHECK (quantity >= 0),
    reserved_quantity   BIGINT      NOT NULL DEFAULT 0 CHECK (reserved_quantity >= 0 AND reserved_quantity <= quantity),
    version             INT         NOT NULL DEFAULT 1,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT allowance_holding_uq UNIQUE (registry_account_id, product_code)
);

CREATE TABLE allowance_movement (
    seq             BIGINT      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    holding_id      UUID        NOT NULL REFERENCES allowance_holding (id),
    movement_type   TEXT        NOT NULL CHECK (movement_type IN (
                        'FREE_ALLOCATION', 'PURCHASE', 'SALE', 'TRANSFER_IN', 'TRANSFER_OUT', 'SURRENDER', 'DELETION', 'OPENING_BALANCE', 'CORRECTION')),
    quantity        BIGINT      NOT NULL CHECK (quantity <> 0),
    effective_date  DATE        NOT NULL,
    reference       TEXT,
    cost_per_unit   NUMERIC(18, 6),
    cost_currency   CHAR(3),
    compliance_year INT,
    actor           TEXT        NOT NULL,
    correlation_id  TEXT,
    recorded_at     TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    payload         JSONB       NOT NULL DEFAULT '{}'::jsonb
);
CREATE INDEX allowance_movement_holding_idx ON allowance_movement (holding_id, seq);

-- Registry statements and reconciliation breaks: the control that proves internal inventory equals the registry.
CREATE TABLE registry_statement (
    id                  UUID        NOT NULL PRIMARY KEY,
    registry_code       TEXT        NOT NULL,
    registry_account_id UUID        NOT NULL,
    statement_date      DATE        NOT NULL,
    source              TEXT        NOT NULL,
    imported_by         TEXT        NOT NULL,
    imported_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    line_count          INT         NOT NULL,
    status              TEXT        NOT NULL CHECK (status IN ('IMPORTED', 'RECONCILED', 'BREAKS')),
    reconciled_at       TIMESTAMPTZ
);

CREATE TABLE registry_statement_line (
    id               UUID           NOT NULL PRIMARY KEY,
    statement_id     UUID           NOT NULL REFERENCES registry_statement (id) ON DELETE CASCADE,
    line_no          INT            NOT NULL,
    issuance_ref     TEXT           NOT NULL,
    cert_no_start    NUMERIC(40, 0) NOT NULL,
    cert_no_end      NUMERIC(40, 0) NOT NULL,
    device_gsrn      TEXT,
    production_start DATE,
    production_end   DATE,
    raw              JSONB          NOT NULL DEFAULT '{}'::jsonb,
    CONSTRAINT registry_statement_line_uq UNIQUE (statement_id, line_no)
);

CREATE TABLE reconciliation_break (
    id            UUID           NOT NULL PRIMARY KEY,
    statement_id  UUID           NOT NULL REFERENCES registry_statement (id) ON DELETE CASCADE,
    break_type    TEXT           NOT NULL CHECK (break_type IN ('MISSING_INTERNALLY', 'MISSING_IN_REGISTRY', 'ATTRIBUTE_MISMATCH')),
    issuance_ref  TEXT           NOT NULL,
    cert_no_start NUMERIC(40, 0) NOT NULL,
    cert_no_end   NUMERIC(40, 0) NOT NULL,
    lot_id        UUID,
    details       JSONB          NOT NULL DEFAULT '{}'::jsonb,
    status        TEXT           NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN', 'RESOLVED', 'ACCEPTED')),
    resolution    TEXT,
    resolved_by   TEXT,
    resolved_at   TIMESTAMPTZ,
    detected_at   TIMESTAMPTZ    NOT NULL DEFAULT now()
);
CREATE INDEX reconciliation_break_open_idx ON reconciliation_break (statement_id) WHERE status = 'OPEN';
