import { expect, test } from '@playwright/test';
import { pendingHedge } from './api';
import { actAs, switchPersona } from './fixtures';

test.describe('valuation depth: discounting and cash flow hedges', () => {
  test('a discount curve shows its day count, and valuation results their discount factor', async ({ page }) => {
    await actAs(page, 'risk');
    await page.goto('/market-data/curves?curve=EUR_DISC');
    await expect(page.getByTestId('curve-day-count-info')).toContainText('ACT/360');
    await page.goto('/market-data/curves?curve=EUA_FWD');
    await expect(page.getByTestId('curve-interpolation')).toHaveValue(/last carry rate/);
    await page.goto('/valuation');
    await page
      .getByRole('gridcell', { name: /official/i })
      .first()
      .click();
    await expect(page.getByRole('columnheader', { name: 'Discount factor' })).toBeVisible();
  });

  test('a hedge proposed by one accountant is approved by another and shows its documentation', async ({
    page,
    request,
  }) => {
    const { hedgeId } = await pendingHedge(request);
    await actAs(page, 'accountant');
    await page.goto('/ledger/hedges');
    await expect(page.getByTestId('hedges-grid')).toBeVisible();
    const kpi = page.getByTestId('hedge-kpi-active');
    await expect(kpi).toContainText(/\d/);
    const activeBefore = Number((await kpi.innerText()).match(/\d+/)?.[0]);

    await page.goto(`/ledger/hedges/${hedgeId}`);
    await expect(page.getByTestId('hedge-checks')).toBeVisible();
    await page.getByTestId('hedge-task').click();
    await expect(page.getByTestId('own-request')).toBeVisible();
    await switchPersona(page, 'controller');
    await page.getByTestId('decide-APPROVE').click();
    await expect(page.getByText('Decided by controller', { exact: false })).toBeVisible();

    await page.goto('/ledger/hedges?status=ACTIVE');
    await expect(page.getByTestId('hedge-kpi-active')).toContainText(new RegExp(`(^|\\D)${activeBefore + 1}(\\D|$)`));
    await page.goto(`/ledger/hedges/${hedgeId}`);
    await expect(page.getByTestId('hedge-document')).toBeVisible();
    await expect(page.getByTestId('hedge-designation-price')).toBeVisible();
    await page.getByTestId('hedge-tab-effectiveness').click();
    await expect(page.getByTestId('hedge-measurements-empty')).toBeVisible();
  });
});
