-- A plan in EUR (ADR 0035): delivered value, expiry haircut, transfer fees, give-away, per obligation.
-- Null for plans proposed before value-based allocation.
ALTER TABLE allocation_plan ADD COLUMN value_summary JSONB;
