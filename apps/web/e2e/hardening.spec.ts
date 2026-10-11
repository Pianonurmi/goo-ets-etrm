import AxeBuilder from '@axe-core/playwright';
import { expect, test, type Page } from '@playwright/test';
import { asPersona } from './api';
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
  '/confirmations',
  '/inventory',
  '/positions',
  '/registry',
  '/reconciliation',
  '/reconciliation?tab=statements',
  '/registry?tab=routes',
  '/registry?tab=formats',
  '/settlement',
  '/settlement?tab=invoices',
  '/settlement?tab=payments',
  '/settlement?tab=netting',
  '/settlement?tab=vat-rules',
  '/settlement?tab=reports',
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
    // Middle office has no ledger screen; the manual's ledger page may still be found.
    await expect(page.getByTestId('palette-screen').filter({ hasText: /Ledger/ })).toHaveCount(0);
    await page.getByTestId('palette-search').fill('valu');
    await page
      .getByTestId('palette-screen')
      .filter({ hasText: /Valuation/ })
      .first()
      .click();
    await expect(page).toHaveURL(/\/valuation$/);

    await page.getByTestId('palette-button').click();
    await page.getByTestId('palette-search').fill('T-20');
    await page.getByRole('button', { name: /T-20/ }).first().click();
    await expect(page).toHaveURL(/\/trades\/[0-9a-f-]{36}$/);
  });
});

test.describe('hardening: operations detail pages', () => {
  test('invoice, netting, instruction, break and confirmation pages have no serious accessibility violations', async ({
    page,
    request,
  }) => {
    const admin = asPersona(request, 'admin', 'ADMIN');
    const entity = (await admin.get<{ id: string; code: string }[]>('/api/v1/reference/legal-entities')).find(
      (e) => e.code === 'DEMO-FI',
    );
    const id = entity?.id ?? '';
    const first = async (path: string, pick: (body: unknown) => string | undefined) => pick(await admin.get(path));
    const pages = [
      `/settlement/invoices/${await first(`/api/v1/settlement/invoices?legalEntityId=${id}`, (b) => (b as { id: string }[])[0]?.id)}`,
      `/settlement/netting/${await first(`/api/v1/settlement/netting?legalEntityId=${id}`, (b) => (b as { id: string }[])[0]?.id)}`,
      `/registry/instructions/${await first(`/api/v1/registry/instructions/search?holderEntityId=${id}`, (b) => (b as { items: { id: string }[] }).items[0]?.id)}`,
      `/reconciliation/breaks/${await first('/api/v1/inventory/breaks?status=OPEN&status=INVESTIGATING&status=PENDING_ACCEPTANCE&status=ACCEPTED&status=RESOLVED&status=AUTO_CLEARED', (b) => (b as { id: string }[])[0]?.id)}`,
      `/confirmations/${await first(`/api/v1/confirmations?legalEntityId=${id}&status=MISMATCHED&status=OVERRIDDEN&status=MATCHED`, (b) => (b as { id: string }[])[0]?.id)}`,
    ];
    const violations = await watchCsp(page);
    await actAs(page, 'admin');
    for (const path of pages) {
      expect(path, 'demo data for every page').not.toContain('undefined');
      await page.goto(path);
      await expect(page.getByRole('heading', { level: 3 }).first()).toBeVisible();
      await page.waitForLoadState('networkidle');
      const results = await new AxeBuilder({ page })
        .withTags(['wcag2a', 'wcag2aa'])
        .exclude('.ag-root-wrapper')
        .analyze();
      const serious = results.violations
        .filter((violation) => violation.impact === 'serious' || violation.impact === 'critical')
        .map((violation) => `${path}: ${violation.id} (${violation.nodes.length}) ${violation.help}`);
      expect(serious, serious.join('\n')).toEqual([]);
    }
    expect(await violations()).toEqual([]);
  });
});
