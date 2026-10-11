import { expect, test } from '@playwright/test';
import { actAs } from './fixtures';

test.describe('allocation and positions', () => {
  test('what-if first, then propose a plan that waits for a second person', async ({ page }) => {
    await actAs(page, 'backoffice');
    await page.goto('/allocation');
    await page.getByTestId('what-if').click();
    await expect(page.getByTestId('what-if-result')).toBeVisible();
    await expect(page.getByTestId('what-if-allocated')).toContainText('MWh allocated');
    await expect(page.getByTestId('what-if-solver')).toHaveText('Solved by GREEDY');

    await page.getByTestId('solver').getByText('Exact').click();
    await page.getByTestId('what-if').click();
    await expect(page.getByTestId('what-if-solver')).toHaveText('Solved by CP_SAT');
    await expect(page.getByTestId('what-if-fallback')).toHaveCount(0);

    await page.getByTestId('propose').click();
    await expect(page).toHaveURL(/\/allocation\/plans\/[0-9a-f-]{36}$/);
    await expect(page.getByTestId('plan-status')).toHaveText(/^proposed$/i);
    await expect(page.getByTestId('plan-approval-task')).toBeVisible();
  });

  test('shows the GoO position with its expiry ladder', async ({ page }) => {
    await actAs(page, 'trader');
    await page.goto('/positions');
    await expect(page.getByText('Expiry ladder (MWh by month)')).toBeVisible();
    await expect(page.getByRole('gridcell', { name: 'FI_HYDRO' }).first()).toBeVisible();
    await page.getByRole('tab', { name: 'EU ETS' }).click();
    await expect(page.getByText('Surrender deadline')).toBeVisible();
  });
});
