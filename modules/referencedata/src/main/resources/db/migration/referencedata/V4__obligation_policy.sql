-- When a legal entity's trades start to oblige delivery: at confirmation (default) or already at approval, so that
-- allocation need not wait for the counterparty's confirmation to be matched.
ALTER TABLE legal_entity
    ADD COLUMN obligations_open_at TEXT NOT NULL DEFAULT 'CONFIRMED' CHECK (obligations_open_at IN ('APPROVED', 'CONFIRMED'));
