-- Shadow prices of a plan (ADR 0035): the duals of the pricing LP, indicative. Null for plans proposed before them.
ALTER TABLE allocation_plan ADD COLUMN pricing JSONB;
