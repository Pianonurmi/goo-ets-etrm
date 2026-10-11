import { expect, test, type Page } from '@playwright/test';
import { asPersona } from './api';
import { actAs, switchPersona } from './fixtures';

async function choose(page: Page, testId: string, option: RegExp) {
  await page.getByTestId(testId).click();
  await page.getByRole('option', { name: option }).first().click();
}

test.describe('registry instructions', () => {
  test('the demo transfer is approved by a second person and executed by the simulator', async ({ page }) => {
    await actAs(page, 'backoffice');
    await page.goto('/registry');
    await page.getByRole('gridcell', { name: 'PENDING APPROVAL' }).first().click();
    await expect(page).toHaveURL(/\/registry\/instructions\/[0-9a-f-]{36}$/);
    await expect(page.getByTestId('instruction-status')).toHaveText(/^pending approval$/i);
    const instructionUrl = page.url();

    await page.getByTestId('task-INSTRUCTION_APPROVAL').click();
    await expect(page.getByTestId('own-request')).toBeVisible();
    await expect(page.getByTestId('decide-APPROVE')).toBeDisabled();

    await switchPersona(page, 'middle');
    await page.getByTestId('decide-APPROVE').click();
    await expect(page.getByText('Decided by middle', { exact: false })).toBeVisible();

    await page.goto(instructionUrl);
    await expect(page.getByTestId('instruction-status')).toHaveText(/^confirmed$/i);
    await expect(page.getByTestId('instruction-lifecycle')).toContainText('middle');
  });

  test('a transfer started from a lot waits for approval and can be cancelled', async ({ page, request }) => {
    const admin = asPersona(request, 'admin', 'ADMIN');
    const entity = (await admin.get<{ id: string; code: string }[]>('/api/v1/reference/legal-entities')).find(
      (e) => e.code === 'DEMO-FI',
    );
    const lot = (
      await admin.get<{ id: string }[]>(`/api/v1/inventory/lots?holderEntityId=${entity?.id}&status=HELD`)
    )[0];
    if (!lot) throw new Error('No held demo lot');

    await actAs(page, 'backoffice');
    await page.goto(`/inventory/lots/${lot.id}`);
    await page.getByTestId('lot-transfer').click();
    await page.getByTestId('transfer-quantity').fill('10');
    await choose(page, 'transfer-registry', /SIMULATOR/);
    await page.getByTestId('transfer-account').fill('SIM-E2E-RECEIVER');
    await expect(page.getByTestId('transfer-route')).toBeVisible();
    await page.getByTestId('transfer-submit').click();

    await expect(page).toHaveURL(/\/registry\/instructions\/[0-9a-f-]{36}$/);
    await expect(page.getByTestId('instruction-status')).toHaveText(/^pending approval$/i);
    await page.getByTestId('instruction-cancel').click();
    await page.getByTestId('reason').fill('Entered by mistake');
    await page.getByTestId('reason-submit').click();
    await expect(page.getByTestId('instruction-status')).toHaveText(/^cancelled$/i);
    await expect(page.getByTestId('instruction-error')).toContainText('Entered by mistake');
  });

  test('shows transfer routes and statement formats; a trader has no registry screen', async ({ page }) => {
    await actAs(page, 'backoffice');
    await page.goto('/registry?tab=routes');
    await expect(page.getByTestId('routes-table')).toBeVisible();
    await page.goto('/registry?tab=formats');
    await expect(page.getByTestId('formats-table')).toBeVisible();
    await switchPersona(page, 'trader');
    await expect(page.getByRole('link', { name: 'Registry', exact: true })).toHaveCount(0);
  });
});
