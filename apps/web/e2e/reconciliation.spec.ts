import { expect, test, type APIRequestContext, type Page } from '@playwright/test';
import { asPersona } from './api';
import { actAs, switchPersona } from './fixtures';

interface Lot {
  id: string;
  status: string;
  issuanceRef: string;
  range: { start: number; end: number };
  deviceGsrn: string;
  productionPeriod: { start: string; endExclusive: string };
  registryAccountId: string;
}

/** The demo GoO account's lots still in it (held, reserved or in transfer), oldest range first. */
async function demoAccountLots(request: APIRequestContext): Promise<Lot[]> {
  const admin = asPersona(request, 'admin', 'ADMIN');
  const entity = (await admin.get<{ id: string; code: string }[]>('/api/v1/reference/legal-entities')).find(
    (e) => e.code === 'DEMO-FI',
  );
  const accounts = await admin.get<{ id: string; accountNumber: string }[]>(
    `/api/v1/reference/registry-accounts?holderType=LEGAL_ENTITY&holderId=${entity?.id}`,
  );
  const account = accounts.find((a) => a.accountNumber === 'SIM-DEMO-FI');
  const lots = await admin.get<Lot[]>(`/api/v1/inventory/lots?registryAccountId=${account?.id}`);
  return lots
    .filter((lot) => ['HELD', 'RESERVED', 'IN_TRANSFER'].includes(lot.status))
    .sort((a, b) => a.range.start - b.range.start);
}

function statement(lots: Lot[]): string {
  const rows = lots.map(
    (lot) =>
      `${lot.issuanceRef},${lot.range.start},${lot.range.end},${lot.deviceGsrn},${lot.productionPeriod.start},${lot.productionPeriod.endExclusive}`,
  );
  return (
    ['issuance_ref,cert_no_start,cert_no_end,device_gsrn,production_start,production_end', ...rows].join('\n') + '\n'
  );
}

async function upload(page: Page, csv: string): Promise<void> {
  await page.getByTestId('upload-holdings').click();
  await page.getByTestId('holdings-csv').fill(csv);
  await page.getByTestId('holdings-upload').click();
  await expect(page.getByTestId('holdings-result')).toBeVisible();
  await page.keyboard.press('Escape');
}

test.describe('reconciliation', () => {
  test('the demo break is taken, its acceptance asked for and approved by a second person', async ({ page }) => {
    await actAs(page, 'backoffice');
    await page.goto('/reconciliation');
    await expect(page.getByTestId('break-ageing')).toBeVisible();
    await page.getByRole('gridcell', { name: 'In our books, not in the registry' }).first().click();
    await expect(page.getByTestId('break-status')).toHaveText(/^open$/i);
    const breakUrl = page.url();

    await page.getByTestId('break-take').click();
    await expect(page.getByTestId('break-status')).toHaveText(/^investigating$/i);
    await expect(page.getByTestId('break-withdraw')).toBeVisible();
    await page.getByTestId('break-accept').click();
    await page.getByTestId('break-note').fill('Registry statement cut off; the certificates are ours');
    await page.getByTestId('break-note-submit').click();
    await expect(page.getByTestId('break-status')).toHaveText(/^pending acceptance$/i);

    await page.getByTestId('task-BREAK_ACCEPTANCE').click();
    await expect(page.getByTestId('own-request')).toBeVisible();
    await switchPersona(page, 'middle');
    await page.getByTestId('decide-APPROVE').click();
    await expect(page.getByText('Decided by middle', { exact: false })).toBeVisible();

    await page.goto(breakUrl);
    await expect(page.getByTestId('break-status')).toHaveText(/^accepted$/i);
  });

  test('a break from one upload clears itself when the next statement shows the certificates', async ({
    page,
    request,
  }) => {
    const lots = await demoAccountLots(request);
    const omitted = lots.find((lot) => lot.status === 'HELD');
    if (!omitted) throw new Error('No held lot on the demo account');

    await actAs(page, 'backoffice');
    await page.goto('/reconciliation?tab=statements');
    await upload(page, statement(lots.filter((lot) => lot.id !== omitted.id)));

    const admin = asPersona(request, 'admin', 'ADMIN');
    const breaks = await admin.get<{ id: string; lotId: string | null; status: string }[]>(
      `/api/v1/inventory/breaks?registryAccountId=${omitted.registryAccountId}`,
    );
    const created = breaks.find((brk) => brk.lotId === omitted.id && brk.status === 'OPEN');
    if (!created) throw new Error(`No break for lot ${omitted.id}`);

    await upload(page, statement(lots));
    await page.goto(`/reconciliation/breaks/${created.id}`);
    await expect(page.getByTestId('break-status')).toHaveText(/^auto cleared$/i);
  });
});
