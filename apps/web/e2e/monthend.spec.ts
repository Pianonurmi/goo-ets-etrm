import { expect, test } from '@playwright/test';
import { actAs } from './fixtures';

function previousPeriod(): string {
  const today = new Date();
  const date = new Date(Date.UTC(today.getUTCFullYear(), today.getUTCMonth() - 1, 1));
  return date.toISOString().slice(0, 7);
}

test.describe('ledger, month end and reports', () => {
  test('month end shows the hard-close checklist and the period-end run; soft close and reopen', async ({ page }) => {
    await actAs(page, 'accountant');
    await page.goto('/month-end');
    await expect(page.getByTestId('period-picker')).toHaveValue(previousPeriod());
    await expect(page.getByTestId('hard-close-checklist')).toBeVisible();
    await expect(page.getByTestId('period-end-run')).toBeVisible();
    await expect(page.getByTestId('period-status')).toHaveText('OPEN');
    await page.getByTestId('soft-close').click();
    await expect(page.getByTestId('period-status')).toHaveText('SOFT CLOSED');
    await page.getByRole('button', { name: 'Reopen' }).click();
    await expect(page.getByTestId('period-status')).toHaveText('OPEN');
    await page.getByRole('tab', { name: 'GL interface' }).click();
    await expect(page.getByText('Reconciliation with the general ledger')).toBeVisible();
  });

  test('the journal opens an entry with its lines and verifies the hash chain', async ({ page }) => {
    await actAs(page, 'accountant');
    await page.goto(`/ledger?period=${previousPeriod()}`);
    await page.getByRole('gridcell', { name: 'POSTED' }).first().click();
    await expect(page.getByTestId('entry-lines')).toBeVisible();
    await page.keyboard.press('Escape');
    await page.getByRole('button', { name: 'Verify journal' }).click();
    await expect(page.getByTestId('verify-result')).toHaveText(/intact/);
  });

  test('a report runs with its parameters and is logged', async ({ page }) => {
    await actAs(page, 'accountant');
    await page.goto('/reports?report=GL_TRIAL_BALANCE');
    await page.getByTestId('param-period').fill(previousPeriod());
    await page.getByTestId('run-report').click();
    await expect(page.getByTestId('report-run')).toContainText('Run ');
    await expect(page.getByRole('columnheader', { name: 'account', exact: true })).toBeVisible();
    await page.getByRole('tab', { name: 'Export log' }).click();
    await expect(page.getByRole('gridcell', { name: 'GL_TRIAL_BALANCE' }).first()).toBeVisible();
  });
});
