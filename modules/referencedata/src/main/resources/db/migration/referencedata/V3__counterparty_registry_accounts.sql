-- Counterparty registry accounts are structured, four-eyes controlled records. For the Union Registry an account on
-- the trusted account list only becomes usable after the registry's activation delay (four working days); before
-- that, and for accounts not on the list, transfers to it are delayed by the registry.
ALTER TABLE registry_account ADD COLUMN trusted_since DATE;
UPDATE registry_account SET trusted_since = created_at::date WHERE trusted AND trusted_since IS NULL;
