-- Allowance holdings statements: the Union Registry reports balances per account and unit type, not serial ranges.
-- Their reconciliation compares balances and raises BALANCE_MISMATCH breaks.

ALTER TABLE registry_statement
    ADD COLUMN kind TEXT NOT NULL DEFAULT 'GOO' CHECK (kind IN ('GOO', 'ALLOWANCE'));

CREATE TABLE allowance_statement_line (
    id           UUID   NOT NULL PRIMARY KEY,
    statement_id UUID   NOT NULL REFERENCES registry_statement (id) ON DELETE CASCADE,
    line_no      INT    NOT NULL,
    product_code TEXT   NOT NULL,
    quantity     BIGINT NOT NULL CHECK (quantity >= 0),
    raw          JSONB  NOT NULL DEFAULT '{}'::jsonb,
    CONSTRAINT allowance_statement_line_uq UNIQUE (statement_id, line_no),
    CONSTRAINT allowance_statement_line_product_uq UNIQUE (statement_id, product_code)
);

ALTER TABLE reconciliation_break
    DROP CONSTRAINT reconciliation_break_break_type_check,
    ADD CONSTRAINT reconciliation_break_break_type_check
        CHECK (break_type IN ('MISSING_INTERNALLY', 'MISSING_IN_REGISTRY', 'ATTRIBUTE_MISMATCH', 'BALANCE_MISMATCH')),
    ALTER COLUMN issuance_ref DROP NOT NULL,
    ALTER COLUMN cert_no_start DROP NOT NULL,
    ALTER COLUMN cert_no_end DROP NOT NULL,
    ADD COLUMN product_code      TEXT,
    ADD COLUMN registry_quantity BIGINT,
    ADD COLUMN internal_quantity BIGINT,
    ADD CONSTRAINT reconciliation_break_shape CHECK (
        (break_type = 'BALANCE_MISMATCH' AND product_code IS NOT NULL AND registry_quantity IS NOT NULL AND internal_quantity IS NOT NULL)
        OR (break_type <> 'BALANCE_MISMATCH' AND issuance_ref IS NOT NULL AND cert_no_start IS NOT NULL AND cert_no_end IS NOT NULL)
    );
