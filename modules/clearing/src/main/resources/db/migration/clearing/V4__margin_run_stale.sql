-- A run whose margin rests on an earlier day that changed, and that could not be computed again (no settlement
-- price yet): it stays current, marked stale with the reason, until the day is run again.
ALTER TABLE margin_run ADD COLUMN stale_reason TEXT;
