import { expect, test, type Page } from '@playwright/test';
import { captureDemoTrade, confirmedDemoSale } from './api';
import { actAs } from './fixtures';

async function choose(page: Page, testId: string, option: RegExp) {
  await page.getByTestId(testId).click();
  await page.getByRole('option', { name: option }).first().click();
}

/** Next year's December contract: always still trading. */
const nextDecember = `EUAZ${(new Date().getUTCFullYear() + 1) % 100}`;

test.describe('trading', () => {
  test('a trader captures an EUA future with the clearing house; the contract sets its delivery', async ({ page }) => {
    await actAs(page, 'trader');
    await page.goto('/trades/new');
    await choose(page, 'field-instrument', /^Future$/);
    await choose(page, 'field-book', /DEMO-FI-TRD/);
    await choose(page, 'field-counterparty', /CP-CLEARING/);
    await choose(page, 'leg-0-contract', new RegExp(nextDecember));
    await expect(page.getByTestId('leg-0-future-delivery')).toContainText('whole lots of 1000');
    await page.getByTestId('leg-0-quantity').fill('5000');
    await page.getByTestId('leg-0-price').fill('71.25');
    await page.getByTestId('submit-trade').click();

    await expect(page).toHaveURL(/\/trades\/[0-9a-f-]{36}$/);
    await expect(page.getByTestId('trade-status')).toHaveText(/^pending approval$/i);
    await expect(page.getByText(nextDecember)).toBeVisible();
  });

  test('a trader captures an index-priced GoO sale with a broker', async ({ page }) => {
    await actAs(page, 'trader');
    await page.goto('/trades/new');
    await choose(page, 'field-book', /DEMO-FI-TRD/);
    await choose(page, 'field-counterparty', /CP-AURORA/);
    await choose(page, 'field-broker', /CP-NORDBROKER/);
    await page.getByTestId('leg-0-price-type').getByText('Index').click();
    await choose(page, 'leg-0-index', /GOO_NORDIC_HYDRO_IDX/);
    await page.getByTestId('leg-0-quantity').fill('40');
    await page.getByTestId('leg-0-price').fill('0.15');
    await choose(page, 'leg-0-account', /SIM-DEMO-FI/);
    await page.getByTestId('leg-0-delivery-0-quantity').fill('40');
    await expect(page.getByTestId('criteria-summary')).not.toHaveText('…');
    await page.getByTestId('submit-trade').click();

    await expect(page).toHaveURL(/\/trades\/[0-9a-f-]{36}$/);
    await expect(page.getByText('GOO_NORDIC_HYDRO_IDX + 0.15')).toBeVisible();
    await expect(page.getByText('Nordic Carbon Brokers AB (fictitious)')).toBeVisible();
  });

  test('an intercompany sale needs the mirror book of the other entity', async ({ page }) => {
    await actAs(page, 'trader');
    await page.goto('/trades/new');
    await choose(page, 'field-book', /DEMO-FI-TRD/);
    await choose(page, 'field-counterparty', /CP-DEMO-SE/);
    await page.getByTestId('leg-0-quantity').fill('30');
    await page.getByTestId('leg-0-price').fill('1.95');
    await choose(page, 'leg-0-account', /SIM-DEMO-FI/);
    await page.getByTestId('leg-0-delivery-0-quantity').fill('30');
    await expect(page.getByTestId('criteria-summary')).not.toHaveText('…');
    await page.getByTestId('submit-trade').click();
    await expect(page.getByTestId('field-mirror-book')).toHaveAttribute('aria-invalid', 'true');

    await choose(page, 'field-mirror-book', /DEMO-SE-TRD/);
    await page.getByTestId('submit-trade').click();
    await expect(page).toHaveURL(/\/trades\/[0-9a-f-]{36}$/);
    await expect(page.getByTestId('intercompany')).toBeVisible();
  });

  test('risk checks a pending trade against the limits again', async ({ page, request }) => {
    const { tradeId } = await captureDemoTrade(request, 25);
    await actAs(page, 'risk');
    await page.goto(`/trades/${tradeId}`);
    await page.getByTestId('check-limits').click();
    await expect(page.getByTestId('limit-findings')).toBeVisible();
  });

  test('back office finds an open delivery, records it, settles the trade and verifies its confirmation', async ({
    page,
    request,
  }) => {
    const { tradeNumber } = await confirmedDemoSale(request, 35);
    await actAs(page, 'backoffice');
    await page.goto('/trades?view=deliveries');
    await page.getByTestId('obligation-search').fill(tradeNumber);
    await page.getByTestId('obligations-grid').getByRole('gridcell', { name: tradeNumber }).click();
    await expect(page.getByTestId('trade-status')).toHaveText(/^confirmed$/i);

    // Our confirmation is issued as a PDF whose hash the documents tab checks.
    await page.getByRole('tab', { name: 'Confirmations' }).click();
    await page.getByTestId('issue-confirmation').click();
    await expect(page.getByRole('link', { name: 'ISSUED' })).toBeVisible();
    await page.getByTestId('tab-documents').click();
    await page.getByTestId('verify-document').first().click();
    await expect(page.getByTestId('document-verified').first()).toHaveText('Intact');

    await page.getByRole('tab', { name: 'Legs and deliveries' }).click();
    await page.getByTestId('delivery-actions-1-1').click();
    await page.getByTestId('delivery-actions-1-1-record').click();
    await expect(page.getByTestId('obligation-remaining')).toContainText('35');
    await expect(page.getByTestId('delivery-quantity')).toHaveValue('35');
    await page.getByTestId('delivery-submit').click();
    await expect(page.getByTestId('trade-status')).toHaveText(/^delivered$/i);

    await page.getByTestId('settle-trade').click();
    await expect(page.getByTestId('trade-status')).toHaveText(/^settled$/i);
  });
});
