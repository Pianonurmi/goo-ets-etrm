import { defineConfig, devices } from '@playwright/test';
import { SHOWCASE_PASSWORD } from './e2e/fixtures';

/**
 * The screenshots of the manual and the README (docs/images), taken from the public demo as a visitor sees it: the
 * jar in the showcase and demo profiles on its own database, signed in, 1440 × 900. Run with `npm run docs:screenshots`
 * (E2E_DOCS_DB_URL chooses the database; E2E_DOCS_URL reuses a running showcase).
 */
const port = Number(process.env.E2E_DOCS_PORT ?? 18082);
const baseURL = process.env.E2E_DOCS_URL ?? `http://127.0.0.1:${port}`;
const executablePath = process.env.PW_CHROMIUM_PATH;
const java = process.env.ETRM_JAVA ?? 'java';
const databaseUrl = process.env.E2E_DOCS_DB_URL ?? 'jdbc:postgresql://localhost:5432/etrm_docs';
const databaseName = /\/([^/?]+)(\?.*)?$/.exec(databaseUrl)?.[1] ?? 'etrm_docs';

export default defineConfig({
  testDir: 'e2e/docs',
  timeout: 300_000,
  expect: { timeout: 15_000 },
  workers: 1,
  reporter: [['list']],
  outputDir: 'build/docs-results',
  use: {
    ...devices['Desktop Chrome'],
    baseURL,
    locale: 'en-GB',
    viewport: { width: 1440, height: 900 },
    deviceScaleFactor: 1,
    launchOptions: executablePath ? { executablePath } : {},
  },
  webServer: process.env.E2E_DOCS_URL
    ? undefined
    : {
        command: `${java} -jar ../api/build/libs/api.jar --spring.profiles.active=showcase,demo --server.port=${port} --server.address=127.0.0.1`,
        url: `${baseURL}/actuator/health/readiness`,
        reuseExistingServer: !process.env.CI,
        timeout: 300_000,
        stdout: 'ignore',
        stderr: 'pipe',
        env: {
          ETRM_DB_USER: process.env.ETRM_DB_USER ?? 'etrm',
          ETRM_DB_PASSWORD: process.env.ETRM_DB_PASSWORD ?? 'etrm',
          ETRM_DB_URL: databaseUrl,
          // The showcase wipes this database on every start (ADR 0033), so every run starts from the demo data.
          ETRM_SHOWCASE_DATABASE: databaseName,
          ETRM_DEMO_PASSWORD: SHOWCASE_PASSWORD,
        },
      },
});
