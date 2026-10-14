-- A measurement that left something out says so (increment 14, M3): a currency without a rate, an option without a value.
-- A limit that could not be measured at all is NOT_MEASURED, which counts as a breach.
ALTER TABLE limit_utilisation DROP CONSTRAINT limit_utilisation_status_check;
ALTER TABLE limit_utilisation ADD CONSTRAINT limit_utilisation_status_check CHECK (status IN ('OK', 'WARNING', 'BREACH', 'NOT_MEASURED'));
ALTER TABLE limit_utilisation
    ADD COLUMN incomplete           BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN left_out_currencies  TEXT[]  NOT NULL DEFAULT '{}',
    ADD COLUMN incomplete_reasons   TEXT[]  NOT NULL DEFAULT '{}';

ALTER TABLE counterparty_exposure
    ADD COLUMN incomplete           BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN left_out_currencies  TEXT[]  NOT NULL DEFAULT '{}',
    ADD COLUMN incomplete_reasons   TEXT[]  NOT NULL DEFAULT '{}';
