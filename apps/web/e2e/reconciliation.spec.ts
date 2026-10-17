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

interface Break {
  id: string;
  lotId: string | null;
  status: string;
}

/** Uploads a holdings statement for [accountId] through the API as back office; returns the breaks it opened. */
async function uploadStatement(request: APIRequestContext, accountId: string, csv: string): Promise<Break[]> {
  const headers = { 'X-Actor': 'backoffice', 'X-Roles': 'BACK_OFFICE', 'Content-Type': 'text/csv' };
  const today = new Date().toISOString().slice(0, 10);
  const response = await request.post(
    `/api/v1/registry/statements/csv?registryAccountId=${accountId}&statementDate=${today}`,
    { headers, data: csv },
  );
  if (!response.ok()) throw new Error(`Statement upload: ${response.status()} ${await response.text()}`);
  return ((await response.json()) as { breaks: Break[] }).breaks;
}

/** Every break on [accountId], whatever its status (the list defaults to open ones). */
async function allBreaks(request: APIRequestContext, accountId: string): Promise<Break[]> {
  const statuses = ['OPEN', 'INVESTIGATING', 'PENDING_ACCEPTANCE', 'RESOLVED', 'ACCEPTED', 'AUTO_CLEARED'];
  return asPersona(request, 'admin', 'ADMIN').get<Break[]>(
    `/api/v1/inventory/breaks?registryAccountId=${accountId}&${statuses.map((status) => `status=${status}`).join('&')}`,
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
  test('a break is taken, its acceptance asked for and approved by a second person', async ({ page, request }) => {
    // A statement without the newest held lot opens a break: in our books, not in the registry.
    // A lot no earlier run has had a break on: an accepted break settles that lot for good.
    const lots = await demoAccountLots(request);
    const accountId = lots[0]?.registryAccountId ?? '';
    const earlier = new Set((await allBreaks(request, accountId)).map((brk) => brk.lotId));
    const omitted = lots.filter((lot) => lot.status === 'HELD' && !earlier.has(lot.id)).at(-1);
    if (!omitted) throw new Error('No held lot without a break on the demo account');
    const created = await uploadStatement(
      request,
      omitted.registryAccountId,
      statement(lots.filter((lot) => lot.id !== omitted.id)),
    );
    const brk = created.find((candidate) => candidate.lotId === omitted.id && candidate.status === 'OPEN');
    if (!brk) throw new Error(`No break for lot ${omitted.id}`);

    await actAs(page, 'backoffice');
    await page.goto('/reconciliation');
    await expect(page.getByTestId('break-ageing')).toBeVisible();
    await page.goto(`/reconciliation/breaks/${brk.id}`);
    await expect(page.getByTestId('break-status')).toHaveText(/^open$/i);
    await expect(page.getByText('In our books, not in the registry').first()).toBeVisible();
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
