import { expect, test, type Page } from '@playwright/test';
import { actAs, switchPersona } from './fixtures';

async function choose(page: Page, testId: string, option: RegExp) {
  await page.getByTestId(testId).click();
  await page.getByRole('option', { name: option }).first().click();
}

test.describe('risk and EU ETS compliance', () => {
  test('a credit limit is requested by one analyst and approved by another', async ({ page }) => {
    await actAs(page, 'risk');
    await page.goto('/risk');
    await expect(page.getByRole('gridcell', { name: 'Credit exposure' }).first()).toBeVisible();
    await page.getByTestId('request-limit').click();
    await choose(page, 'limit-counterparty', /CP-AURORA/);
    await page.getByTestId('limit-amount').fill('75000');
    await page.getByTestId('limit-reason').fill('New counterparty onboarding');
    await page.getByTestId('request-limit-submit').click();
    await expect(page.getByText('Limit sent for approval')).toBeVisible();

    await page.getByLabel('Show pending, rejected and retired').check();
    await page
      .getByRole('gridcell', { name: /Aurora/ })
      .first()
      .click();
    await expect(page).toHaveURL(/\/risk\/limits\/[0-9a-f-]{36}$/);
    await expect(page.getByTestId('limit-status')).toHaveText('PENDING APPROVAL');
    await page.getByRole('link', { name: 'Approval task' }).click();
    await expect(page.getByTestId('own-request')).toBeVisible();
    await switchPersona(page, 'risk2');
    await page.getByTestId('decide-APPROVE').click();
    await expect(page.getByText('Decided by risk2', { exact: false })).toBeVisible();
  });

  test('measures limits and shows exposures and PFE add-ons', async ({ page }) => {
    await actAs(page, 'risk');
    await page.goto('/risk');
    await page.getByTestId('measure').click();
    await expect(page.getByText(/limits? measured/)).toBeVisible();
    await expect(page.locator('.mantine-Progress-root').first()).toBeVisible();
    await page.getByRole('tab', { name: 'Exposures' }).click();
    await expect(page.getByRole('columnheader', { name: 'Exposure' })).toBeVisible();
    await page.getByRole('tab', { name: 'PFE add-ons' }).click();
    await expect(page.getByRole('cell', { name: 'Emission allowances' }).first()).toBeVisible();
  });

  test('shows the installation, its deadlines and records an emission estimate', async ({ page }) => {
    await actAs(page, 'middle');
    await page.goto('/ets');
    await expect(page.getByTestId('installations')).toBeVisible();
    await expect(page.getByTestId('deadline-calendar')).toBeVisible();
    await page.getByTestId('installations').getByRole('link').first().click();
    await expect(page).toHaveURL(/\/ets\/installations\/[0-9a-f-]{36}/);
    await expect(page.getByRole('gridcell', { name: 'Forecast' })).toBeVisible();
    await page.getByTestId('record-emissions').click();
    await page.getByTestId('emissions-quantity').fill('1234');
    await page.getByTestId('record-emissions-submit').click();
    await expect(page.getByText('Emissions recorded')).toBeVisible();
    await expect(page.getByRole('gridcell', { name: '1,234 t' })).toBeVisible();
  });
});
