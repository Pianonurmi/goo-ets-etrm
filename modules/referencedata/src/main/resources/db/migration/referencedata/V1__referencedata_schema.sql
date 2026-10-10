-- Reference (master) data: legal entities, books, counterparties, registries and registry accounts, production
-- devices, calendars. Every master table is versioned; a generic history table captures every version with its
-- transaction-time period so any report can be reproduced "as known at" a past instant.

CREATE TABLE reference_history (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    table_name  TEXT        NOT NULL,
    entity_id   UUID        NOT NULL,
    version     INT         NOT NULL,
    sys_period  TSTZRANGE   NOT NULL,
    actor       TEXT,
    data        JSONB       NOT NULL
);
CREATE INDEX reference_history_entity_idx ON reference_history USING gist (entity_id, sys_period);
CREATE INDEX reference_history_table_idx ON reference_history (table_name, entity_id, version);

-- Generic trigger: closes the previous history row and opens a new one for the current version.
-- `SET search_path FROM CURRENT` pins the module schema so the function works regardless of the caller's search path.
CREATE OR REPLACE FUNCTION reference_history_trigger() RETURNS trigger LANGUAGE plpgsql SET search_path FROM CURRENT AS $$
DECLARE
    v_now TIMESTAMPTZ := clock_timestamp();
BEGIN
    IF TG_OP = 'UPDATE' THEN
        UPDATE reference_history
           SET sys_period = tstzrange(lower(sys_period), v_now, '[)')
         WHERE table_name = TG_TABLE_NAME AND entity_id = OLD.id AND upper_inf(sys_period);
    END IF;
    INSERT INTO reference_history (table_name, entity_id, version, sys_period, actor, data)
    VALUES (TG_TABLE_NAME, NEW.id, NEW.version, tstzrange(v_now, NULL, '[)'), current_setting('etrm.actor', true), to_jsonb(NEW));
    RETURN NEW;
END $$;

CREATE TABLE legal_entity (
    id                      UUID        NOT NULL PRIMARY KEY,
    code                    TEXT        NOT NULL UNIQUE CHECK (code ~ '^[A-Z0-9_-]{2,20}$'),
    name                    TEXT        NOT NULL,
    country                 CHAR(2)     NOT NULL,
    lei                     CHAR(20),
    vat_number              TEXT,
    base_currency           CHAR(3)     NOT NULL,
    time_zone               TEXT        NOT NULL,
    goo_costing_method      TEXT        NOT NULL CHECK (goo_costing_method IN ('SPECIFIC_IDENTIFICATION', 'FIFO', 'WEIGHTED_AVERAGE')),
    allowance_costing_method TEXT       NOT NULL CHECK (allowance_costing_method IN ('FIFO', 'WEIGHTED_AVERAGE')),
    allowance_classification TEXT       NOT NULL CHECK (allowance_classification IN ('INTANGIBLE_ASSET', 'INVENTORY')),
    free_allocation_policy  TEXT        NOT NULL CHECK (free_allocation_policy IN ('NOMINAL', 'FAIR_VALUE')),
    valid_from              DATE        NOT NULL,
    valid_to                DATE,
    active                  BOOLEAN     NOT NULL DEFAULT TRUE,
    version                 INT         NOT NULL DEFAULT 1,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TRIGGER legal_entity_history AFTER INSERT OR UPDATE ON legal_entity FOR EACH ROW EXECUTE FUNCTION reference_history_trigger();

CREATE TABLE book (
    id               UUID        NOT NULL PRIMARY KEY,
    legal_entity_id  UUID        NOT NULL REFERENCES legal_entity (id),
    code             TEXT        NOT NULL CHECK (code ~ '^[A-Z0-9_-]{2,30}$'),
    name             TEXT        NOT NULL,
    book_type        TEXT        NOT NULL CHECK (book_type IN ('TRADING', 'OWN_USE', 'HEDGING', 'COMPLIANCE')),
    parent_book_id   UUID        REFERENCES book (id),
    strategy         TEXT,
    active           BOOLEAN     NOT NULL DEFAULT TRUE,
    version          INT         NOT NULL DEFAULT 1,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT book_entity_code_uq UNIQUE (legal_entity_id, code)
);
CREATE TRIGGER book_history AFTER INSERT OR UPDATE ON book FOR EACH ROW EXECUTE FUNCTION reference_history_trigger();

CREATE TABLE counterparty (
    id                   UUID        NOT NULL PRIMARY KEY,
    code                 TEXT        NOT NULL UNIQUE CHECK (code ~ '^[A-Z0-9_-]{2,30}$'),
    name                 TEXT        NOT NULL,
    country              CHAR(2)     NOT NULL,
    lei                  CHAR(20),
    vat_number           TEXT,
    counterparty_type    TEXT        NOT NULL CHECK (counterparty_type IN ('EXTERNAL', 'INTERCOMPANY', 'BROKER', 'EXCHANGE', 'CLEARING_HOUSE')),
    linked_legal_entity_id UUID      REFERENCES legal_entity (id),
    master_agreements    JSONB       NOT NULL DEFAULT '[]'::jsonb,
    payment_terms_days   INT         NOT NULL DEFAULT 14 CHECK (payment_terms_days >= 0),
    settlement_currency  CHAR(3)     NOT NULL DEFAULT 'EUR',
    credit_limit_amount  NUMERIC(18, 2),
    active               BOOLEAN     NOT NULL DEFAULT TRUE,
    version              INT         NOT NULL DEFAULT 1,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT counterparty_intercompany_ck CHECK (counterparty_type <> 'INTERCOMPANY' OR linked_legal_entity_id IS NOT NULL)
);
CREATE TRIGGER counterparty_history AFTER INSERT OR UPDATE ON counterparty FOR EACH ROW EXECUTE FUNCTION reference_history_trigger();

CREATE TABLE registry (
    id            UUID        NOT NULL PRIMARY KEY,
    code          TEXT        NOT NULL UNIQUE CHECK (code ~ '^[A-Z0-9_]{2,30}$'),
    name          TEXT        NOT NULL,
    registry_type TEXT        NOT NULL CHECK (registry_type IN ('GOO', 'EMISSION_ALLOWANCE')),
    country       CHAR(2),
    hub_member    BOOLEAN     NOT NULL DEFAULT FALSE,
    operator      TEXT,
    integration   TEXT        NOT NULL DEFAULT 'MANUAL' CHECK (integration IN ('MANUAL', 'FILE', 'API', 'SIMULATOR')),
    active        BOOLEAN     NOT NULL DEFAULT TRUE,
    version       INT         NOT NULL DEFAULT 1,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TRIGGER registry_history AFTER INSERT OR UPDATE ON registry FOR EACH ROW EXECUTE FUNCTION reference_history_trigger();

CREATE TABLE registry_account (
    id             UUID        NOT NULL PRIMARY KEY,
    registry_id    UUID        NOT NULL REFERENCES registry (id),
    account_number TEXT        NOT NULL,
    name           TEXT        NOT NULL,
    account_type   TEXT        NOT NULL CHECK (account_type IN (
                       'HOLDING', 'PRODUCTION', 'CANCELLATION_ONLY',
                       'OPERATOR_HOLDING', 'AIRCRAFT_OPERATOR_HOLDING', 'TRADING', 'PERSON_HOLDING')),
    holder_type    TEXT        NOT NULL CHECK (holder_type IN ('LEGAL_ENTITY', 'COUNTERPARTY')),
    holder_id      UUID        NOT NULL,
    can_import     BOOLEAN     NOT NULL DEFAULT TRUE,
    can_export     BOOLEAN     NOT NULL DEFAULT TRUE,
    trusted        BOOLEAN     NOT NULL DEFAULT FALSE,
    active         BOOLEAN     NOT NULL DEFAULT TRUE,
    version        INT         NOT NULL DEFAULT 1,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT registry_account_number_uq UNIQUE (registry_id, account_number)
);
CREATE INDEX registry_account_holder_idx ON registry_account (holder_type, holder_id);
CREATE TRIGGER registry_account_history AFTER INSERT OR UPDATE ON registry_account FOR EACH ROW EXECUTE FUNCTION reference_history_trigger();

CREATE TABLE production_device (
    id                   UUID        NOT NULL PRIMARY KEY,
    gsrn                 CHAR(18)    NOT NULL UNIQUE CHECK (gsrn ~ '^[0-9]{18}$'),
    name                 TEXT        NOT NULL,
    country              CHAR(2)     NOT NULL,
    region_code          TEXT,
    latitude             NUMERIC(9, 6),
    longitude            NUMERIC(9, 6),
    capacity_kw          NUMERIC(14, 3) NOT NULL CHECK (capacity_kw > 0),
    commissioning_date   DATE        NOT NULL,
    decommissioning_date DATE,
    energy_source_code   TEXT        NOT NULL,
    technology_code      TEXT        NOT NULL,
    support_scheme_codes TEXT[]      NOT NULL DEFAULT '{}',
    label_codes          TEXT[]      NOT NULL DEFAULT '{}',
    registry_id          UUID        REFERENCES registry (id),
    owner_type           TEXT        NOT NULL CHECK (owner_type IN ('LEGAL_ENTITY', 'COUNTERPARTY', 'THIRD_PARTY')),
    owner_id             UUID,
    own_device           BOOLEAN     NOT NULL DEFAULT FALSE,
    external_codes       JSONB       NOT NULL DEFAULT '{}'::jsonb,
    active               BOOLEAN     NOT NULL DEFAULT TRUE,
    version              INT         NOT NULL DEFAULT 1,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT production_device_dates_ck CHECK (decommissioning_date IS NULL OR commissioning_date < decommissioning_date)
);
CREATE INDEX production_device_country_idx ON production_device (country);
CREATE INDEX production_device_energy_source_idx ON production_device (energy_source_code);
CREATE INDEX production_device_owner_idx ON production_device (owner_type, owner_id);
CREATE TRIGGER production_device_history AFTER INSERT OR UPDATE ON production_device FOR EACH ROW EXECUTE FUNCTION reference_history_trigger();

CREATE TABLE calendar (
    code       TEXT        NOT NULL PRIMARY KEY CHECK (code ~ '^[A-Z0-9_]{2,30}$'),
    name       TEXT        NOT NULL,
    weekend_days INT[]     NOT NULL DEFAULT '{6,7}'
);
CREATE TABLE calendar_holiday (
    calendar_code TEXT NOT NULL REFERENCES calendar (code) ON DELETE CASCADE,
    holiday       DATE NOT NULL,
    name          TEXT NOT NULL,
    PRIMARY KEY (calendar_code, holiday)
);

INSERT INTO calendar (code, name) VALUES ('TARGET2', 'TARGET2 settlement calendar'), ('FI', 'Finland'), ('SE', 'Sweden'), ('NO', 'Norway'), ('DK', 'Denmark');
INSERT INTO calendar_holiday (calendar_code, holiday, name) VALUES
    ('TARGET2', DATE '2026-01-01', 'New Year''s Day'), ('TARGET2', DATE '2026-04-03', 'Good Friday'), ('TARGET2', DATE '2026-04-06', 'Easter Monday'),
    ('TARGET2', DATE '2026-05-01', 'Labour Day'), ('TARGET2', DATE '2026-12-25', 'Christmas Day'), ('TARGET2', DATE '2026-12-26', 'Christmas Holiday'),
    ('TARGET2', DATE '2027-01-01', 'New Year''s Day'), ('TARGET2', DATE '2027-03-26', 'Good Friday'), ('TARGET2', DATE '2027-03-29', 'Easter Monday'),
    ('TARGET2', DATE '2027-05-01', 'Labour Day'), ('TARGET2', DATE '2027-12-25', 'Christmas Day'), ('TARGET2', DATE '2027-12-26', 'Christmas Holiday');

-- Registries in initial scope. Integration mode is upgraded per registry as adapters are delivered.
INSERT INTO registry (id, code, name, registry_type, country, hub_member, operator, integration) VALUES
    (gen_random_uuid(), 'FINGRID', 'Finland GoO registry (Finextra)', 'GOO', 'FI', TRUE, 'Finextra Oy', 'FILE'),
    (gen_random_uuid(), 'CESAR', 'Sweden GoO registry (Cesar)', 'GOO', 'SE', TRUE, 'Energimyndigheten', 'FILE'),
    (gen_random_uuid(), 'NECS', 'Norway GoO registry (NECS)', 'GOO', 'NO', TRUE, 'Statnett', 'FILE'),
    (gen_random_uuid(), 'ENERGINET', 'Denmark GoO registry', 'GOO', 'DK', TRUE, 'Energinet', 'FILE'),
    (gen_random_uuid(), 'ELERING', 'Estonia GoO registry', 'GOO', 'EE', TRUE, 'Elering', 'FILE'),
    (gen_random_uuid(), 'CERTIQ', 'Netherlands GoO registry', 'GOO', 'NL', TRUE, 'CertiQ', 'FILE'),
    (gen_random_uuid(), 'HKNR', 'Germany GoO registry (HKNR)', 'GOO', 'DE', TRUE, 'Umweltbundesamt', 'FILE'),
    (gen_random_uuid(), 'UNION_REGISTRY', 'EU ETS Union Registry', 'EMISSION_ALLOWANCE', NULL, FALSE, 'European Commission', 'MANUAL'),
    (gen_random_uuid(), 'SIMULATOR', 'In-memory registry simulator (test and demo)', 'GOO', NULL, TRUE, 'ETRM', 'SIMULATOR');
