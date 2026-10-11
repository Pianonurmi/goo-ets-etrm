import AxeBuilder from '@axe-core/playwright';
import { expect, test } from '@playwright/test';
import { TOURS } from '../src/shared/tour/tours';
import { actAs } from './fixtures';

test.describe('help and tours', () => {
  test('the help drawer explains the screen in view and opens the manual there', async ({ page }) => {
    await actAs(page, 'accountant');
    await page.goto('/month-end');
    await page.getByTestId('help-button').click();
    await expect(page.getByTestId('help-drawer')).toContainText('Ledger and month end');
    await expect(
      page.getByTestId('help-content').getByRole('heading', { name: 'Month end', exact: true }),
    ).toBeVisible();
    await page.getByTestId('help-open-manual').click();
    await expect(page).toHaveURL(/\/help\/ledger#month-end$/);
    await page.getByTestId('help-page-trading').click();
    await expect(page.getByTestId('help-content').getByRole('heading', { name: 'Capture a trade' })).toBeVisible();
  });

  test('the command palette finds a heading of the manual', async ({ page }) => {
    await actAs(page, 'trader');
    await page.goto('/');
    await page.getByTestId('palette-button').click();
    await page.getByTestId('palette-search').fill('capture a trade');
    await page.getByRole('button', { name: /Capture a trade/ }).click();
    await expect(page).toHaveURL(/\/help\/trading#capture-a-trade$/);
  });

  test('the orientation tour runs to its end from the demo guide and is marked done', async ({ page }) => {
    await actAs(page, 'trader');
    await page.goto('/guide');
    await page.getByTestId('guide-start-orientation').click();
    await expect(page.getByTestId('tour-card')).toBeVisible();
    await expect(page.getByTestId('tour-progress')).toHaveText('1 of 8');
    const axe = await new AxeBuilder({ page })
      .include('[data-testid="tour-card"]')
      .withTags(['wcag2a', 'wcag2aa'])
      .analyze();
    expect(axe.violations).toEqual([]);
    for (let step = 2; step <= 8; step++) {
      await page.getByTestId('tour-next').click();
      await expect(page.getByTestId('tour-progress')).toHaveText(`${step} of 8`);
    }
    await page.getByTestId('tour-next').click();
    await expect(page.getByTestId('tour-card')).toHaveCount(0);
    await page.goto('/guide');
    await expect(page.getByTestId('guide-orientation')).toContainText('Done');
  });

  test('a tour step meant for another persona offers to switch to it', async ({ page }) => {
    await actAs(page, 'middle');
    await page.goto('/guide');
    await page.getByTestId('guide-start-trade').click();
    await expect(page).toHaveURL(/\/trades\/new$/);
    await page.getByTestId('tour-switch-persona').click();
    await expect(page.getByTestId('tour-switch-persona')).toHaveCount(0);
    await expect(page.getByTestId('persona-menu')).toContainText('Tia Trader');
    await page.keyboard.press('Escape');
    await expect(page.getByTestId('tour-card')).toHaveCount(0);
  });

  test('every tour runs to its end, and every step finds what it points at', async ({ page }) => {
    test.setTimeout(240_000);
    for (const tour of TOURS) {
      await actAs(page, tour.personas[0] ?? 'trader');
      await page.goto('/guide');
      await page.getByTestId(`guide-start-${tour.id}`).click();
      for (let step = 1; step <= tour.steps.length; step++) {
        const card = page.getByTestId('tour-card');
        await expect(page.getByTestId('tour-progress')).toHaveText(`${step} of ${tour.steps.length}`);
        const switchPersona = page.getByTestId('tour-switch-persona');
        if (await switchPersona.isVisible()) await switchPersona.click();
        await expect(card, `${tour.id} step ${step}`).toHaveAttribute('data-anchor', /^(found|none)$/);
        await page.getByTestId('tour-next').click();
      }
      await expect(page.getByTestId('tour-card')).toHaveCount(0);
    }
  });
});
