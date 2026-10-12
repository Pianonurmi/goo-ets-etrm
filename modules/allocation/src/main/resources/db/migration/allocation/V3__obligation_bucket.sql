-- Obligations carry the product bucket of their criterion and the vintage year they are marked at.
-- Existing rows stay null; a re-sync from the trade fills them.
ALTER TABLE obligation
    ADD COLUMN bucket_code TEXT,
    ADD COLUMN bucket_match TEXT CHECK (bucket_match IS NULL OR bucket_match IN ('EXACT', 'IMPLIED')),
    ADD COLUMN vintage_year INTEGER;
