-- The legal entity's obligations-open policy, fixed on the trade when it is captured.
ALTER TABLE trade
    ADD COLUMN obligations_open_at TEXT NOT NULL DEFAULT 'CONFIRMED' CHECK (obligations_open_at IN ('APPROVED', 'CONFIRMED'));
