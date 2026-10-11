import { expect, test } from '@playwright/test';
import { actAs } from './fixtures';

test.describe('administration and audit', () => {
  test('middle office adds a label to the taxonomy; the front office sees no admin screens', async ({ page }) => {
    const code = `E2E_${Date.now().toString().slice(-8)}`;
    await actAs(page, 'middle');
    await page.goto('/admin/taxonomy?scheme=LABEL');
    await page.getByRole('button', { name: 'Add top-level node' }).click();
    await page.getByTestId('node-code').fill(code);
    await page.getByTestId('node-name').fill('End-to-end label');
    await page.getByTestId('node-save').click();
    await expect(page.getByTestId('taxonomy').getByText(code)).toBeVisible();

    await page.goto('/admin/reference?tab=counterparties');
    await page.getByRole('gridcell', { name: 'CP-NORDVIND' }).click();
    await page.getByRole('button', { name: 'History' }).click();
    await expect(page.getByTestId('history')).toContainText('Version 1');
  });

  test('a trader has no administration section', async ({ page }) => {
    await actAs(page, 'trader');
    await page.goto('/');
    await expect(page.getByRole('link', { name: 'Trades' })).toBeVisible();
    await expect(page.getByRole('link', { name: 'Reference data' })).toHaveCount(0);
    await expect(page.getByRole('link', { name: 'Audit trail' })).toHaveCount(0);
  });

  test('the auditor filters the audit trail and reads an event', async ({ page }) => {
    await actAs(page, 'auditor');
    await page.goto('/audit?eventType=TradeApproved');
    await page.getByRole('gridcell', { name: 'TradeApproved' }).first().click();
    await expect(page.getByTestId('audit-payload')).toContainText('"trade"');
  });
});
