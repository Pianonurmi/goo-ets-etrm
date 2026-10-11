-- Databases for jOOQ code generation and integration tests next to the application database.
CREATE DATABASE etrm_codegen OWNER etrm;
CREATE DATABASE etrm_test OWNER etrm;
-- Playwright end-to-end runs (apps/web, npm run e2e).
CREATE DATABASE etrm_e2e OWNER etrm;
-- Playwright against the public showcase (showcase and demo profiles).
CREATE DATABASE etrm_showcase_e2e OWNER etrm;
