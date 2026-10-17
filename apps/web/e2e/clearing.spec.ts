import { expect, test } from '@playwright/test';
import { clearingBreak, demoClearingAccount } from './api';
import { actAs, switchPersona } from './fixtures';

test.describe('clearing: futures margining (ADR 0046)', () => {
  test('daily margin and the member statements are read from the clearing account', async ({ page, request }) => {
    const accountId = await demoClearingAccount(request);
    await actAs(page, 'backoffice');
    await page.goto(`/clearing?account=${accountId}`);
    await expect(page.getByTestId('clearing-page')).toBeVisible();
    await expect(page.getByTestId('clearing-accounts-grid')).toContainText('ECC-DEMO-FI');

    await page.getByTestId('clearing-tab-margin').click();
    await expect(page.getByTestId('clearing-runs-grid')).toBeVisible();
    await page.getByTestId('clearing-runs-grid').getByRole('gridcell').first().click();
    await expect(page.getByTestId('clearing-run-lines')).toContainText('EUAZ');
    await page.keyboard.press('Escape');

    await page.getByTestId('clearing-tab-statements').click();
    await page.getByTestId('clearing-statements-grid').getByRole('gridcell').first().click();
    await expect(page.getByTestId('clearing-statement-lines')).toContainText('EUAZ');
  });

  test('a statement break is accepted in the inbox by someone other than who imported the statement', async ({
    page,
    request,
  }) => {
    const { accountId, breakId } = await clearingBreak(request);
    await actAs(page, 'backoffice');
    await page.goto(`/clearing?account=${accountId}`);
    await expect(page.getByTestId('clearing-kpi-calls')).toContainText('1');
    await expect(page.getByTestId('clearing-kpi-breaks')).toContainText('1');
    await page.getByTestId('clearing-tab-margin-calls').click();
    await expect(page.getByTestId('clearing-calls-grid')).toContainText('1,250.00');

    await page.goto(`/clearing/breaks/${breakId}`);
    await expect(page.getByTestId('clearing-difference-VARIATION_MARGIN').first()).toBeVisible();
    await expect(page.getByTestId('clearing-break-status')).toContainText('Open');

    await page.getByTestId('clearing-break-task').click();
    await expect(page.getByTestId('own-request')).toBeVisible();
    await switchPersona(page, 'middle');
    await page.getByLabel('Reason').fill('Member applied a give-up fee to the margin; confirmed by phone');
    await page.getByTestId('decide-APPROVE').click();
    await expect(page.getByText('Decided by middle', { exact: false })).toBeVisible();

    await page.goto(`/clearing?tab=breaks&account=${accountId}`);
    await expect(page.getByTestId('clearing-kpi-breaks')).toContainText('0');
  });

  test('a statement file is uploaded and its result shown', async ({ page, request }) => {
    const accountId = await demoClearingAccount(request);
    await actAs(page, 'backoffice');
    await page.goto(`/clearing?tab=statements&account=${accountId}`);
    await page.getByTestId('clearing-import-statement').click();
    await expect(page.getByTestId('clearing-import-csv')).toBeVisible();
    await page
      .getByTestId('clearing-import-csv')
      .fill(
        'Date,Account,Record,Contract,Position,SettlementPrice,Amount,Currency\n2000-01-03,ECC-DEMO-FI,IM,,,,0.00,EUR',
      );
    await page.getByTestId('clearing-import-submit').click();
    await expect(page.getByTestId('clearing-import-result')).toBeVisible();
  });
});
