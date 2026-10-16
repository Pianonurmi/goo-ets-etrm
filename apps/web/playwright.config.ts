import { defineConfig, devices } from '@playwright/test';
import { SHOWCASE_PASSWORD } from './e2e/fixtures';

/**
 * End-to-end journeys against the real application jar. The `chromium` project runs the dev and demo profiles (persona
 * headers and demo data); the `showcase` project runs the public demo (showcase and demo profiles: sign-in, then
 * personas) on its own database. Set E2E_BASE_URL / E2E_SHOWCASE_URL to test running servers instead. Locally,
 * PW_CHROMIUM_PATH can point at an installed Chromium.
 */
const port = Number(process.env.E2E_PORT ?? 18080);
const showcasePort = Number(process.env.E2E_SHOWCASE_PORT ?? 18081);
// 127.0.0.1, not localhost: the dev profile listens on IPv4 loopback only.
const baseURL = process.env.E2E_BASE_URL ?? `http://127.0.0.1:${port}`;
const showcaseURL = process.env.E2E_SHOWCASE_URL ?? `http://127.0.0.1:${showcasePort}`;
const executablePath = process.env.PW_CHROMIUM_PATH;
const java = process.env.ETRM_JAVA ?? 'java';
const showcaseDatabaseUrl = process.env.E2E_SHOWCASE_DB_URL ?? 'jdbc:postgresql://localhost:5432/etrm_showcase_e2e';
const showcaseDatabase = /\/([^/?]+)(\?.*)?$/.exec(showcaseDatabaseUrl)?.[1] ?? 'etrm_showcase_e2e';
const database = {
  ETRM_DB_USER: process.env.ETRM_DB_USER ?? 'etrm',
  ETRM_DB_PASSWORD: process.env.ETRM_DB_PASSWORD ?? 'etrm',
};

export default defineConfig({
  testDir: 'e2e',
  timeout: 60_000,
  expect: { timeout: 10_000 },
  fullyParallel: false,
  workers: 1,
  // No retries, in CI either: a flaky test is fixed or gets an issue, never passed on a second try.
  retries: 0,
  reporter: [['list'], ['html', { open: 'never', outputFolder: 'build/playwright-report' }]],
  outputDir: 'build/test-results',
  use: {
    baseURL,
    // Formats are asserted as en-GB whatever the machine's language (W7: the UI follows the browser's locale).
    locale: 'en-GB',
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
  },
  projects: [
    {
      name: 'chromium',
      // The documentation screenshots have their own config (playwright.docs.config.ts).
      testIgnore: [/showcase\.spec\.ts/, /e2e\/docs\//],
      use: { ...devices['Desktop Chrome'], launchOptions: executablePath ? { executablePath } : {} },
    },
    {
      name: 'showcase',
      testMatch: /showcase\.spec\.ts/,
      use: {
        ...devices['Desktop Chrome'],
        baseURL: showcaseURL,
        launchOptions: executablePath ? { executablePath } : {},
      },
    },
  ],
  webServer: [
    ...(process.env.E2E_BASE_URL
      ? []
      : [
          {
            command: `${java} -jar ../api/build/libs/api-showcase.jar --spring.profiles.active=${process.env.E2E_PROFILES ?? 'dev,demo'} --server.port=${port}`,
            url: `${baseURL}/actuator/health`,
            reuseExistingServer: !process.env.CI,
            timeout: 180_000,
            stdout: 'ignore' as const,
            stderr: 'pipe' as const,
            env: { ...database, ETRM_DB_URL: process.env.E2E_DB_URL ?? 'jdbc:postgresql://localhost:5432/etrm_e2e' },
          },
        ]),
    ...(process.env.E2E_SHOWCASE_URL
      ? []
      : [
          {
            // Bound to loopback: the showcase needs no proxy in a test, and the seeder calls it on 127.0.0.1.
            command: `${java} -jar ../api/build/libs/api-showcase.jar --spring.profiles.active=showcase,demo --server.port=${showcasePort} --server.address=127.0.0.1`,
            url: `${showcaseURL}/actuator/health`,
            reuseExistingServer: !process.env.CI,
            timeout: 180_000,
            stdout: 'ignore' as const,
            stderr: 'pipe' as const,
            env: {
              ...database,
              ETRM_DB_URL: showcaseDatabaseUrl,
              // The showcase wipes its database on every start (ADR 0033); it must be named to be wiped.
              ETRM_SHOWCASE_DATABASE: showcaseDatabase,
              ETRM_DEMO_PASSWORD: SHOWCASE_PASSWORD,
            },
          },
        ]),
  ],
});
