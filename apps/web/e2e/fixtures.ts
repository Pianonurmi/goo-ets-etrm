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
