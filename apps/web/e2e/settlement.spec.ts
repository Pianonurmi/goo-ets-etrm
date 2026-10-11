import { expect, test, type Page } from '@playwright/test';
import { deliveredDemoSale } from './api';
import { actAs, switchPersona } from './fixtures';

async function choose(page: Page, testId: string, option: RegExp) {
  await page.getByTestId(testId).click();
  await page.getByRole('option', { name: option }).first().click();
}

test.describe('settlement', () => {
  test('a delivery is invoiced, submitted, refused to its submitter and issued by a second accountant', async ({
    page,
    request,
  }) => {
    const { tradeNumber } = await deliveredDemoSale(request);
    await actAs(page, 'accountant');
    await page.goto('/settlement?view=open');
    await expect(page.getByRole('gridcell', { name: tradeNumber })).toBeVisible();

    await page.getByTestId('run-invoices').click();
    await choose(page, 'run-counterparty', /CP-AURORA/);
    await page.getByTestId('run-submit').click();
    await expect(page.getByTestId('run-result')).toContainText('1 draft created');
    await page.getByTestId('run-result').getByRole('link').click();

    await expect(page.getByTestId('invoice-status')).toHaveText(/^draft$/i);
    await expect(page.getByTestId('invoice-lines')).toContainText(tradeNumber);
    await page.getByTestId('invoice-submit').click();
    await expect(page.getByTestId('invoice-status')).toHaveText(/^pending approval$/i);
    const invoiceUrl = page.url();

    await page.getByTestId('invoice-task').click();
    await expect(page.getByTestId('own-request')).toBeVisible();
    await expect(page.getByTestId('decide-APPROVE')).toBeDisabled();

    await switchPersona(page, 'controller');
    await page.getByTestId('decide-APPROVE').click();
    await expect(page.getByText('Decided by controller', { exact: false })).toBeVisible();

    await page.goto(invoiceUrl);
    await expect(page.getByTestId('invoice-status')).toHaveText(/^issued$/i);
    await expect(page.getByTestId('invoice-number')).toHaveText(/^INV-\d{4}-\d{6}$/);
    await expect(page.getByTestId('invoice-payment-status')).toHaveText(/^unpaid$/i);
    await expect(page.getByTestId('invoice-pdf')).toBeVisible();
  });

  test('lists the demo invoices and opens the paid one; the front office has no settlement screen', async ({
    page,
  }) => {
    await actAs(page, 'controller');
    await page.goto('/settlement?tab=invoices&status=ISSUED');
    await page.getByRole('gridcell', { name: 'PAID', exact: true }).first().click();
    await expect(page).toHaveURL(/\/settlement\/invoices\/[0-9a-f-]{36}$/);
    await expect(page.getByTestId('invoice-payment-status')).toHaveText(/^paid$/i);
    await expect(page.getByTestId('invoice-paid')).toBeVisible();

    await switchPersona(page, 'trader');
    await expect(page.getByRole('navigation').getByRole('link', { name: 'Settlement' })).toHaveCount(0);
  });
});
