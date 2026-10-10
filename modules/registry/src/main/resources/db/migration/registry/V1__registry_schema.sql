-- Registry integration: instructions follow instruction -> execution (API, file or manual) -> confirmation.
-- Several registries (notably the Union Registry) offer no API to account holders, so the same state machine
-- covers an adapter that executes directly and an operator who executes in the registry UI and confirms here.

CREATE TABLE registry_instruction (
    id               UUID           NOT NULL PRIMARY KEY,
    instruction_key  TEXT           NOT NULL UNIQUE,
    instruction_type TEXT           NOT NULL CHECK (instruction_type IN ('TRANSFER', 'CANCELLATION')),
    registry_code    TEXT           NOT NULL,
    holder_entity_id UUID           NOT NULL,
    from_account_id  UUID           NOT NULL,
    to_registry_code TEXT,
    to_account_ref   TEXT,
    lot_id           UUID           NOT NULL,
    cert_no_start    NUMERIC(40, 0) NOT NULL,
    cert_no_end      NUMERIC(40, 0) NOT NULL,
    quantity_mwh     BIGINT         NOT NULL CHECK (quantity_mwh > 0),
    cancellation     JSONB,
    plan_ref         UUID,
    line_ref         UUID,
    obligation_ref   UUID,
    status           TEXT           NOT NULL CHECK (status IN ('PENDING', 'SENT', 'ACKNOWLEDGED', 'CONFIRMED', 'FAILED', 'CANCELLED')),
    external_ref     TEXT,
    request_payload  JSONB          NOT NULL DEFAULT '{}'::jsonb,
    response_payload JSONB,
    attempts         INT            NOT NULL DEFAULT 0,
    last_error       TEXT,
    submitted_by     TEXT           NOT NULL,
    confirmed_by     TEXT,
    created_at       TIMESTAMPTZ    NOT NULL DEFAULT now(),
    sent_at          TIMESTAMPTZ,
    confirmed_at     TIMESTAMPTZ,
    version          INT            NOT NULL DEFAULT 1
);
CREATE INDEX registry_instruction_open_idx ON registry_instruction (registry_code, status) WHERE status IN ('PENDING', 'SENT', 'ACKNOWLEDGED');
CREATE INDEX registry_instruction_lot_idx ON registry_instruction (lot_id);
CREATE INDEX registry_instruction_plan_idx ON registry_instruction (plan_ref) WHERE plan_ref IS NOT NULL;
CREATE UNIQUE INDEX registry_instruction_external_ref_uq ON registry_instruction (registry_code, external_ref) WHERE external_ref IS NOT NULL;

-- Explicit overrides of the default transfer-route rules (same registry: free; both AIB hub members: hub transfer).
CREATE TABLE transfer_route (
    from_registry_code TEXT           NOT NULL,
    to_registry_code   TEXT           NOT NULL,
    feasible           BOOLEAN        NOT NULL DEFAULT TRUE,
    fee_per_mwh        NUMERIC(10, 4) NOT NULL DEFAULT 0,
    expected_days      INT            NOT NULL DEFAULT 1,
    restrictions       JSONB          NOT NULL DEFAULT '{}'::jsonb,
    PRIMARY KEY (from_registry_code, to_registry_code)
);
