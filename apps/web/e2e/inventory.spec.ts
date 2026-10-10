import { expect, test } from '@playwright/test';
import { actAs } from './fixtures';

test.describe('inventory explorer', () => {
  test('finds Nordic hydro certificates by criteria and opens one', async ({ page }) => {
    await actAs(page, 'backoffice');
    await page.goto('/inventory?tab=search');
    await expect(page.getByTestId('criteria-summary')).not.toHaveText('…');
    await page.getByTestId('run-search').click();
    await expect(page.getByTestId('lot-totals')).toContainText('lots');
    await page.getByRole('gridcell', { name: 'HYDRO_RESERVOIR' }).first().click();
    await expect(page).toHaveURL(/\/inventory\/lots\/[0-9a-f-]{36}$/);
    await expect(page.getByTestId('lot-title')).toContainText('MWh');
    await expect(page.getByTestId('withdraw')).toBeVisible();
  });

  test('lists live certificates and hides operations from a trader', async ({ page }) => {
    await actAs(page, 'trader');
    await page.goto('/inventory');
    await expect(page.getByTestId('lot-totals')).toContainText('lots');
    await page.getByRole('gridcell', { name: 'HYDRO_RESERVOIR' }).first().click();
    await expect(page.getByTestId('lot-title')).toBeVisible();
    await expect(page.getByTestId('withdraw')).toBeHidden();
  });
});
