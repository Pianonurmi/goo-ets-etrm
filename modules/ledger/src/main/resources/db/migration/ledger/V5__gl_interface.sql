-- GL interface (ADR 0024): each journal entry is exported once, in a layout stored as data; exports are delivered,
-- acknowledged or rejected; GL balances are imported for reconciliation; posting rules change under four eyes.

CREATE TABLE gl_export_format (
    code         TEXT    NOT NULL PRIMARY KEY,
    name         TEXT    NOT NULL,
    -- Columns, delimiter and amount style; see GlExportLayout.
    layout       JSONB   NOT NULL,
    -- False until checked against the receiving system's specification.
    verified     BOOLEAN NOT NULL DEFAULT false,
    active       BOOLEAN NOT NULL DEFAULT true
);

INSERT INTO gl_export_format (code, name, layout, verified) VALUES
    ('GENERIC_CSV', 'Generic CSV, one row per journal line', '{"delimiter": ",", "header": true, "style": "GENERIC"}', true),
    ('SAP_FLAT', 'SAP-style flat file (FI document lines)', '{"delimiter": ";", "header": true, "style": "COLUMNS", "columns": [
        {"name": "BUKRS", "source": "ENTITY_CODE"},
        {"name": "BLDAT", "source": "POSTING_DATE", "format": "yyyyMMdd"},
        {"name": "BUDAT", "source": "POSTING_DATE", "format": "yyyyMMdd"},
        {"name": "MONAT", "source": "PERIOD_MONTH"},
        {"name": "XBLNR", "source": "ENTRY_NUMBER"},
        {"name": "BUZEI", "source": "LINE_NO"},
        {"name": "HKONT", "source": "GL_ACCOUNT"},
        {"name": "SHKZG", "source": "DEBIT_CREDIT", "debit": "S", "credit": "H"},
        {"name": "WRBTR", "source": "AMOUNT"},
        {"name": "WAERS", "source": "CURRENCY"},
        {"name": "DMBTR", "source": "BASE_AMOUNT"},
        {"name": "HWAER", "source": "BASE_CURRENCY"},
        {"name": "SGTXT", "source": "DESCRIPTION", "maxLength": 50}
    ]}', false);

ALTER TABLE gl_export ADD COLUMN kind TEXT NOT NULL DEFAULT 'PERIOD' CHECK (kind IN ('PERIOD', 'DELTA'));
ALTER TABLE gl_export ADD COLUMN format TEXT NOT NULL DEFAULT 'GENERIC_CSV';
ALTER TABLE gl_export ADD COLUMN status TEXT NOT NULL DEFAULT 'GENERATED' CHECK (status IN ('GENERATED', 'DELIVERED', 'ACKNOWLEDGED', 'REJECTED'));
ALTER TABLE gl_export ADD COLUMN status_reason TEXT;
ALTER TABLE gl_export ADD COLUMN acknowledgement_ref TEXT;
ALTER TABLE gl_export ADD COLUMN status_changed_by TEXT;
ALTER TABLE gl_export ADD COLUMN status_changed_at TIMESTAMPTZ;

-- Which export carried each journal entry. A rejected export releases its entries for the next one.
CREATE TABLE gl_export_entry (
    export_id  UUID    NOT NULL REFERENCES gl_export (id),
    entry_id   UUID    NOT NULL REFERENCES journal_entry (id),
    active     BOOLEAN NOT NULL DEFAULT true,
    PRIMARY KEY (export_id, entry_id)
);
CREATE UNIQUE INDEX gl_export_entry_once_uq ON gl_export_entry (entry_id) WHERE active;

-- Closing balances per GL account as the general ledger reports them, for reconciliation.
CREATE TABLE gl_balance (
    legal_entity_id  UUID           NOT NULL,
    period           CHAR(7)        NOT NULL,
    gl_account       TEXT           NOT NULL,
    balance          NUMERIC(18, 2) NOT NULL,
    currency         CHAR(3)        NOT NULL,
    imported_by      TEXT           NOT NULL,
    imported_at      TIMESTAMPTZ    NOT NULL DEFAULT now(),
    PRIMARY KEY (legal_entity_id, period, gl_account)
);

-- Posting rule changes wait for a second accountant; every applied version is kept.
CREATE TABLE posting_rule_change (
    id            UUID        NOT NULL PRIMARY KEY,
    legal_entity_id UUID,
    rule_code     TEXT        NOT NULL,
    payload       JSONB       NOT NULL,
    status        TEXT        NOT NULL CHECK (status IN ('PENDING', 'APPLIED', 'REJECTED')),
    requested_by  TEXT        NOT NULL,
    requested_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    decided_by    TEXT,
    decided_at    TIMESTAMPTZ,
    CONSTRAINT posting_rule_change_four_eyes_ck CHECK (decided_by IS NULL OR decided_by <> requested_by)
);

CREATE TABLE posting_rule_history (
    rule_code       TEXT        NOT NULL,
    legal_entity_id UUID,
    version         INT         NOT NULL,
    payload         JSONB       NOT NULL,
    change_id       UUID REFERENCES posting_rule_change (id),
    recorded_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX posting_rule_history_uq ON posting_rule_history (rule_code, COALESCE(legal_entity_id, '00000000-0000-0000-0000-000000000000'::uuid), version);
