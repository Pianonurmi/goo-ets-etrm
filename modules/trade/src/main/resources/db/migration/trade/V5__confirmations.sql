-- Trade confirmations: ours (issued per trade version, with a PDF) and the counterparty's (received and matched).
CREATE TABLE trade_confirmation (
    id              UUID        NOT NULL PRIMARY KEY,
    trade_id        UUID        NOT NULL REFERENCES trade (id),
    trade_version   INT         NOT NULL,
    direction       TEXT        NOT NULL CHECK (direction IN ('OURS', 'THEIRS')),
    source          TEXT        NOT NULL CHECK (source IN ('GENERATED', 'JSON', 'CSV', 'MANUAL')),
    reference       TEXT,
    terms           JSONB       NOT NULL,
    status          TEXT        NOT NULL CHECK (status IN ('ISSUED', 'SUPERSEDED', 'RECEIVED', 'MATCHED', 'MISMATCHED', 'OVERRIDDEN', 'DISPUTED')),
    differences     JSONB       NOT NULL DEFAULT '[]'::jsonb,
    matched_against UUID REFERENCES trade_confirmation (id),
    document_id     UUID,
    created_by      TEXT        NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    decided_by      TEXT,
    decided_at      TIMESTAMPTZ,
    note            TEXT,
    CONSTRAINT trade_confirmation_direction_status CHECK (
        (direction = 'OURS' AND status IN ('ISSUED', 'SUPERSEDED'))
        OR (direction = 'THEIRS' AND status NOT IN ('ISSUED'))
    ),
    -- An override is a second person's decision on a mismatch received by someone else.
    CONSTRAINT trade_confirmation_override_four_eyes CHECK (status <> 'OVERRIDDEN' OR (decided_by IS NOT NULL AND decided_by <> created_by))
);
CREATE UNIQUE INDEX trade_confirmation_current_ours ON trade_confirmation (trade_id) WHERE direction = 'OURS' AND status = 'ISSUED';
CREATE UNIQUE INDEX trade_confirmation_their_ref ON trade_confirmation (trade_id, reference) WHERE direction = 'THEIRS';
CREATE INDEX trade_confirmation_trade_idx ON trade_confirmation (trade_id, created_at);
