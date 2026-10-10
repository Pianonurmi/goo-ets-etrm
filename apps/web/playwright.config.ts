import { defineConfig, devices } from '@playwright/test';

/**
 * End-to-end journeys against the real application jar (dev and demo profiles: persona headers and demo data). Set E2E_BASE_URL to test a
 * running server instead. Locally, PW_CHROMIUM_PATH can point at an installed Chromium.
 */
const port = Number(process.env.E2E_PORT ?? 18080);
const baseURL = process.env.E2E_BASE_URL ?? `http://localhost:${port}`;
const executablePath = process.env.PW_CHROMIUM_PATH;

export default defineConfig({
  testDir: 'e2e',
  timeout: 60_000,
  expect: { timeout: 10_000 },
  fullyParallel: false,
  workers: 1,
  retries: process.env.CI ? 1 : 0,
  reporter: [['list'], ['html', { open: 'never', outputFolder: 'build/playwright-report' }]],
  outputDir: 'build/test-results',
  use: {
    baseURL,
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
  },
  projects: [
    {
      name: 'chromium',
      use: { ...devices['Desktop Chrome'], launchOptions: executablePath ? { executablePath } : {} },
    },
  ],
  webServer: process.env.E2E_BASE_URL
    ? undefined
    : {
        command: `java -jar ../api/build/libs/api.jar --spring.profiles.active=${process.env.E2E_PROFILES ?? 'dev,demo'} --server.port=${port}`,
        url: `${baseURL}/actuator/health`,
        reuseExistingServer: !process.env.CI,
        timeout: 180_000,
        stdout: 'ignore',
        stderr: 'pipe',
        env: {
          ETRM_DB_URL: process.env.E2E_DB_URL ?? 'jdbc:postgresql://localhost:5432/etrm_e2e',
          ETRM_DB_USER: process.env.ETRM_DB_USER ?? 'etrm',
          ETRM_DB_PASSWORD: process.env.ETRM_DB_PASSWORD ?? 'etrm',
        },
      },
});
