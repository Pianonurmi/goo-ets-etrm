import { expect, test } from '@playwright/test';
import { actAs, expectShell, switchPersona } from './fixtures';

test.describe('application shell', () => {
  test('loads, acts as a persona and switches to another', async ({ page }) => {
    await actAs(page, 'trader');
    await page.goto('/');
    await expectShell(page);
    await expect(page.getByRole('heading', { name: 'Welcome, trader' })).toBeVisible();
    await expect(page.getByTestId('my-roles')).toContainText('TRADER');

    await switchPersona(page, 'accountant');
    await expect(page.getByRole('heading', { name: 'Welcome, accountant' })).toBeVisible();
    await expect(page.getByTestId('my-roles')).toContainText('ACCOUNTANT');
  });

  test('serves client routes on reload and shows its own not-found page', async ({ page }) => {
    await page.goto('/no/such/screen');
    await expectShell(page);
    await expect(page.getByTestId('not-found')).toBeVisible();
    await page.reload();
    await expect(page.getByTestId('not-found')).toBeVisible();
  });

  test('answers unknown API paths with a problem, not the shell', async ({ request }) => {
    const response = await request.get('/api/v1/no-such-endpoint');
    expect(response.status()).toBe(404);
    expect((await response.json()) as { type: string }).toMatchObject({ type: 'urn:etrm:problem:not-found' });
  });
});
