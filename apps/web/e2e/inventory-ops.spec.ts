import { expect, test, type APIRequestContext, type Page } from '@playwright/test';
import { asPersona } from './api';
import { actAs } from './fixtures';

/**
 * Manual inventory, registry and reconciliation operations. Each journey works on registry accounts and lots of
 * its own (created for DEMO-FI through the API) so that the demo data other journeys rely on stays as it is.
 */

interface Identified {
  id: string;
  code: string;
}

interface Account {
  id: string;
  accountNumber: string;
}

const BACK_OFFICE = 'BACK_OFFICE,REGISTRY_OPERATOR';
const today = () => new Date().toISOString().slice(0, 10);
const year = () => new Date().getUTCFullYear();
/** Upper case: profile codes are stored upper case. */
const unique = () => `${Date.now().toString(36)}${Math.floor(Math.random() * 1296).toString(36)}`.toUpperCase();

async function choose(page: Page, testId: string, option: RegExp) {
  await page.getByTestId(testId).click();
  await page.getByRole('option', { name: option }).first().click();
}

async function demoFinland(request: APIRequestContext): Promise<Identified> {
  const admin = asPersona(request, 'admin', 'ADMIN');
  const entity = (await admin.get<Identified[]>('/api/v1/reference/legal-entities')).find((e) => e.code === 'DEMO-FI');
  if (!entity) throw new Error('Demo data missing: start the jar with the dev,demo profiles');
  return entity;
}

/** A fresh account of DEMO-FI; its number sorts after the demo accounts, which screens choose by default. */
async function newAccount(
  request: APIRequestContext,
  registryCode: 'SIMULATOR' | 'UNION_REGISTRY',
  label: string,
): Promise<Account> {
  const entity = await demoFinland(request);
  const prefix = registryCode === 'SIMULATOR' ? 'SIM-ZZ-E2E' : 'EU-ZZ-E2E';
  return asPersona(request, 'admin', 'ADMIN').post<Account>('/api/v1/reference/registry-accounts', {
    registryCode,
    accountNumber: `${prefix}-${label}-${unique()}`,
    name: `E2E ${label}`,
    accountType: registryCode === 'SIMULATOR' ? 'HOLDING' : 'TRADING',
    holderType: 'LEGAL_ENTITY',
    holderId: entity.id,
  });
}

async function demoDevice(request: APIRequestContext): Promise<string> {
  const devices = await asPersona(request, 'admin', 'ADMIN').get<{ gsrn: string; country: string; active: boolean }[]>(
    '/api/v1/reference/production-devices',
  );
  const device = devices.find((candidate) => candidate.active && candidate.country === 'FI') ?? devices[0];
  if (!device) throw new Error('No production device in the demo data');
  return device.gsrn;
}

/** Last month's production received into [account] through the API, as back office. */
async function receivedLot(request: APIRequestContext, account: Account): Promise<string> {
  const entity = await demoFinland(request);
  const now = new Date();
  const month = new Date(Date.UTC(now.getUTCFullYear(), now.getUTCMonth(), 1));
  const previous = new Date(Date.UTC(now.getUTCFullYear(), now.getUTCMonth() - 1, 1));
  const lot = await asPersona(request, 'backoffice', BACK_OFFICE).post<{ id: string }>('/api/v1/inventory/lots', {
    holderEntityId: entity.id,
    registryAccountId: account.id,
    issuanceRef: `E2E-ISS-${unique()}`,
    range: { start: 1, end: 200 },
    deviceGsrn: await demoDevice(request),
    productionPeriod: { start: previous.toISOString().slice(0, 10), endExclusive: month.toISOString().slice(0, 10) },
    issueDate: today(),
    issuingBody: 'SIM',
    issuingCountry: 'FI',
    acquisitionSource: 'ISSUANCE',
  });
  return lot.id;
}

async function cancelInstruction(page: Page) {
  await expect(page).toHaveURL(/\/registry\/instructions\/[0-9a-f-]{36}$/);
  await expect(page.getByTestId('instruction-status')).toHaveText(/^pending approval$/i);
  await page.getByTestId('instruction-cancel').click();
  await page.getByTestId('reason').fill('E2E: entered to check the screen');
  await page.getByTestId('reason-submit').click();
  await expect(page.getByTestId('instruction-status')).toHaveText(/^cancelled$/i);
}

test.describe('inventory operations', () => {
  test('back office receives certificates, reserves part of the lot, releases it and corrects it', async ({
    page,
    request,
  }) => {
    const account = await newAccount(request, 'SIMULATOR', 'RCV');
    const gsrn = await demoDevice(request);
    const issuance = `E2E-ISS-${unique()}`;

    await actAs(page, 'backoffice');
    await page.goto('/inventory');
    await page.getByTestId('receive-lots').click();
    await choose(page, 'receive-account', new RegExp(account.accountNumber));
    await page.getByTestId('receive-issuance').fill(issuance);
    await page.getByTestId('receive-range-start').fill('1');
    await page.getByTestId('receive-range-end').fill('100');
    await page.getByTestId('receive-issuing-body').fill('SIM');
    await page.getByTestId('receive-issuing-country').fill('FI');
    await page.getByTestId('receive-device').fill(gsrn);
    // Closes the device suggestions, which may cover the fields below.
    await page.getByTestId('receive-issuance').click();
    await page.getByTestId('receive-submit').click();

    await expect(page).toHaveURL(/\/inventory\/lots\/[0-9a-f-]{36}$/);
    await expect(page.getByTestId('lot-title')).toContainText('100 MWh');
    await expect(page.getByTestId('lot-status')).toHaveText(/^held$/i);
    const received = page.url();

    // Reserving part of the lot splits it; the page moves to the reserved part.
    await page.getByTestId('lot-reserve').click();
    await page.getByTestId('reserve-quantity').fill('40');
    await page.getByTestId('reserve-submit').click();
    await expect(page).not.toHaveURL(received);
    await expect(page.getByTestId('lot-title')).toContainText('40 MWh');
    await expect(page.getByTestId('lot-status')).toHaveText(/^reserved$/i);

    await page.getByTestId('lot-release').click();
    await page.getByTestId('release-submit').click();
    await expect(page.getByTestId('lot-status')).toHaveText(/^held$/i);

    await page.getByTestId('lot-correct').click();
    await page.getByTestId('correct-expiry').fill(`${year() + 2}-06-30`);
    await page.getByTestId('correct-reason').fill('E2E: the registry extended the expiry');
    await page.getByTestId('correct-submit').click();
    await expect(page.getByTestId('lot-history')).toContainText('LotCorrected');

    await page.goto(received);
    await expect(page.getByTestId('lot-status')).toHaveText(/^split$/i);
    await expect(page.getByTestId('lot-reserve')).toBeHidden();
  });

  test('a lot is cancelled in the registry for a beneficiary; the instruction waits for approval', async ({
    page,
    request,
  }) => {
    const lotId = await receivedLot(request, await newAccount(request, 'SIMULATOR', 'CNL'));

    await actAs(page, 'backoffice');
    await page.goto(`/inventory/lots/${lotId}`);
    await page.getByTestId('lot-registry-cancel').click();
    await page.getByTestId('cancellation-quantity').fill('50');
    await page.getByTestId('cancellation-beneficiary').fill('Oy E2E Asiakas Ab');
    await page.getByTestId('cancellation-from').fill(`${year()}-01-01`);
    await page.getByTestId('cancellation-to').fill(`${year() + 1}-01-01`);
    await page.getByTestId('cancellation-submit').click();
    await cancelInstruction(page);
  });

  test('a trader is offered no lot operations', async ({ page, request }) => {
    const lotId = await receivedLot(request, await newAccount(request, 'SIMULATOR', 'TRD'));
    await actAs(page, 'trader');
    await page.goto(`/inventory/lots/${lotId}`);
    await expect(page.getByTestId('lot-title')).toBeVisible();
    await expect(page.getByTestId('lot-actions')).toBeHidden();
    await page.goto('/inventory');
    await expect(page.getByTestId('expiry-ladder')).toBeVisible();
    await expect(page.getByTestId('receive-lots')).toBeHidden();
  });

  test('the expiry ladder shows and the lot journal verifies', async ({ page }) => {
    await actAs(page, 'backoffice');
    await page.goto('/inventory');
    await expect(page.getByTestId('expiry-ladder')).toBeVisible();
    await page.getByTestId('verify-journal').click();
    await expect(page.getByTestId('journal-result')).toContainText('intact');
  });
});

test.describe('allowance operations', () => {
  test('a purchase is recorded and allowances move to another account after approval', async ({ page, request }) => {
    const from = await newAccount(request, 'UNION_REGISTRY', 'FROM');
    const to = await newAccount(request, 'UNION_REGISTRY', 'TO');

    await actAs(page, 'backoffice');
    await page.goto('/inventory?tab=allowances');
    await page.getByTestId('record-movement').click();
    await choose(page, 'movement-account', new RegExp(from.accountNumber));
    await choose(page, 'movement-product', /^EUA ·/);
    await page.getByTestId('movement-quantity').fill('500');
    await page.getByTestId('movement-reference').fill(`E2E-BUY-${unique()}`);
    await page.getByTestId('movement-cost').fill('70.25');
    await page.getByTestId('movement-submit').click();
    const holding = page.getByRole('gridcell', { name: from.accountNumber });
    await expect(holding).toBeVisible();

    await holding.click();
    await expect(page.getByText('Movements of EUA')).toBeVisible();
    await page.getByTestId('allowance-transfer').click();
    await choose(page, 'allowance-to', new RegExp(to.accountNumber));
    await page.getByTestId('allowance-quantity').fill('100');
    await page.getByTestId('allowance-submit').click();
    await cancelInstruction(page);
  });
});

test.describe('registry statements', () => {
  test('an imported statement is opened and reconciled from its detail', async ({ page, request }) => {
    const account = await newAccount(request, 'SIMULATOR', 'STM');
    await asPersona(request, 'backoffice', BACK_OFFICE).post('/api/v1/inventory/statements', {
      registryCode: 'SIMULATOR',
      registryAccountId: account.id,
      statementDate: today(),
      source: `e2e-${unique()}.json`,
      lines: [],
    });

    await actAs(page, 'backoffice');
    await page.goto(`/reconciliation?tab=statements&account=${account.id}`);
    await page.getByRole('gridcell', { name: 'IMPORTED' }).click();
    await expect(page.getByTestId('statement-detail')).toBeVisible();
    await expect(page.getByTestId('statement-status')).toHaveText(/^imported$/i);
    await page.getByTestId('statement-reconcile').click();
    await expect(page.getByTestId('statement-status')).toHaveText(/^reconciled$/i);
    await expect(page.getByTestId('statement-reconcile')).toBeHidden();
  });

  test('back office adds a statement format profile and edits it as JSON', async ({ page }) => {
    const profile = `E2E_${unique()}`;
    const key = `*-${profile}-GOO_HOLDINGS`;

    await actAs(page, 'backoffice');
    await page.goto('/registry?tab=formats');
    await page.getByTestId('new-format').click();
    await page.getByTestId('json-code').fill(profile);
    await page.getByTestId('json-submit').click();
    await expect(page.getByTestId(`format-row-${key}`)).toContainText('v1');

    await page.getByTestId(`format-edit-${key}`).click();
    await expect(page.getByTestId('json-code')).toBeHidden();
    await page.getByTestId('json-submit').click();
    await expect(page.getByTestId(`format-row-${key}`)).toContainText('v2');
  });
});
