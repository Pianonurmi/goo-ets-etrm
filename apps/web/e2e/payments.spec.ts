import { expect, test, type Page } from '@playwright/test';
import { draftNetting, issuedDemoInvoice, unique } from './api';
import { actAs, switchPersona } from './fixtures';

async function choose(page: Page, testId: string, option: RegExp) {
  await page.getByTestId(testId).click();
  await page.getByRole('option', { name: option }).first().click();
}

test.describe('payments, netting and VAT', () => {
  test('a bank payment naming the invoice in its remittance pays it', async ({ page, request }) => {
    const { invoiceId, number, grossTotal } = await issuedDemoInvoice(request);
    await actAs(page, 'accountant');
    await page.goto('/settlement?tab=payments');
    await page.getByTestId('record-payment').click();
    await choose(page, 'payment-counterparty', /CP-AURORA/);
    await page.getByTestId('payment-amount').fill(String(grossTotal));
    await page.getByTestId('payment-reference').fill(`E2E-BANK-${invoiceId.slice(-8)}`);
    await page.getByTestId('payment-remittance').fill(`Invoice ${number}`);
    await page.getByTestId('payment-submit').click();
    await expect(page.getByText('Payment recorded and applied')).toBeVisible();
    await expect(page.getByRole('gridcell', { name: `E2E-BANK-${invoiceId.slice(-8)}` })).toBeVisible();

    await page.goto(`/settlement/invoices/${invoiceId}`);
    await expect(page.getByTestId('invoice-payment-status')).toHaveText(/^paid$/i);
  });

  test('a netting is agreed by a second accountant and settled with the net payment', async ({ page, request }) => {
    // A 300 MWh sale at 1.95 against a 200 MWh purchase at 1.70 (340.00 net): the counterparty pays us 245.00 EUR.
    const { nettingId, counterpartyCode } = await draftNetting(request);
    await actAs(page, 'accountant');
    await page.goto(`/settlement/netting/${nettingId}`);
    await expect(page.getByTestId('netting-status')).toHaveText(/^draft$/i);
    await expect(page.getByTestId('netting-own')).toBeVisible();
    await expect(page.getByTestId('netting-agree')).toBeHidden();
    await expect(page.getByTestId('netting-net')).toContainText('The counterparty pays us 245.00 EUR');
    const statementUrl = page.url();

    await switchPersona(page, 'controller');
    await page.getByTestId('netting-agree').click();
    await page.getByTestId('agree-reference').fill(`E-mail ${counterpartyCode}`);
    await page.getByTestId('agree-submit').click();
    await expect(page.getByTestId('netting-status')).toHaveText(/^agreed$/i);

    // The counterparty pays the net amount; nothing in the remittance, so it stays unapplied for the netting.
    const reference = `E2E-NETTING-${counterpartyCode.slice(-8)}`;
    await page.goto('/settlement?tab=payments');
    await page.getByTestId('record-payment').click();
    await choose(page, 'payment-counterparty', new RegExp(counterpartyCode));
    await page.getByTestId('payment-amount').fill('245.00');
    await page.getByTestId('payment-reference').fill(reference);
    await page.getByTestId('payment-submit').click();
    await expect(page.getByText('245.00 EUR is unapplied')).toBeVisible();

    await page.goto(statementUrl);
    await page.getByTestId('netting-settle').click();
    await choose(page, 'settle-payment', new RegExp(reference));
    await page.getByTestId('settle-submit').click();
    await expect(page.getByTestId('netting-status')).toHaveText(/^settled$/i);
  });

  test('VAT rules: the determination finds the domestic rate; a proposal waits for approval', async ({ page }) => {
    await actAs(page, 'accountant');
    await page.goto('/settlement?tab=vat-rules');
    await page.getByTestId('determine-supplier').fill('FI');
    await page.getByTestId('determine-customer').fill('FI');
    await page.getByTestId('determine').click();
    await expect(page.getByTestId('determination-result')).toContainText('GOO-STD-FI');

    await page.getByTestId('propose-rule').click();
    const code = `E2E-RULE-${unique()}`;
    await page.getByTestId('rule-code').fill(code);
    await page.getByTestId('rule-description').fill('End-to-end test rule');
    await page.getByTestId('rule-rate').fill('25.5');
    await page.getByTestId('rule-submit').click();
    await expect(page.getByText('Rule sent for approval')).toBeVisible();
    await expect(page.getByRole('row', { name: new RegExp(code) })).toContainText('DRAFT');

    await page.goto('/settlement?tab=reports&report=vat');
    await expect(page.getByTestId('report-table')).toBeVisible();
  });
});
