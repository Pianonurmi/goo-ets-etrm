-- How a leg's bucket was found: EXACT (the bucket's own criteria) or IMPLIED (a bucket proved to contain the criteria).
ALTER TABLE trade_leg ADD COLUMN bucket_match TEXT CHECK (bucket_match IN ('EXACT', 'IMPLIED'));
UPDATE trade_leg SET bucket_match = 'EXACT' WHERE bucket_code IS NOT NULL;
