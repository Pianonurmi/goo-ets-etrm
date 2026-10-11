import { expect, type Page } from '@playwright/test';

/**
 * Acts as a dev persona (ADR 0026) from the first page load on. The init script runs on every navigation, so it only
 * sets the persona once per tab; later switches through the header survive reloads.
 */
export async function actAs(page: Page, persona: string): Promise<void> {
  await page.addInitScript((id) => {
    if (window.sessionStorage.getItem('etrm.e2e.persona-set')) return;
    window.localStorage.setItem('etrm.persona', id);
    window.sessionStorage.setItem('etrm.e2e.persona-set', 'yes');
  }, persona);
}

/** Switches persona through the header menu, as a user would. */
export async function switchPersona(page: Page, persona: string): Promise<void> {
  await page.getByTestId('persona-menu').click();
  await page.getByTestId(`persona-${persona}`).click();
}

export async function expectShell(page: Page): Promise<void> {
  await expect(page.getByTestId('dev-banner')).toBeVisible();
}

/** The demo sign-in of the `showcase` project (user `demo`); the config passes it to the jar. */
export const SHOWCASE_PASSWORD = process.env.ETRM_DEMO_PASSWORD ?? 'e2e-showcase-password';

/**
 * Signs in to the showcase through the login page, as a visitor would. The first-visit welcome is marked as seen
 * unless [welcome] is set, so it does not cover the screens a test works on.
 */
export async function signIn(
  page: Page,
  password: string = SHOWCASE_PASSWORD,
  username = 'demo',
  welcome = false,
): Promise<void> {
  if (!welcome) await page.addInitScript(() => window.localStorage.setItem('etrm.welcomed', 'true'));
  await page.goto('/');
  await page.getByTestId('login-username').fill(username);
  await page.getByTestId('login-password').fill(password);
  await page.getByTestId('login-submit').click();
}
