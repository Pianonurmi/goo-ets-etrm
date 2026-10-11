import { expect, test, type APIRequestContext, type Page } from '@playwright/test';
import { asPersona } from './api';
import { actAs } from './fixtures';

/** A suffix that keeps codes unique across runs on the same database. */
function stamp(): string {
  return Date.now().toString(36).toUpperCase();
}

function today(): string {
  return new Date().toISOString().slice(0, 10);
}

async function choose(page: Page, testId: string, option: RegExp, search?: string) {
  await page.getByTestId(testId).click();
  if (search) await page.getByTestId(testId).fill(search);
  await page.getByRole('option', { name: option }).first().click();
}

/** The as-of date of the latest official EUA forward curve, read the way valuation reads it. */
async function latestOfficialEuaDate(request: APIRequestContext): Promise<string> {
  const risk = asPersona(request, 'risk', 'RISK');
  const year = new Date().getUTCFullYear();
  const price = await risk.get<{ snapshotId: string }>(
    `/api/v1/market-data/curves/forward-price?product=EUA&delivery=${year + 1}-12-15&asOf=${today()}`,
  );
  const snapshot = await risk.get<{ asOfDate: string }>(`/api/v1/market-data/curves/snapshots/${price.snapshotId}`);
  return snapshot.asOfDate;
}

/** The newest vintage the demo prices for a bucket. */
async function latestVintage(request: APIRequestContext, bucket: string): Promise<number> {
  const instruments = await asPersona(request, 'risk', 'RISK').get<
    { bucketCode: string | null; productionYear: number | null; currency: string }[]
  >('/api/v1/market-data/instruments?kind=GOO_VINTAGE');
  const years = instruments
    .filter((i) => i.bucketCode === bucket && i.currency === 'EUR' && i.productionYear !== null)
    .map((i) => i.productionYear ?? 0);
  if (years.length === 0) throw new Error(`Demo data has no ${bucket} vintages`);
  return Math.max(...years);
}

test.describe('allocation, market data, curves, valuation and positions operations', () => {
  test('back office opens an obligation, constrains its shares and closes it', async ({ page }) => {
    const beneficiary = `E2E offices ${stamp()}`;
    await actAs(page, 'backoffice');
    await page.goto('/allocation');
    await page.getByTestId('open-obligation').click();
    await page.getByTestId('obligation-quantity').fill('7');
    await page.getByTestId('obligation-beneficiary').fill(beneficiary);
    await page.getByTestId('obligation-country').fill('FI');
    await expect(page.getByTestId('criteria-summary')).toBeVisible();
    await page.getByTestId('obligation-submit').click();

    await expect(page.getByText('Obligation opened')).toBeVisible();
    await expect(page).toHaveURL(/[?&]obligation=[0-9a-f-]{36}/);
    await expect(page.getByTestId('obligation-status')).toHaveText('Open');
    await expect(page.getByTestId('obligation-quantity-value')).toContainText('7');
    await expect(page.getByText(beneficiary)).toBeVisible();
    await expect(page.getByTestId('obligation-constraints-none')).toBeVisible();

    await page.getByTestId('obligation-edit-constraints').click();
    await page.getByTestId('constraints-max-share').fill('60');
    await page.getByTestId('constraints-add-min-share').click();
    await page.getByTestId('constraints-min-share-0-percent').fill('25');
    await page.getByTestId('constraints-save').click();
    await expect(page.getByText('Constraints saved')).toBeVisible();
    await expect(page.getByTestId('obligation-constraints')).toContainText('At most 60 % from one device');
    await expect(page.getByTestId('obligation-constraints')).toContainText('At least 25 % matching');

    await page.getByTestId('obligation-close').click();
    await page.getByTestId('reason').fill('Opened by the end-to-end test');
    await page.getByTestId('reason-submit').click();
    await expect(page.getByTestId('obligation-status')).toHaveText('Closed');
    await expect(page.getByTestId('obligation-closed')).toBeVisible();
    await expect(page.getByTestId('obligation-edit-constraints')).toHaveCount(0);
  });

  test('middle office adds an instrument, an alias, an import format and a price index', async ({ page }) => {
    const suffix = stamp();
    const instrument = `E2E_IDX_INSTR_${suffix}`;
    await actAs(page, 'middle');
    await page.goto('/market-data?tab=instruments');
    await page.getByTestId('new-instrument').click();
    await page.getByTestId('instrument-code').fill(instrument);
    await choose(page, 'instrument-kind', /^Index$/);
    await page.getByTestId('instrument-description').fill('End-to-end index instrument');
    await page.getByTestId('instrument-unit').fill('EUR/MWH');
    await page.getByTestId('instrument-submit').click();
    await expect(page.getByText(`Instrument ${instrument} created`)).toBeVisible();

    await page.getByTestId('add-alias').click();
    await choose(page, 'alias-instrument', new RegExp(`^${instrument}$`), instrument);
    await page.getByTestId('alias-source').fill('E2EFEED');
    await page.getByTestId('alias-external').fill(`feed-${suffix}`);
    await page.getByTestId('alias-submit').click();
    await expect(page.getByText(`Alias E2EFEED feed-${suffix} saved for ${instrument}`)).toBeVisible();

    const format = `E2E_FMT_${suffix}`;
    await page.goto('/market-data?tab=imports');
    await page.getByTestId('new-format').click();
    await page.getByTestId('json-code').fill(format);
    const definition = {
      kind: 'QUOTES_LONG_CSV',
      description: 'End-to-end closing prices',
      source: 'E2EFEED',
      columnMapping: { date: 'Date', instrument: 'Product', value: 'Close' },
      constants: { quoteType: 'CLOSE', currency: 'EUR' },
    };
    await page.getByTestId('json-editor').fill(JSON.stringify(definition));
    await page.getByTestId('json-submit').click();
    await expect(page.getByText(`Import format ${format} saved as version 1`)).toBeVisible();
    await expect(page.getByTestId(`format-version-${format}`)).toHaveText('v1');
    await page.getByTestId(`format-edit-${format}`).click();
    await page
      .getByTestId('json-editor')
      .fill(JSON.stringify({ ...definition, description: 'End-to-end closing prices, semicolons', delimiter: ';' }));
    await page.getByTestId('json-submit').click();
    await expect(page.getByTestId(`format-version-${format}`)).toHaveText('v2');
    await expect(page.getByTestId(`format-row-${format}`)).toContainText('semicolons');

    const index = `E2E_EUA_IDX_${suffix}`;
    await page.goto('/market-data?tab=indices');
    await expect(page.getByTestId('indices-table')).toContainText('GOO_NORDIC_HYDRO_IDX');
    await page.getByTestId('new-index').click();
    await page.getByTestId('index-code').fill(index);
    await page.getByTestId('index-description').fill('EUA spot close of the day before');
    await choose(page, 'index-instrument', /^EUA_SPOT$/, 'EUA_SPOT');
    await choose(page, 'index-quote-type', /^CLOSE$/);
    await choose(page, 'index-fixing-rule', /^Prior day$/);
    await page.getByTestId('index-submit').click();
    await expect(page.getByText(`Index ${index} created`)).toBeVisible();
    await expect(page.getByTestId(`index-row-${index}`)).toBeVisible();

    await choose(page, 'fixing-index', new RegExp(`^${index}$`), index);
    await page.getByTestId('fixing-date').fill(today());
    await page.getByTestId('fixing-show').click();
    await expect(page.getByTestId('fixing-value')).toContainText('EUR');
    await expect(page.getByTestId('fixing-result')).toContainText('From 1 quote');
  });

  test('risk creates a curve, reads the official curve and looks up prices', async ({ page, request }) => {
    const official = await latestOfficialEuaDate(request);
    const vintage = await latestVintage(request, 'EU_WIND');
    // A bucket of its own (one curve per bucket; it matches no certificate), so the test runs again on the same data.
    const curve = `ZZ_E2E_${stamp()}`;
    const bucket = `ZZ_E2E_B_${stamp()}`;
    await asPersona(request, 'middle', 'MIDDLE_OFFICE').post('/api/v1/products/buckets', {
      code: bucket,
      name: `End-to-end bucket ${bucket}`,
      criterion: { type: 'equals', attribute: 'DEVICE_COUNTRY', value: 'ZZ' },
      sortOrder: 9999,
    });
    await actAs(page, 'risk');
    await page.goto('/market-data/curves');
    await page.getByTestId('new-curve').click();
    await page.getByTestId('curve-code').fill(curve);
    await page.getByTestId('curve-description').fill('End-to-end wind vintages');
    await choose(page, 'curve-bucket', new RegExp(`^${bucket} `), bucket);
    await page.getByTestId('curve-submit').click();
    await expect(page.getByText(`Curve ${curve} created`)).toBeVisible();
    await expect(page.getByTestId('curve-select')).toHaveValue(new RegExp(`^${curve}`));

    await page.goto(`/market-data/curves?curve=EUA_FWD&asOf=${official}`);
    await expect(page.getByTestId('official-curve-status')).toHaveText('Official');
    await page.getByTestId('price-delivery').fill(`${new Date().getUTCFullYear() + 1}-12-15`);
    await page.getByTestId('price-look-up').click();
    await expect(page.getByTestId('price-value')).toContainText('EUR');
    await expect(page.getByTestId('price-result')).toContainText('EUA_FWD');

    await page.getByTestId('price-kind').getByText('Vintage (GoO)').click();
    await choose(page, 'price-bucket', /^EU_WIND$/, 'EU_WIND');
    await page.getByTestId('price-year').fill(String(vintage));
    await page.getByTestId('price-look-up').click();
    await expect(page.getByTestId('price-result')).toContainText('GOO_EU_WIND');

    await page.getByTestId('official-curve-open').click();
    await expect(page).toHaveURL(/\/market-data\/snapshots\/[0-9a-f-]{36}$/);
    await expect(
      page
        .getByTestId('snapshot-status')
        .filter({ hasText: /^official$/i })
        .first(),
    ).toBeVisible();

    await page.goto('/market-data/curves?curve=EUA_FWD&asOf=2001-01-02');
    await expect(page.getByTestId('official-curve-none')).toBeVisible();
  });

  test('shows the official valuation run as of a date', async ({ page }) => {
    await actAs(page, 'risk');
    await page.goto('/valuation?tab=official');
    await expect(page.getByTestId('official-run-status')).toHaveText('Official');
    await expect(page.getByTestId('official-run-date')).toHaveText(/^\d{4}-\d{2}-\d{2}$/);
    await page.getByTestId('official-date').fill('2001-01-02');
    await expect(page.getByTestId('official-none')).toBeVisible();
    await page.getByTestId('official-date').fill(today());
    await page.getByTestId('official-run-open').click();
    await expect(page).toHaveURL(/\/valuation\/runs\/[0-9a-f-]{36}$/);
    await expect(page.getByTestId('run-status')).toHaveText(/^official$/i);
  });

  test('lists position snapshots and takes new ones', async ({ page }) => {
    await actAs(page, 'middle');
    await page.goto('/positions?tab=snapshots');
    await expect(page.getByTestId('snapshots-table')).toBeVisible();
    await page.getByTestId('take-snapshots').click();
    await expect(page.getByText(/^Snapshots taken for \d{4}-\d{2}-\d{2}$/)).toBeVisible();
    await expect(page.getByTestId('snapshots-table').locator('tbody tr').first()).toBeVisible();
    await page.getByTestId('snapshot-kind').getByText('EU ETS').click();
    await expect(page.getByRole('columnheader', { name: 'Net position (t)' })).toBeVisible();
  });
});
