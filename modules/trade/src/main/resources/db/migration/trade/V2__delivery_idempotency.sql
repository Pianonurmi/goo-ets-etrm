-- Deliveries get a stable identity and an optional idempotency key, so the same physical delivery reported by
-- several sources (registry instruction, statement, receipt match) is recorded once and downstream modules
-- (settlement, ledger) can key on it.
ALTER TABLE delivery_record ADD COLUMN id UUID;
UPDATE delivery_record SET id = gen_random_uuid() WHERE id IS NULL;
ALTER TABLE delivery_record ALTER COLUMN id SET NOT NULL;
ALTER TABLE delivery_record ADD CONSTRAINT delivery_record_id_uq UNIQUE (id);
ALTER TABLE delivery_record ADD COLUMN idempotency_key TEXT;
ALTER TABLE delivery_record ADD CONSTRAINT delivery_record_idempotency_key_uq UNIQUE (idempotency_key);
