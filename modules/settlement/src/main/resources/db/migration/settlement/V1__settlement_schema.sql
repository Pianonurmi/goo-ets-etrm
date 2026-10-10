-- Settlement: what is owed for each delivery, the VAT rules that apply, and the invoices that claim it.

-- VAT rules are data, effective-dated and approved by a second person. Seeds are marked as needing the tax
-- adviser's sign-off before production use.
CREATE TABLE vat_rule (
    id                UUID          NOT NULL PRIMARY KEY,
    code              TEXT          NOT NULL,
    version           INT           NOT NULL,
    description       TEXT          NOT NULL,
    product_family    TEXT          NOT NULL CHECK (product_family IN ('GUARANTEE_OF_ORIGIN', 'EMISSION_ALLOWANCE', '*')),
    supplier_country  TEXT          NOT NULL CHECK (supplier_country ~ '^([A-Z]{2}|\*)$'),
    scope             TEXT          NOT NULL CHECK (scope IN ('DOMESTIC', 'INTRA_EU', 'IMPORT', 'EXPORT', 'ANY')),
    treatment         TEXT          NOT NULL CHECK (treatment IN (
                          'STANDARD', 'REVERSE_CHARGE_DOMESTIC', 'REVERSE_CHARGE_CROSS_BORDER', 'OUT_OF_SCOPE', 'EXEMPT')),
    rate              NUMERIC(5, 2) NOT NULL CHECK (rate >= 0 AND rate < 100),
    valid_from        DATE          NOT NULL,
    valid_to          DATE,
    legal_text        TEXT,
    requires_sign_off BOOLEAN       NOT NULL DEFAULT TRUE,
    status            TEXT          NOT NULL CHECK (status IN ('DRAFT', 'APPROVED', 'RETIRED')),
    created_by        TEXT          NOT NULL,
    created_at        TIMESTAMPTZ   NOT NULL DEFAULT now(),
    approved_by       TEXT,
    approved_at       TIMESTAMPTZ,
    CONSTRAINT vat_rule_code_version UNIQUE (code, version),
    CONSTRAINT vat_rule_period CHECK (valid_to IS NULL OR valid_to > valid_from),
    CONSTRAINT vat_rule_zero_rate CHECK (treatment = 'STANDARD' OR rate = 0),
    CONSTRAINT vat_rule_four_eyes CHECK (status = 'DRAFT' OR (approved_by IS NOT NULL AND approved_by <> created_by))
);

INSERT INTO vat_rule (id, code, version, description, product_family, supplier_country, scope, treatment, rate, valid_from, valid_to,
                      legal_text, status, created_by, approved_by, approved_at) VALUES
    ('01900000-0000-7000-8000-0000000000a1', 'EUA-RC-FI', 1, 'Emission allowances, Finland, domestic reverse charge', 'EMISSION_ALLOWANCE', 'FI',
     'DOMESTIC', 'REVERSE_CHARGE_DOMESTIC', 0, '2011-01-01', '2027-01-01',
     'Käännetty verovelvollisuus / Reverse charge, AVL 8 d § (VAT Directive Art. 199a)', 'APPROVED', 'migration', 'seed', now()),
    ('01900000-0000-7000-8000-0000000000a2', 'EUA-RC-SE', 1, 'Emission allowances, Sweden, domestic reverse charge', 'EMISSION_ALLOWANCE', 'SE',
     'DOMESTIC', 'REVERSE_CHARGE_DOMESTIC', 0, '2011-01-01', '2027-01-01',
     'Omvänd betalningsskyldighet / Reverse charge (VAT Directive Art. 199a)', 'APPROVED', 'migration', 'seed', now()),
    ('01900000-0000-7000-8000-0000000000a3', 'EUA-RC-DE', 1, 'Emission allowances, Germany, domestic reverse charge', 'EMISSION_ALLOWANCE', 'DE',
     'DOMESTIC', 'REVERSE_CHARGE_DOMESTIC', 0, '2011-01-01', '2027-01-01',
     'Steuerschuldnerschaft des Leistungsempfängers / Reverse charge, § 13b UStG (VAT Directive Art. 199a)', 'APPROVED', 'migration', 'seed', now()),
    ('01900000-0000-7000-8000-0000000000a4', 'EUA-STD-FI', 1, 'Emission allowances, Finland, standard rate once Art. 199a lapses', 'EMISSION_ALLOWANCE',
     'FI', 'DOMESTIC', 'STANDARD', 25.5, '2027-01-01', NULL, NULL, 'APPROVED', 'migration', 'seed', now()),
    ('01900000-0000-7000-8000-0000000000a5', 'EUA-STD-SE', 1, 'Emission allowances, Sweden, standard rate once Art. 199a lapses', 'EMISSION_ALLOWANCE',
     'SE', 'DOMESTIC', 'STANDARD', 25, '2027-01-01', NULL, NULL, 'APPROVED', 'migration', 'seed', now()),
    ('01900000-0000-7000-8000-0000000000a6', 'EUA-STD-DE', 1, 'Emission allowances, Germany, standard rate once Art. 199a lapses', 'EMISSION_ALLOWANCE',
     'DE', 'DOMESTIC', 'STANDARD', 19, '2027-01-01', NULL, NULL, 'APPROVED', 'migration', 'seed', now()),
    ('01900000-0000-7000-8000-0000000000b1', 'GOO-RC-DE', 1, 'Guarantees of origin, Germany, domestic reverse charge', 'GUARANTEE_OF_ORIGIN', 'DE',
     'DOMESTIC', 'REVERSE_CHARGE_DOMESTIC', 0, '2011-01-01', NULL,
     'Steuerschuldnerschaft des Leistungsempfängers / Reverse charge, § 13b UStG', 'APPROVED', 'migration', 'seed', now()),
    ('01900000-0000-7000-8000-0000000000b2', 'GOO-STD-FI', 1, 'Guarantees of origin, Finland, standard rate 24 %', 'GUARANTEE_OF_ORIGIN', 'FI',
     'DOMESTIC', 'STANDARD', 24, '2013-01-01', '2024-09-01', NULL, 'APPROVED', 'migration', 'seed', now()),
    ('01900000-0000-7000-8000-0000000000b3', 'GOO-STD-FI', 2, 'Guarantees of origin, Finland, standard rate 25.5 %', 'GUARANTEE_OF_ORIGIN', 'FI',
     'DOMESTIC', 'STANDARD', 25.5, '2024-09-01', NULL, NULL, 'APPROVED', 'migration', 'seed', now()),
    ('01900000-0000-7000-8000-0000000000b4', 'GOO-STD-SE', 1, 'Guarantees of origin, Sweden, standard rate 25 %', 'GUARANTEE_OF_ORIGIN', 'SE',
     'DOMESTIC', 'STANDARD', 25, '2011-01-01', NULL, NULL, 'APPROVED', 'migration', 'seed', now()),
    ('01900000-0000-7000-8000-0000000000c1', 'B2B-INTRA-EU', 1, 'Services to a business in another member state', '*', '*',
     'INTRA_EU', 'REVERSE_CHARGE_CROSS_BORDER', 0, '2010-01-01', NULL,
     'Reverse charge, VAT Directive Art. 44 and 196', 'APPROVED', 'migration', 'seed', now()),
    ('01900000-0000-7000-8000-0000000000c2', 'B2B-IMPORT', 1, 'Services from a business outside the EU', '*', '*',
     'IMPORT', 'REVERSE_CHARGE_CROSS_BORDER', 0, '2010-01-01', NULL,
     'Reverse charge, VAT Directive Art. 44 and 196', 'APPROVED', 'migration', 'seed', now()),
    ('01900000-0000-7000-8000-0000000000c3', 'B2B-EXPORT', 1, 'Services to a business outside the EU', '*', '*',
     'EXPORT', 'OUT_OF_SCOPE', 0, '2010-01-01', NULL,
     'Outside the scope of EU VAT, VAT Directive Art. 44', 'APPROVED', 'migration', 'seed', now());

-- One item per recorded delivery: what is owed, by whom, and how it is taxed.
CREATE TABLE settlement_item (
    id              UUID           NOT NULL PRIMARY KEY,
    delivery_id     UUID           NOT NULL UNIQUE,
    trade_id        UUID           NOT NULL,
    trade_number    TEXT           NOT NULL,
    schedule_id     UUID           NOT NULL,
    legal_entity_id UUID           NOT NULL,
    counterparty_id UUID           NOT NULL,
    direction       TEXT           NOT NULL CHECK (direction IN ('RECEIVABLE', 'PAYABLE')),
    product_family  TEXT           NOT NULL,
    description     TEXT           NOT NULL,
    quantity        NUMERIC(18, 3) NOT NULL CHECK (quantity > 0),
    unit            TEXT           NOT NULL,
    unit_price      NUMERIC(18, 6),
    currency        CHAR(3)        NOT NULL,
    net_amount      NUMERIC(18, 2),
    delivered_on    DATE           NOT NULL,
    vat_rule_code   TEXT,
    vat_rule_version INT,
    vat_treatment   TEXT,
    vat_rate        NUMERIC(5, 2),
    vat_amount      NUMERIC(18, 2),
    status          TEXT           NOT NULL CHECK (status IN ('OPEN', 'NEEDS_ATTENTION', 'INVOICED', 'CANCELLED')),
    problem         TEXT,
    invoice_id      UUID,
    reference       TEXT,
    version         INT            NOT NULL DEFAULT 1,
    created_at      TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ    NOT NULL DEFAULT now(),
    CONSTRAINT settlement_item_priced CHECK (status = 'NEEDS_ATTENTION' OR (net_amount IS NOT NULL AND vat_treatment IS NOT NULL))
);
CREATE INDEX settlement_item_open_idx ON settlement_item (legal_entity_id, counterparty_id, status);
CREATE INDEX settlement_item_schedule_idx ON settlement_item (schedule_id);

-- Prices of index and formula legs, fixed by hand until market data arrives (increment 3).
CREATE TABLE price_fixing (
    schedule_id UUID           NOT NULL PRIMARY KEY,
    trade_id    UUID           NOT NULL,
    price       NUMERIC(18, 6) NOT NULL CHECK (price >= 0),
    currency    CHAR(3)        NOT NULL,
    note        TEXT,
    fixed_by    TEXT           NOT NULL,
    fixed_at    TIMESTAMPTZ    NOT NULL DEFAULT now()
);

-- Gap-free numbering: the row is locked and incremented in the issuing transaction; a rollback returns the number.
CREATE TABLE number_series (
    legal_entity_id UUID   NOT NULL,
    series          TEXT   NOT NULL,
    fiscal_year     INT    NOT NULL,
    next_value      BIGINT NOT NULL DEFAULT 1 CHECK (next_value > 0),
    PRIMARY KEY (legal_entity_id, series, fiscal_year)
);

CREATE TABLE invoice (
    id                    UUID           NOT NULL PRIMARY KEY,
    legal_entity_id       UUID           NOT NULL,
    counterparty_id       UUID           NOT NULL,
    kind                  TEXT           NOT NULL CHECK (kind IN ('SALES', 'SELF_BILLED', 'PURCHASE', 'CREDIT_NOTE', 'PURCHASE_CREDIT_NOTE')),
    direction             TEXT           NOT NULL CHECK (direction IN ('RECEIVABLE', 'PAYABLE')),
    status                TEXT           NOT NULL CHECK (status IN ('DRAFT', 'PENDING_APPROVAL', 'ISSUED', 'CANCELLED')),
    number                TEXT,
    counterparty_invoice_ref TEXT,
    currency              CHAR(3)        NOT NULL,
    invoice_date          DATE           NOT NULL,
    tax_point_date        DATE           NOT NULL,
    due_date              DATE,
    net_total             NUMERIC(18, 2) NOT NULL,
    vat_total             NUMERIC(18, 2) NOT NULL,
    gross_total           NUMERIC(18, 2) NOT NULL,
    supplier              JSONB,
    customer              JSONB,
    bank_account          JSONB,
    notes                 JSONB          NOT NULL DEFAULT '[]'::jsonb,
    original_invoice_id   UUID REFERENCES invoice (id),
    document_id           UUID,
    created_by            TEXT           NOT NULL,
    created_at            TIMESTAMPTZ    NOT NULL DEFAULT now(),
    submitted_by          TEXT,
    approved_by           TEXT,
    issued_at             TIMESTAMPTZ,
    version               INT            NOT NULL DEFAULT 1,
    CONSTRAINT invoice_number_uq UNIQUE (legal_entity_id, number),
    CONSTRAINT invoice_totals CHECK (gross_total = net_total + vat_total),
    CONSTRAINT invoice_issued CHECK (status <> 'ISSUED' OR (number IS NOT NULL AND issued_at IS NOT NULL AND due_date IS NOT NULL)),
    CONSTRAINT invoice_four_eyes CHECK (status <> 'ISSUED' OR (approved_by IS NOT NULL AND approved_by <> submitted_by))
);
CREATE INDEX invoice_party_idx ON invoice (legal_entity_id, counterparty_id, status);
CREATE UNIQUE INDEX invoice_supplier_ref_uq ON invoice (legal_entity_id, counterparty_id, counterparty_invoice_ref)
    WHERE counterparty_invoice_ref IS NOT NULL AND status <> 'CANCELLED';

CREATE TABLE invoice_line (
    id                 UUID           NOT NULL PRIMARY KEY,
    invoice_id         UUID           NOT NULL REFERENCES invoice (id),
    line_no            INT            NOT NULL,
    line_type          TEXT           NOT NULL CHECK (line_type IN ('COMMODITY', 'FEE', 'ADJUSTMENT')),
    settlement_item_id UUID REFERENCES settlement_item (id),
    description        TEXT           NOT NULL,
    quantity           NUMERIC(18, 3),
    unit               TEXT,
    unit_price         NUMERIC(18, 6),
    net_amount         NUMERIC(18, 2) NOT NULL,
    vat_rule_code      TEXT,
    vat_treatment      TEXT           NOT NULL,
    vat_rate           NUMERIC(5, 2)  NOT NULL,
    vat_amount         NUMERIC(18, 2) NOT NULL,
    CONSTRAINT invoice_line_uq UNIQUE (invoice_id, line_no)
);

-- An issued invoice is a legal document: its number, dates, amounts, parties and lines never change.
CREATE FUNCTION invoice_issued_immutable() RETURNS trigger LANGUAGE plpgsql SET search_path FROM CURRENT AS $$
BEGIN
    IF OLD.status = 'ISSUED' AND (NEW.number IS DISTINCT FROM OLD.number OR NEW.invoice_date IS DISTINCT FROM OLD.invoice_date
        OR NEW.due_date IS DISTINCT FROM OLD.due_date OR NEW.net_total IS DISTINCT FROM OLD.net_total
        OR NEW.vat_total IS DISTINCT FROM OLD.vat_total OR NEW.supplier IS DISTINCT FROM OLD.supplier
        OR NEW.customer IS DISTINCT FROM OLD.customer OR NEW.document_id IS DISTINCT FROM OLD.document_id
        OR NEW.status <> 'ISSUED') THEN
        RAISE EXCEPTION 'invoice % is issued and cannot change', OLD.number USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER invoice_issued_immutable BEFORE UPDATE ON invoice FOR EACH ROW EXECUTE FUNCTION invoice_issued_immutable();

CREATE FUNCTION invoice_line_frozen() RETURNS trigger LANGUAGE plpgsql SET search_path FROM CURRENT AS $$
DECLARE
    v_status TEXT;
BEGIN
    SELECT status INTO v_status FROM invoice WHERE id = COALESCE(NEW.invoice_id, OLD.invoice_id);
    IF v_status IN ('ISSUED', 'PENDING_APPROVAL') THEN
        RAISE EXCEPTION 'lines of a % invoice cannot change', v_status USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    RETURN COALESCE(NEW, OLD);
END $$;
CREATE TRIGGER invoice_line_frozen BEFORE INSERT OR UPDATE OR DELETE ON invoice_line FOR EACH ROW EXECUTE FUNCTION invoice_line_frozen();
