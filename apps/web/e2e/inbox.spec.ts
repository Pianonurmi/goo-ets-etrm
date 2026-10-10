import { expect, test } from '@playwright/test';
import { captureDemoTrade } from './api';
import { actAs, switchPersona } from './fixtures';

test.describe('approval inbox', () => {
  test('a trader may not decide; middle office may not approve what it captured; another middle office user can', async ({
    page,
    request,
  }) => {
    const { taskId } = await captureDemoTrade(request, 120, { actor: 'middle', roles: 'MIDDLE_OFFICE' });

    await actAs(page, 'trader');
    await page.goto(`/inbox/${taskId}`);
    await expect(page.getByTestId('task-summary')).toBeVisible();
    await expect(page.getByTestId('not-candidate')).toBeVisible();

    await switchPersona(page, 'middle');
    await expect(page.getByTestId('own-request')).toBeVisible();
    await expect(page.getByTestId('decide-APPROVE')).toBeDisabled();

    await switchPersona(page, 'middle2');
    await expect(page.getByTestId('own-request')).toBeHidden();
    await page.getByTestId('decide-APPROVE').click();
    await expect(page.getByText('Decided by middle2', { exact: false })).toBeVisible();
    await expect(page.getByTestId('decide-APPROVE')).toBeHidden();
  });

  test('lists what the user can decide and opens a task from the grid', async ({ page, request }) => {
    await captureDemoTrade(request, 90);
    await actAs(page, 'middle2');
    await page.goto('/inbox');
    await expect(page.getByTestId('inbox-count')).not.toHaveText('0 tasks shown');
    await page.getByRole('gridcell', { name: 'Trade approval' }).first().click();
    await expect(page).toHaveURL(/\/inbox\/[0-9a-f-]{36}$/);
    await expect(page.getByTestId('decide-APPROVE')).toBeEnabled();
  });
});
