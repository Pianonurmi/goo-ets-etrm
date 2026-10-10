-- Payments, their allocation to invoices (also by credit notes and netting), and netting statements.

ALTER TABLE invoice
    ADD COLUMN paid_amount    NUMERIC(18, 2) NOT NULL DEFAULT 0,
    ADD COLUMN payment_status TEXT           NOT NULL DEFAULT 'UNPAID' CHECK (payment_status IN ('UNPAID', 'PARTIALLY_PAID', 'PAID')),
    ADD COLUMN credited       BOOLEAN        NOT NULL DEFAULT FALSE;

CREATE TABLE payment (
    id               UUID           NOT NULL PRIMARY KEY,
    legal_entity_id  UUID           NOT NULL,
    counterparty_id  UUID,
    direction        TEXT           NOT NULL CHECK (direction IN ('INCOMING', 'OUTGOING')),
    amount           NUMERIC(18, 2) NOT NULL CHECK (amount > 0),
    currency         CHAR(3)        NOT NULL,
    value_date       DATE           NOT NULL,
    bank_reference   TEXT           NOT NULL,
    remittance_info  TEXT,
    source           TEXT           NOT NULL CHECK (source IN ('MANUAL', 'CSV')),
    unapplied_amount NUMERIC(18, 2) NOT NULL CHECK (unapplied_amount >= 0 AND unapplied_amount <= amount),
    created_by       TEXT           NOT NULL,
    created_at       TIMESTAMPTZ    NOT NULL DEFAULT now(),
    CONSTRAINT payment_bank_reference_uq UNIQUE (legal_entity_id, bank_reference)
);
CREATE INDEX payment_party_idx ON payment (legal_entity_id, counterparty_id);

CREATE TABLE netting_statement (
    id               UUID           NOT NULL PRIMARY KEY,
    legal_entity_id  UUID           NOT NULL,
    counterparty_id  UUID           NOT NULL,
    currency         CHAR(3)        NOT NULL,
    value_date       DATE           NOT NULL,
    status           TEXT           NOT NULL CHECK (status IN ('DRAFT', 'AGREED', 'SETTLED', 'CANCELLED')),
    receivable_total NUMERIC(18, 2) NOT NULL,
    payable_total    NUMERIC(18, 2) NOT NULL,
    net_amount       NUMERIC(18, 2) NOT NULL,
    counterparty_ref TEXT,
    settlement_payment_id UUID REFERENCES payment (id),
    document_id      UUID,
    created_by       TEXT           NOT NULL,
    created_at       TIMESTAMPTZ    NOT NULL DEFAULT now(),
    agreed_by        TEXT,
    agreed_at        TIMESTAMPTZ,
    settled_at       TIMESTAMPTZ,
    version          INT            NOT NULL DEFAULT 1,
    CONSTRAINT netting_net CHECK (net_amount = receivable_total - payable_total),
    CONSTRAINT netting_four_eyes CHECK (status IN ('DRAFT', 'CANCELLED') OR (agreed_by IS NOT NULL AND agreed_by <> created_by))
);

CREATE TABLE netting_statement_invoice (
    statement_id UUID           NOT NULL REFERENCES netting_statement (id),
    invoice_id   UUID           NOT NULL REFERENCES invoice (id),
    amount       NUMERIC(18, 2) NOT NULL,
    PRIMARY KEY (statement_id, invoice_id)
);

-- What settles an invoice: a payment, a credit note, or a netting statement. Append-only.
CREATE TABLE payment_allocation (
    id                   UUID           NOT NULL PRIMARY KEY,
    invoice_id           UUID           NOT NULL REFERENCES invoice (id),
    kind                 TEXT           NOT NULL CHECK (kind IN ('PAYMENT', 'CREDIT_NOTE', 'NETTING')),
    amount               NUMERIC(18, 2) NOT NULL CHECK (amount > 0),
    payment_id           UUID REFERENCES payment (id),
    credit_note_id       UUID REFERENCES invoice (id),
    netting_statement_id UUID REFERENCES netting_statement (id),
    allocated_by         TEXT           NOT NULL,
    allocated_at         TIMESTAMPTZ    NOT NULL DEFAULT now(),
    CONSTRAINT payment_allocation_source CHECK (
        (kind = 'PAYMENT' AND payment_id IS NOT NULL)
        OR (kind = 'CREDIT_NOTE' AND credit_note_id IS NOT NULL)
        OR (kind = 'NETTING' AND netting_statement_id IS NOT NULL)
    )
);
CREATE INDEX payment_allocation_invoice_idx ON payment_allocation (invoice_id);

CREATE FUNCTION payment_allocation_append_only() RETURNS trigger LANGUAGE plpgsql SET search_path FROM CURRENT AS $$
BEGIN
    RAISE EXCEPTION 'payment allocations are append-only' USING ERRCODE = 'integrity_constraint_violation';
END $$;
CREATE TRIGGER payment_allocation_append_only BEFORE UPDATE OR DELETE ON payment_allocation
    FOR EACH ROW EXECUTE FUNCTION payment_allocation_append_only();
