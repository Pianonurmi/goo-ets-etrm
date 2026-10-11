import AxeBuilder from '@axe-core/playwright';
import { expect, test, type Page } from '@playwright/test';
import { actAs } from './fixtures';

/** Collects Content-Security-Policy violations the browser reports on the page. */
async function watchCsp(page: Page): Promise<() => Promise<string[]>> {
  await page.addInitScript(() => {
    const seen: string[] = [];
    (window as unknown as { cspViolations: string[] }).cspViolations = seen;
    document.addEventListener('securitypolicyviolation', (event) => {
      seen.push(`${event.violatedDirective} ${event.blockedURI}`);
    });
  });
  return () => page.evaluate(() => (window as unknown as { cspViolations: string[] }).cspViolations);
}

const SCREENS = [
  '/',
  '/inbox',
  '/trades',
  '/inventory',
  '/positions',
  '/settlement',
  '/settlement?tab=invoices',
  '/risk',
  '/ets',
  '/ledger',
  '/reports',
  '/admin/reference',
];

test.describe('hardening', () => {
  test('main screens have no serious accessibility violations and no CSP violations', async ({ page }) => {
    const violations = await watchCsp(page);
    await actAs(page, 'admin');
    const response = await page.goto('/');
    expect(response?.headers()['content-security-policy']).toContain("default-src 'self'");
    for (const path of SCREENS) {
      await page.goto(path);
      await expect(page.getByRole('main').getByRole('heading', { level: 2 }).first()).toBeVisible();
      await page.waitForLoadState('networkidle');
      const results = await new AxeBuilder({ page })
        .withTags(['wcag2a', 'wcag2aa'])
        // AG Grid manages its own ARIA grid roles; its internals are checked by the vendor.
        .exclude('.ag-root-wrapper')
        .analyze();
      const serious = results.violations
        .filter((violation) => violation.impact === 'serious' || violation.impact === 'critical')
        .map((violation) => `${path}: ${violation.id} (${violation.nodes.length}) ${violation.help}`);
      expect(serious, serious.join('\n')).toEqual([]);
    }
    expect(await violations()).toEqual([]);
  });

  test('the command palette opens with the keyboard and jumps to screens and trades', async ({ page }) => {
    await actAs(page, 'middle');
    await page.goto('/');
    await expect(page.getByTestId('dev-banner')).toBeVisible();
    await page.keyboard.press('ControlOrMeta+k');
    await page.getByTestId('palette-search').fill('ledg');
    await expect(page.getByRole('button', { name: /Ledger/ })).toHaveCount(0);
    await page.getByTestId('palette-search').fill('valu');
    await page.getByRole('button', { name: /Valuation/ }).click();
    await expect(page).toHaveURL(/\/valuation$/);

    await page.getByTestId('palette-button').click();
    await page.getByTestId('palette-search').fill('T-20');
    await page.getByRole('button', { name: /T-20/ }).first().click();
    await expect(page).toHaveURL(/\/trades\/[0-9a-f-]{36}$/);
  });
});
