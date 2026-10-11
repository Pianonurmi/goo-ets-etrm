import { expect, test } from '@playwright/test';
import { asPersona, captureDemoTrade } from './api';
import { actAs, switchPersona } from './fixtures';

test.describe('counterparty confirmations', () => {
  test('the demo mismatch is overridden by a second middle office user and confirms the trade', async ({ page }) => {
    await actAs(page, 'middle');
    await page.goto('/confirmations');
    await page.getByRole('gridcell', { name: 'MISMATCHED' }).first().click();
    await expect(page).toHaveURL(/\/confirmations\/[0-9a-f-]{36}$/);
    await expect(page.getByTestId('confirmation-status')).toHaveText(/^mismatched$/i);
    await expect(page.getByTestId('term-price')).toContainText('MISMATCH');
    await expect(page.getByTestId('term-quantity')).not.toContainText('MISMATCH');
    const confirmationUrl = page.url();

    await page.getByTestId('confirmation-task').click();
    await expect(page.getByTestId('own-request')).toBeVisible();
    await switchPersona(page, 'middle2');
    await page.getByTestId('decide-APPROVE').click();
    await expect(page.getByText('Decided by middle2', { exact: false })).toBeVisible();

    await page.goto(confirmationUrl);
    await expect(page.getByTestId('confirmation-status')).toHaveText(/^overridden$/i);
    await page.getByTestId('confirmation-trade').click();
    await expect(page).toHaveURL(/\/trades\/[0-9a-f-]{36}$/);
    await expect(page.getByTestId('trade-status')).toHaveText(/^confirmed$/i);
  });

  test('their confirmation entered by hand matches ours', async ({ page, request }) => {
    const { tradeId, taskId } = await captureDemoTrade(request, 75);
    await asPersona(request, 'middle2', 'MIDDLE_OFFICE').post(`/api/v1/workflow/tasks/${taskId}/approve`, {});

    await actAs(page, 'backoffice');
    await page.goto(`/trades/${tradeId}`);
    await page.getByRole('tab', { name: 'Confirmations' }).click();
    await page.getByTestId('enter-theirs').click();
    await page.getByTestId('theirs-reference').fill(`AURORA-${tradeId.slice(-6)}`);
    await page.getByTestId('theirs-submit').click();
    await expect(page).toHaveURL(/\/confirmations\/[0-9a-f-]{36}$/);
    await expect(page.getByTestId('confirmation-status')).toHaveText(/^matched$/i);
  });
});
