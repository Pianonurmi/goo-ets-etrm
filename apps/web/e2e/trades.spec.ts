import { expect, test, type Page } from '@playwright/test';
import { actAs, switchPersona } from './fixtures';

async function choose(page: Page, testId: string, option: RegExp) {
  await page.getByTestId(testId).click();
  await page.getByRole('option', { name: option }).first().click();
}

test.describe('trades', () => {
  test('capture, four-eyes approval through the inbox, confirmation, and the blotter', async ({ page }) => {
    await actAs(page, 'trader');
    await page.goto('/trades');
    await page.getByTestId('new-trade').click();

    await choose(page, 'field-book', /DEMO-FI-TRD/);
    await choose(page, 'field-counterparty', /CP-NORDVIND/);
    await page.getByTestId('leg-0-quantity').fill('75');
    await page.getByTestId('leg-0-price').fill('2.15');
    await choose(page, 'leg-0-account', /SIM-DEMO-FI/);
    await page.getByTestId('leg-0-cp-account').fill('SIMULATOR:SIM-NORDVIND-1');
    await page.getByTestId('leg-0-delivery-0-quantity').fill('75');
    await expect(page.getByTestId('criteria-summary')).not.toHaveText('…');
    await page.getByTestId('submit-trade').click();

    await expect(page).toHaveURL(/\/trades\/[0-9a-f-]{36}$/);
    const tradeNumber = (await page.getByTestId('trade-number').textContent()) ?? '';
    expect(tradeNumber).toMatch(/^T-\d{4}-\d+$/);
    await expect(page.getByTestId('trade-status')).toHaveText(/^pending approval$/i);

    // The trader cannot decide their own trade: the approval task is not theirs to take.
    await page.getByTestId('approval-task').click();
    await expect(page.getByTestId('not-candidate')).toBeVisible();

    await switchPersona(page, 'middle');
    await page.getByTestId('decide-APPROVE').click();
    await expect(page.getByText('Decided by middle', { exact: false })).toBeVisible();

    await switchPersona(page, 'backoffice');
    await page.goto('/trades');
    await page.getByTestId('trade-search').fill(tradeNumber);
    // Wait for the search to narrow the blotter, so the click does not land on a row being replaced.
    await expect(page.getByTestId('trade-status')).toHaveCount(1);
    await page.getByRole('gridcell', { name: tradeNumber }).click();
    await expect(page.getByTestId('trade-status')).toHaveText(/^approved$/i);
    await page.getByTestId('confirm-trade').click();
    await page.getByTestId('confirmation-ref').fill(`E2E-${tradeNumber}`);
    await page.getByTestId('confirm-submit').click();
    await expect(page.getByTestId('trade-status')).toHaveText(/^confirmed$/i);
  });

  test('refuses an invalid trade with field messages', async ({ page }) => {
    await actAs(page, 'trader');
    await page.goto('/trades/new');
    await page.getByTestId('submit-trade').click();
    await expect(page.getByText('Required').first()).toBeVisible();
    await expect(page).toHaveURL(/\/trades\/new$/);
  });
});
