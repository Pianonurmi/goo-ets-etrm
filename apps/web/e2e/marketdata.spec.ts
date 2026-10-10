import { expect, test } from '@playwright/test';
import { actAs, switchPersona } from './fixtures';

function lastWeekday(): string {
  const date = new Date();
  do date.setUTCDate(date.getUTCDate() - 1);
  while (date.getUTCDay() === 0 || date.getUTCDay() === 6);
  return date.toISOString().slice(0, 10);
}

test.describe('market data and valuation', () => {
  test('a curve snapshot is built and submitted by one analyst and signed off by another', async ({ page }) => {
    await actAs(page, 'risk');
    await page.goto(`/market-data/curves?curve=EUA_FWD&asOf=${lastWeekday()}`);
    await page.getByTestId('build-snapshot').click();
    await expect(page).toHaveURL(/\/market-data\/snapshots\/[0-9a-f-]{36}$/);
    await expect(page.getByTestId('snapshot-status')).toHaveText('DRAFT');
    await page.getByTestId('submit-snapshot').click();
    await expect(page.getByTestId('snapshot-status')).toHaveText('PENDING SIGN OFF');

    await page.getByTestId('sign-off-task').click();
    await expect(page.getByTestId('own-request')).toBeVisible();
    await switchPersona(page, 'risk2');
    await page.getByTestId('decide-APPROVE').click();
    await expect(page.getByText('Decided by risk2', { exact: false })).toBeVisible();
  });

  test('lists quotes and official valuation runs', async ({ page }) => {
    await actAs(page, 'risk');
    await page.goto('/market-data?tab=quotes&instrument=EUA_SPOT');
    await expect(page.getByRole('gridcell', { name: 'CLOSE' }).first()).toBeVisible();
    await page.goto('/valuation');
    await page.getByRole('gridcell', { name: 'OFFICIAL' }).first().click();
    await expect(page.getByTestId('run-status')).toHaveText('OFFICIAL');
    await page.goto('/valuation?tab=explain');
    await expect(page.getByText('P&L explain').last()).toBeVisible();
    await expect(page.locator('.recharts-bar-rectangle').first()).toBeVisible();
  });
});
