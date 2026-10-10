-- What invoicing and payments need to know about parties: addresses, VAT registrations, bank accounts (changed only
-- with four eyes) and the counterparty's settlement agreements (netting, self-billing).

ALTER TABLE counterparty
    ADD COLUMN netting_agreed BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN self_billing   BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN invoice_email  TEXT;

CREATE TABLE party_address (
    id           UUID        NOT NULL PRIMARY KEY,
    party_type   TEXT        NOT NULL CHECK (party_type IN ('LEGAL_ENTITY', 'COUNTERPARTY')),
    party_id     UUID        NOT NULL,
    address_type TEXT        NOT NULL CHECK (address_type IN ('REGISTERED', 'BILLING')),
    line1        TEXT        NOT NULL,
    line2        TEXT,
    postal_code  TEXT        NOT NULL,
    city         TEXT        NOT NULL,
    country      CHAR(2)     NOT NULL,
    version      INT         NOT NULL DEFAULT 1,
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT party_address_uq UNIQUE (party_type, party_id, address_type)
);
CREATE TRIGGER party_address_history AFTER INSERT OR UPDATE ON party_address FOR EACH ROW EXECUTE FUNCTION reference_history_trigger();

CREATE TABLE vat_registration (
    id         UUID        NOT NULL PRIMARY KEY,
    party_type TEXT        NOT NULL CHECK (party_type IN ('LEGAL_ENTITY', 'COUNTERPARTY')),
    party_id   UUID        NOT NULL,
    country    CHAR(2)     NOT NULL,
    vat_number TEXT        NOT NULL,
    valid_from DATE        NOT NULL,
    valid_to   DATE,
    version    INT         NOT NULL DEFAULT 1,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT vat_registration_uq UNIQUE (party_type, party_id, country),
    CONSTRAINT vat_registration_period CHECK (valid_to IS NULL OR valid_to > valid_from)
);
CREATE TRIGGER vat_registration_history AFTER INSERT OR UPDATE ON vat_registration FOR EACH ROW EXECUTE FUNCTION reference_history_trigger();

CREATE TABLE bank_account (
    id           UUID        NOT NULL PRIMARY KEY,
    party_type   TEXT        NOT NULL CHECK (party_type IN ('LEGAL_ENTITY', 'COUNTERPARTY')),
    party_id     UUID        NOT NULL,
    currency     CHAR(3)     NOT NULL,
    iban         TEXT        NOT NULL CHECK (iban ~ '^[A-Z]{2}[0-9]{2}[A-Z0-9]{11,30}$'),
    bic          TEXT        NOT NULL CHECK (bic ~ '^[A-Z]{6}[A-Z0-9]{2}([A-Z0-9]{3})?$'),
    account_name TEXT        NOT NULL,
    active       BOOLEAN     NOT NULL DEFAULT TRUE,
    version      INT         NOT NULL DEFAULT 1,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT bank_account_uq UNIQUE (party_type, party_id, iban)
);
CREATE INDEX bank_account_party_idx ON bank_account (party_type, party_id);
CREATE TRIGGER bank_account_history AFTER INSERT OR UPDATE ON bank_account FOR EACH ROW EXECUTE FUNCTION reference_history_trigger();

-- Four eyes on master data changes also in the database: nobody applies their own request.
ALTER TABLE change_request ADD CONSTRAINT change_request_four_eyes CHECK (status <> 'APPLIED' OR decided_by <> requested_by);
