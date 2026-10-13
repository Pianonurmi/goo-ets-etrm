-- Cash tied up at clearing houses (ADR 0046): initial margin held plus open margin calls.
ALTER TABLE risk_limit DROP CONSTRAINT risk_limit_limit_type_check;
ALTER TABLE risk_limit ADD CONSTRAINT risk_limit_limit_type_check CHECK (limit_type IN (
    'CREDIT_EXPOSURE', 'POSITION_NET', 'TENOR_MAX_MONTHS', 'STOP_LOSS_MTD', 'COMPLIANCE_SHORT',
    'GOO_CONCENTRATION', 'GOO_EXPIRY', 'GOO_BASIS', 'MARGIN_LIQUIDITY'
));
