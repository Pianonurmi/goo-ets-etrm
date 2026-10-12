-- GoO limits on the book netted through the eligibility flow (ADR 0040).
ALTER TABLE risk_limit DROP CONSTRAINT risk_limit_limit_type_check;
ALTER TABLE risk_limit ADD CONSTRAINT risk_limit_limit_type_check CHECK (limit_type IN (
    'CREDIT_EXPOSURE', 'POSITION_NET', 'TENOR_MAX_MONTHS', 'STOP_LOSS_MTD', 'COMPLIANCE_SHORT',
    'GOO_CONCENTRATION', 'GOO_EXPIRY', 'GOO_BASIS'
));
