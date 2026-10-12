import { expect, test } from '@playwright/test';
import { actAs } from './fixtures';

test.describe('positions', () => {
  test('a trader sees what can still be sold from the certificates held', async ({ page }) => {
    await actAs(page, 'trader');
    await page.goto('/positions');
    await page.getByTestId('goo-view').getByText('Available to sell').click();

    // The first call for an entity prepares the figures, so allow it a moment.
    const table = page.getByTestId('headroom-table');
    await expect(table).toBeVisible({ timeout: 30_000 });
    await expect(table.getByRole('gridcell').first()).toBeVisible();
    await expect(table.locator('.ag-row').first()).toBeVisible();
    await expect(page.getByTestId('headroom-note')).toContainText('do not add up');
  });
});
