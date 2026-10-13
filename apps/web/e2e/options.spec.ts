import { expect, test } from '@playwright/test';
import { asPersona } from './api';
import { actAs } from './fixtures';

interface Leg {
  option?: { optionType: string; strike: number } | null;
}

interface Trade {
  id: string;
  instrumentType: string;
  legs: Leg[];
}

test.describe('options on EUA futures (ADR 0047)', () => {
  test('the volatility curve lists volatilities and valuation results show the greeks', async ({ page }) => {
    await actAs(page, 'risk');
    await page.goto('/market-data/curves?curve=EUA_VOL');
    await page.getByTestId('official-curve-open').click();
    await expect(page).toHaveURL(/\/market-data\/snapshots\//);
    await expect(page.getByText('Volatility (%)').first()).toBeVisible();
    await page.goto('/valuation');
    await page
      .getByRole('gridcell', { name: /official/i })
      .first()
      .click();
    // The option columns are the grid's last: scroll them into view once the rows are in (the grid renders only the
    // columns in view).
    await expect(page.locator('.ag-row').first()).toBeVisible();
    await expect(async () => {
      await page
        .locator('.ag-body-horizontal-scroll-viewport')
        .first()
        .evaluate((element) => {
          element.scrollLeft = element.scrollWidth;
        });
      await expect(page.getByRole('columnheader', { name: 'Delta' })).toBeVisible({ timeout: 1_000 });
    }).toPass();
    await expect(page.getByRole('columnheader', { name: 'Vol (%)' })).toBeVisible();
  });

  test('the demo collar shows its option terms instead of deliveries', async ({ page, request }) => {
    const middle = asPersona(request, 'middle', 'MIDDLE_OFFICE');
    const trades = await middle.get<Trade[]>('/api/v1/trades?limit=500');
    const call = trades.find(
      (trade) => trade.instrumentType === 'OPTION' && trade.legs[0]?.option?.optionType === 'CALL',
    );
    if (!call) throw new Error('Demo data missing the bought call');
    await actAs(page, 'middle');
    await page.goto(`/trades/${call.id}`);
    await expect(page.getByTestId('trade-option-terms').first()).toContainText('Call');
    await expect(page.getByTestId('trade-option-terms').first()).toContainText('Strike');
    await expect(page.getByTestId('option-no-deliveries').first()).toBeVisible();
  });
});
