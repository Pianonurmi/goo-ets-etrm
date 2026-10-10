-- EMIR counterparty classification (decides who reports and whether valuations are reported) and counterparty groups
-- (credit limits and exposure roll up to the parent).
ALTER TABLE legal_entity
    ADD COLUMN emir_classification TEXT CHECK (emir_classification IN ('FC', 'NFC_PLUS', 'NFC_MINUS', 'THIRD_COUNTRY_ENTITY', 'OTHER'));

ALTER TABLE counterparty
    ADD COLUMN emir_classification TEXT CHECK (emir_classification IN ('FC', 'NFC_PLUS', 'NFC_MINUS', 'THIRD_COUNTRY_ENTITY', 'OTHER')),
    ADD COLUMN parent_counterparty_id UUID REFERENCES counterparty (id),
    ADD CONSTRAINT counterparty_not_own_parent_ck CHECK (parent_counterparty_id IS NULL OR parent_counterparty_id <> id);
