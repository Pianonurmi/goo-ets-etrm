import { expect, type Page } from '@playwright/test';

/** Acts as a dev persona (ADR 0026) before the application loads. */
export async function actAs(page: Page, persona: string): Promise<void> {
  await page.addInitScript((id) => window.localStorage.setItem('etrm.persona', id), persona);
}

/** Switches persona through the header menu, as a user would. */
export async function switchPersona(page: Page, persona: string): Promise<void> {
  await page.getByTestId('persona-menu').click();
  await page.getByTestId(`persona-${persona}`).click();
}

export async function expectShell(page: Page): Promise<void> {
  await expect(page.getByTestId('dev-banner')).toBeVisible();
}
