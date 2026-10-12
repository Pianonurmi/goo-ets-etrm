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

  test('a trader sees the portfolio as a tree of products', async ({ page }) => {
    await actAs(page, 'trader');
    await page.goto('/positions');
    await page.getByTestId('goo-view').getByText('Products').click();

    // The first call for an entity prepares the figures, so allow it a moment.
    const tree = page.getByTestId('goo-hierarchy');
    await expect(tree).toBeVisible({ timeout: 30_000 });
    await expect(tree.locator('.ag-row').filter({ hasText: 'ANY_GOO' }).first()).toBeVisible();

    // Nordic hydro is three levels down and sits under two larger products: open the way to it.
    await tree.getByTestId('portfolio-toggle-NORDIC_RENEWABLE').first().click();
    await expect(tree.locator('.ag-row').filter({ hasText: 'NORDIC_HYDRO' }).first()).toBeVisible();
  });
});
