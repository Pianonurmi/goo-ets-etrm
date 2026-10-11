import AxeBuilder from '@axe-core/playwright';
import { expect, test, type Page } from '@playwright/test';
import { signIn, switchPersona } from './fixtures';

/** The public demo (ADR 0032): sign in once, then act as personas; runs against the showcase and demo profiles. */

async function choose(page: Page, testId: string, option: RegExp) {
  await page.getByTestId(testId).click();
  await page.getByRole('option', { name: option }).first().click();
}

test.describe('showcase', () => {
  test('the sign-in page explains the demo and passes axe', async ({ page }) => {
    await page.goto('/trades');
    await expect(page.getByTestId('login-submit')).toBeVisible();
    await expect(page.getByText('fictitious', { exact: false })).toBeVisible();
    const results = await new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa']).analyze();
    const serious = results.violations
      .filter((violation) => violation.impact === 'serious' || violation.impact === 'critical')
      .map((violation) => `${violation.id} (${violation.nodes.length}) ${violation.help}`);
    expect(serious, serious.join('\n')).toEqual([]);
  });

  test('sign in, capture as trader, approve as a second persona, sign out', async ({ page }) => {
    await signIn(page);
    await expect(page.getByTestId('demo-banner')).toBeVisible();
    await expect(page.getByTestId('persona-menu')).toContainText('Tia Trader');

    await page.goto('/trades/new');
    await choose(page, 'field-book', /DEMO-FI-TRD/);
    await choose(page, 'field-counterparty', /CP-NORDVIND/);
    await page.getByTestId('leg-0-quantity').fill('40');
    await page.getByTestId('leg-0-price').fill('2.05');
    await choose(page, 'leg-0-account', /SIM-DEMO-FI/);
    await page.getByTestId('leg-0-cp-account').fill('SIMULATOR:SIM-NORDVIND-1');
    await page.getByTestId('leg-0-delivery-0-quantity').fill('40');
    await expect(page.getByTestId('criteria-summary')).not.toHaveText('…');
    await page.getByTestId('submit-trade').click();
    await expect(page).toHaveURL(/\/trades\/[0-9a-f-]{36}$/);
    await expect(page.getByTestId('trade-status')).toHaveText(/^pending approval$/i);

    // Four eyes hold in the showcase too: the persona, not the sign-in, is the person.
    await page.getByTestId('approval-task').click();
    await expect(page.getByTestId('not-candidate')).toBeVisible();
    await switchPersona(page, 'middle');
    await page.getByTestId('decide-APPROVE').click();
    await expect(page.getByText('Decided by middle', { exact: false })).toBeVisible();

    await page.getByTestId('account-menu').click();
    await expect(page.getByTestId('account-login')).toHaveText('demo');
    await page.getByTestId('sign-out').click();
    await expect(page.getByTestId('login-submit')).toBeVisible();
    await page.goto('/inbox');
    await expect(page.getByTestId('login-submit')).toBeVisible();
  });

  test('the admin persona sees the reset schedule; a reset right after a start is refused', async ({ page }) => {
    await signIn(page);
    await switchPersona(page, 'admin');
    await page.goto('/admin/demo');
    await expect(page.getByTestId('seed-state')).toHaveText(/^completed$/i);
    await expect(page.getByTestId('next-reset')).not.toHaveText('—');
    await page.getByTestId('reset-demo').click();
    await page.getByTestId('reset-reason').fill('End-to-end check of the cool-down');
    await expect(page.getByTestId('reset-submit')).toBeDisabled();
    await page.getByTestId('reset-confirm').fill('RESET');
    await page.getByTestId('reset-submit').click();
    // The demo has just started: the cool-down refuses the reset and the server keeps running.
    await expect(page.getByTestId('problem-alert')).toBeVisible();
  });

  test('a wrong password is refused, and repeated failures lock the sign-in', async ({ page }) => {
    // A user name and documentation address of its own, so the lockout reaches no other test (the jar trusts
    // loopback proxies, as it trusts Caddy on the Docker network).
    const run = 1 + Math.floor(Math.random() * 250);
    await page.setExtraHTTPHeaders({ 'X-Forwarded-For': `203.0.113.${run}` });
    await signIn(page, 'not-the-password', `intruder-${run}`);
    await expect(page.getByTestId('login-error')).toContainText('Sign-in failed');
    for (let attempt = 2; attempt <= 5; attempt++) {
      await page.getByTestId('login-password').fill('not-the-password');
      await page.getByTestId('login-submit').click();
      await expect(page.getByTestId('login-error')).toBeVisible();
    }
    await page.getByTestId('login-password').fill('not-the-password');
    await page.getByTestId('login-submit').click();
    await expect(page.getByTestId('login-error')).toContainText('Too many attempts');
  });

  test('a first visit is welcomed once, with a way into the guide', async ({ page }) => {
    await signIn(page, undefined, undefined, true);
    await expect(page.getByTestId('welcome')).toBeVisible();
    await page.getByTestId('welcome-guide').click();
    await expect(page).toHaveURL(/\/guide$/);
    await expect(page.getByTestId('guide-orientation')).toBeVisible();
    await page.reload();
    await expect(page.getByTestId('guide-orientation')).toBeVisible();
    await expect(page.getByTestId('welcome')).toHaveCount(0);
  });
});
