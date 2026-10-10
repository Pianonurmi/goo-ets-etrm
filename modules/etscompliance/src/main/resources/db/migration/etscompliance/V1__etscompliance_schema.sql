-- EU ETS compliance: installations and aircraft operators with their holding accounts, emissions (forecast, monthly
-- estimates, verified reports under four eyes), free allocation, surrenders attributed to compliance years, and the
-- regulatory deadlines as data.

CREATE TABLE installation (
    id                      UUID        NOT NULL PRIMARY KEY,
    legal_entity_id         UUID        NOT NULL,
    kind                    TEXT        NOT NULL CHECK (kind IN ('INSTALLATION', 'AIRCRAFT_OPERATOR')),
    eutl_id                 TEXT        NOT NULL UNIQUE,
    permit_id               TEXT,
    name                    TEXT        NOT NULL,
    country                 CHAR(2)     NOT NULL,
    activity_code           TEXT,
    monitoring_plan_ref     TEXT,
    monitoring_plan_version TEXT,
    registry_account_id     UUID        NOT NULL UNIQUE,
    eligible_products       JSONB       NOT NULL DEFAULT '["EUA"]'::jsonb,
    valid_from              DATE        NOT NULL,
    valid_to                DATE,
    active                  BOOLEAN     NOT NULL DEFAULT TRUE,
    version                 INT         NOT NULL DEFAULT 1,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX installation_entity_idx ON installation (legal_entity_id);

CREATE TABLE emission_record (
    id              UUID        NOT NULL PRIMARY KEY,
    installation_id UUID        NOT NULL REFERENCES installation (id),
    compliance_year INT         NOT NULL CHECK (compliance_year BETWEEN 2005 AND 2100),
    kind            TEXT        NOT NULL CHECK (kind IN ('FORECAST', 'MONTHLY_ESTIMATE', 'VERIFIED')),
    period_month    INT CHECK (period_month BETWEEN 1 AND 12),
    quantity_tco2e  BIGINT      NOT NULL CHECK (quantity_tco2e >= 0),
    status          TEXT        NOT NULL CHECK (status IN ('PENDING_APPROVAL', 'APPROVED', 'REJECTED', 'SUPERSEDED')),
    verifier        TEXT,
    verified_on     DATE,
    document_ref    TEXT,
    note            TEXT,
    recorded_by     TEXT        NOT NULL,
    recorded_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    approved_by     TEXT,
    approved_at     TIMESTAMPTZ,
    CONSTRAINT emission_month_ck CHECK ((kind = 'MONTHLY_ESTIMATE') = (period_month IS NOT NULL)),
    CONSTRAINT emission_verified_ck CHECK (kind <> 'VERIFIED' OR (verifier IS NOT NULL AND verified_on IS NOT NULL)),
    CONSTRAINT emission_four_eyes_ck CHECK (kind <> 'VERIFIED' OR approved_by IS NULL OR approved_by <> recorded_by)
);
CREATE UNIQUE INDEX emission_current_uq ON emission_record (installation_id, compliance_year, kind, COALESCE(period_month, 0)) WHERE status = 'APPROVED';

CREATE TABLE free_allocation (
    installation_id UUID        NOT NULL REFERENCES installation (id),
    allocation_year INT         NOT NULL,
    allocated_qty   BIGINT      NOT NULL CHECK (allocated_qty >= 0),
    adjusted_qty    BIGINT CHECK (adjusted_qty >= 0),
    status          TEXT        NOT NULL CHECK (status IN ('EXPECTED', 'ISSUED', 'WITHHELD')),
    note            TEXT,
    updated_by      TEXT        NOT NULL,
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (installation_id, allocation_year)
);

-- Each surrender movement on an installation's account, attributed to the compliance year it covers.
CREATE TABLE surrender_record (
    movement_seq    BIGINT      NOT NULL PRIMARY KEY,
    installation_id UUID        NOT NULL REFERENCES installation (id),
    compliance_year INT         NOT NULL,
    product_code    TEXT        NOT NULL,
    quantity        BIGINT      NOT NULL CHECK (quantity > 0),
    attribution     TEXT        NOT NULL CHECK (attribution IN ('EXPLICIT', 'EARLIEST_OPEN_YEAR')),
    surrendered_on  DATE        NOT NULL,
    recorded_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX surrender_record_year_idx ON surrender_record (installation_id, compliance_year);

CREATE TABLE surrender_request (
    id              UUID        NOT NULL PRIMARY KEY,
    installation_id UUID        NOT NULL REFERENCES installation (id),
    compliance_year INT         NOT NULL,
    product_code    TEXT        NOT NULL,
    quantity        BIGINT      NOT NULL CHECK (quantity > 0),
    instruction_id  UUID        NOT NULL UNIQUE,
    idempotency_key TEXT        NOT NULL UNIQUE,
    status          TEXT        NOT NULL CHECK (status IN ('SUBMITTED', 'CONFIRMED', 'FAILED', 'CANCELLED')),
    requested_by    TEXT        NOT NULL,
    requested_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Regulatory deadlines: month and day in the compliance year plus an offset in years, from a first compliance year.
CREATE TABLE compliance_deadline_rule (
    code                TEXT NOT NULL,
    effective_from_year INT  NOT NULL,
    year_offset         INT  NOT NULL CHECK (year_offset IN (0, 1)),
    deadline_month      INT  NOT NULL CHECK (deadline_month BETWEEN 1 AND 12),
    deadline_day        INT  NOT NULL CHECK (deadline_day BETWEEN 1 AND 31),
    description         TEXT NOT NULL,
    PRIMARY KEY (code, effective_from_year),
    CONSTRAINT deadline_code_ck CHECK (code IN ('VERIFIED_EMISSIONS', 'FREE_ALLOCATION', 'SURRENDER'))
);

INSERT INTO compliance_deadline_rule (code, effective_from_year, year_offset, deadline_month, deadline_day, description) VALUES
    ('VERIFIED_EMISSIONS', 2005, 1, 3, 31, 'Verified annual emissions report submitted by 31 March of the following year'),
    ('FREE_ALLOCATION', 2013, 0, 2, 28, 'Free allocation issued by 28 February of the year'),
    ('FREE_ALLOCATION', 2024, 0, 6, 30, 'Free allocation issued by 30 June of the year (2023 revision; confirm the first year with compliance)'),
    ('SURRENDER', 2005, 1, 4, 30, 'Allowances surrendered by 30 April of the following year'),
    ('SURRENDER', 2024, 1, 9, 30, 'Allowances surrendered by 30 September of the following year (from 2024 emissions)');

-- Reminders sent per deadline and stage (days before), so each stage alerts once.
CREATE TABLE deadline_alert (
    installation_id UUID        NOT NULL REFERENCES installation (id),
    code            TEXT        NOT NULL,
    compliance_year INT         NOT NULL,
    days_before     INT         NOT NULL,
    task_subject    UUID        NOT NULL,
    raised_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (installation_id, code, compliance_year, days_before)
);
