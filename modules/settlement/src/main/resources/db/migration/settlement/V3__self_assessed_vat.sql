-- On a reverse-charged purchase the buyer accounts for the VAT itself: output and input VAT at the buyer's standard
-- rate. Standard rates per country are data (tax sign-off required, like the VAT rules).
CREATE TABLE vat_standard_rate (
    country    CHAR(2)       NOT NULL,
    rate       NUMERIC(5, 2) NOT NULL CHECK (rate > 0 AND rate < 100),
    valid_from DATE          NOT NULL,
    valid_to   DATE,
    PRIMARY KEY (country, valid_from),
    CONSTRAINT vat_standard_rate_period CHECK (valid_to IS NULL OR valid_to > valid_from)
);
INSERT INTO vat_standard_rate (country, rate, valid_from, valid_to) VALUES
    ('FI', 24, '2013-01-01', '2024-09-01'),
    ('FI', 25.5, '2024-09-01', NULL),
    ('SE', 25, '1990-07-01', NULL),
    ('DE', 19, '2007-01-01', NULL),
    ('DK', 25, '1992-01-01', NULL),
    ('NL', 21, '2012-10-01', NULL),
    ('FR', 20, '2014-01-01', NULL),
    ('AT', 20, '1984-01-01', NULL),
    ('EE', 24, '2025-07-01', NULL),
    ('PL', 23, '2011-01-01', NULL);

ALTER TABLE invoice_line ADD COLUMN self_assessed_vat NUMERIC(18, 2) NOT NULL DEFAULT 0;
