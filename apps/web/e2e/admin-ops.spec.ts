import { expect, test, type APIRequestContext, type Page } from '@playwright/test';
import { asPersona, captureDemoTrade, deliveredDemoSale } from './api';
import { actAs } from './fixtures';

interface Identified {
  id: string;
  code: string;
}

/** A short unique suffix: the same database serves several runs. */
function unique(): string {
  return Date.now().toString(36).toUpperCase();
}

function previousPeriod(): string {
  const today = new Date();
  return new Date(Date.UTC(today.getUTCFullYear(), today.getUTCMonth() - 1, 1)).toISOString().slice(0, 7);
}

/** A GS1 GSRN that passes the check digit, unique per run. */
function uniqueGsrn(): string {
  const body = `64${Date.now().toString().padStart(15, '0').slice(-15)}`;
  const sum = body
    .split('')
    .reverse()
    .reduce((total, digit, index) => total + Number(digit) * (index % 2 === 0 ? 3 : 1), 0);
  return `${body}${(10 - (sum % 10)) % 10}`;
}

async function choose(page: Page, testId: string, option: RegExp) {
  await page.getByTestId(testId).click();
  await page.getByRole('option', { name: option }).first().click();
}

async function demoEntity(request: APIRequestContext, code = 'DEMO-FI'): Promise<Identified> {
  const entity = (
    await asPersona(request, 'admin', 'ADMIN').get<Identified[]>('/api/v1/reference/legal-entities')
  ).find((candidate) => candidate.code === code);
  if (!entity) throw new Error('Demo data missing: start the jar with the dev,demo profiles');
  return entity;
}

/** A confirmed EUA forward of DEMO-FI (EMIR reportable), so it has a new-trade report; returns its number. */
async function confirmedEuaForward(request: APIRequestContext): Promise<{ tradeId: string; tradeNumber: string }> {
  const admin = asPersona(request, 'admin', 'ADMIN');
  const trader = asPersona(request, 'trader', 'TRADER');
  const entity = await demoEntity(request);
  const book = (await admin.get<Identified[]>(`/api/v1/reference/legal-entities/${entity.id}/books`)).find(
    (candidate) => candidate.code === 'DEMO-FI-TRD',
  );
  const counterparty = (await admin.get<Identified[]>('/api/v1/reference/counterparties')).find(
    (candidate) => candidate.code === 'CP-AURORA',
  );
  if (!book || !counterparty) throw new Error('Demo reference data incomplete');
  const year = new Date().getUTCMonth() >= 11 ? new Date().getUTCFullYear() + 1 : new Date().getUTCFullYear();
  const deliveryDate = `${year}-12-15`;
  const trade = await trader.post<{ id: string }>('/api/v1/trades', {
    legalEntityId: entity.id,
    bookId: book.id,
    counterpartyId: counterparty.id,
    productFamily: 'EMISSION_ALLOWANCE',
    instrumentType: 'FORWARD',
    tradeDate: new Date().toISOString().slice(0, 10),
    regulatory: { executionTimestamp: new Date(Date.now() - 60_000).toISOString(), venueMic: 'XOFF' },
    legs: [
      {
        side: 'BUY',
        allowanceProductCode: 'EUA',
        quantity: 10,
        unit: 'TCO2E',
        price: '70.00',
        priceCurrency: 'EUR',
        deliveries: [
          {
            deliveryWindow: { start: deliveryDate, endExclusive: `${year}-12-16` },
            deliveryDate,
            quantity: 10,
          },
        ],
      },
    ],
  });
  const tasks = await trader.get<{ items: { id: string }[] }>(
    `/api/v1/workflow/tasks?subjectRef=${trade.id}&type=TRADE_APPROVAL`,
  );
  const task = tasks.items[0];
  if (!task) throw new Error(`No approval task for trade ${trade.id}`);
  await asPersona(request, 'middle2', 'MIDDLE_OFFICE').post(`/api/v1/workflow/tasks/${task.id}/approve`, {});
  const backOffice = asPersona(request, 'backoffice', 'BACK_OFFICE,REGISTRY_OPERATOR');
  await backOffice.post(`/api/v1/trades/${trade.id}/confirm`, { confirmationRef: `E2E-EMIR-${trade.id.slice(-8)}` });
  const confirmed = await backOffice.get<{ tradeNumber: string }>(`/api/v1/trades/${trade.id}`);
  return { tradeId: trade.id, tradeNumber: confirmed.tradeNumber };
}

test.describe('reference data operations', () => {
  test('middle office assigns a default book, edits a legal entity and reads it as of its first version', async ({
    page,
    request,
  }) => {
    const suffix = unique();
    const code = `E2E-${suffix}`;
    const middle = asPersona(request, 'middle', 'MIDDLE_OFFICE');
    const entity = await middle.post<Identified>('/api/v1/reference/legal-entities', {
      code,
      name: `End-to-end ${suffix}`,
      country: 'FI',
      validFrom: `${new Date().getUTCFullYear()}-01-01`,
    });
    await middle.post<Identified>('/api/v1/reference/books', {
      legalEntityId: entity.id,
      code: `${code}-TRD`,
      name: 'End-to-end trading',
      bookType: 'TRADING',
    });
    const account = await middle.post<Identified>('/api/v1/reference/registry-accounts', {
      registryCode: 'UNION_REGISTRY',
      accountNumber: `EU-E2E-${suffix}`,
      name: `${code} trading`,
      accountType: 'TRADING',
      holderType: 'LEGAL_ENTITY',
      holderId: entity.id,
    });

    await actAs(page, 'middle');
    await page.goto(`/admin/reference?open=${entity.id}`);
    await expect(page.getByRole('heading', { name: `${code} · End-to-end ${suffix}` })).toBeVisible();

    await choose(page, `default-book-${account.id}`, new RegExp(`^${code}-TRD · `));
    await expect(page.getByTestId(`default-book-${account.id}`)).toHaveValue(`${code}-TRD · End-to-end trading`);

    await page.getByTestId('record-edit').click();
    await page.getByTestId('edit-name').fill(`End-to-end renamed ${suffix}`);
    // Deactivated, the test entity stays out of the header's entity menu for later runs.
    await page.getByTestId('edit-active').click({ force: true });
    await page.getByTestId('edit-save').click();
    await expect(page.getByRole('heading', { name: `${code} · End-to-end renamed ${suffix}` })).toBeVisible();
    await expect(page.getByText('Inactive', { exact: true }).first()).toBeVisible();

    await page.getByTestId('record-history').click();
    await expect(page.getByTestId('history')).toContainText('Version 2');
    await page.getByTestId('as-of-version-1').click();
    await expect(page.getByTestId('as-of-record')).toContainText(`End-to-end ${suffix}`);
    await expect(page.getByTestId('as-of-record')).not.toContainText('renamed');
  });

  test('middle office edits the terms of a counterparty', async ({ page, request }) => {
    const suffix = unique();
    const counterparty = await asPersona(request, 'middle', 'MIDDLE_OFFICE').post<Identified>(
      '/api/v1/reference/counterparties',
      { code: `CP-E2E-${suffix}`, name: `End-to-end counterparty ${suffix}`, country: 'SE', paymentTermsDays: 14 },
    );

    await actAs(page, 'middle');
    await page.goto(`/admin/reference?tab=counterparties&open=${counterparty.id}`);
    await expect(page.getByTestId('counterparty-payment-terms')).toHaveText('14 days');
    await page.getByTestId('record-edit').click();
    await page.getByTestId('edit-payment-terms').fill('30');
    await page.getByTestId('edit-invoice-email').fill(`ap-${suffix.toLowerCase()}@example.com`);
    await page.getByTestId('edit-save').click();
    await expect(page.getByTestId('counterparty-payment-terms')).toHaveText('30 days');
    await expect(page.getByTestId('counterparty-invoice-email')).toHaveText(`ap-${suffix.toLowerCase()}@example.com`);
  });

  test('back office registers a production device, changes its capacity and reads the first version', async ({
    page,
  }) => {
    const gsrn = uniqueGsrn();
    await actAs(page, 'backoffice');
    await page.goto('/admin/reference?tab=devices');
    await page.getByTestId('new-device').click();
    await page.getByTestId('device-gsrn').fill(gsrn);
    await page.getByTestId('device-name').fill(`End-to-end hydro ${gsrn.slice(-6)}`);
    await page.getByTestId('device-country').fill('FI');
    await page.getByTestId('device-capacity').fill('1500');
    await choose(page, 'device-energy-source', /^HYDRO · /);
    await choose(page, 'device-technology', /·/);
    await choose(page, 'device-owner', /^DEMO-FI · /);
    await page.getByTestId('device-save').click();

    await expect(page.getByRole('heading', { name: `${gsrn} · End-to-end hydro ${gsrn.slice(-6)}` })).toBeVisible();
    await expect(page.getByTestId('device-fact-capacity')).toHaveText(/^1\D?500$/);
    await page.getByTestId('record-edit').click();
    await page.getByTestId('device-capacity').fill('1750');
    await page.getByTestId('device-save').click();
    await expect(page.getByTestId('device-fact-capacity')).toHaveText(/^1\D?750$/);

    await page.getByTestId('record-history').click();
    await expect(page.getByTestId('history')).toContainText('Version 2');
    await page.getByTestId('as-of-version-1').click();
    await expect(page.getByTestId('as-of-record')).toContainText('1500');
  });
});

test.describe('finance and reporting operations', () => {
  test('an accountant proposes a posting rule; it waits for a second accountant', async ({ page, request }) => {
    const ruleCode = `E2E_RULE_${unique()}`;
    await actAs(page, 'accountant');
    await page.goto('/ledger?tab=rules');
    await page.getByTestId('rule-new').click();
    await page.getByTestId('rule-code').fill(ruleCode);
    // An event type nothing publishes: the rule can never post, whatever the decision.
    await page.getByTestId('rule-event-type').fill('E2eNoSuchEvent');
    await page.getByTestId('rule-description').fill('End-to-end rule proposal');
    await choose(page, 'rule-line-0-account', /·/);
    await choose(page, 'rule-line-1-account', /·/);
    await page.getByTestId('rule-propose').click();
    await expect(page.getByTestId('pending-rule-changes')).toContainText(ruleCode);

    // The controller rejects it, so the pending list does not grow from run to run.
    const controller = asPersona(request, 'controller', 'ACCOUNTANT');
    const change = (
      await controller.get<{ id: string; ruleCode: string }[]>('/api/v1/ledger/rules/changes?status=PENDING')
    ).find((candidate) => candidate.ruleCode === ruleCode);
    if (!change) throw new Error(`No pending change for ${ruleCode}`);
    const tasks = await controller.get<{ items: { id: string }[] }>(
      `/api/v1/workflow/tasks?subjectRef=${change.id}&type=POSTING_RULE_CHANGE`,
    );
    const task = tasks.items[0];
    if (!task) throw new Error(`No approval task for ${ruleCode}`);
    await controller.post(`/api/v1/workflow/tasks/${task.id}/reject`, { reason: 'End-to-end clean-up' });
  });

  test('month end exports the GL as a server CSV file, or says why not', async ({ page }) => {
    await actAs(page, 'accountant');
    await page.goto('/month-end');
    await expect(page.getByTestId('period-picker')).toHaveValue(previousPeriod());
    await page.getByRole('tab', { name: 'GL interface' }).click();
    const download = page
      .waitForEvent('download', { timeout: 20_000 })
      .then((file) => `file:${file.suggestedFilename()}`)
      .catch(() => '');
    const refusal = page
      .getByTestId('problem-alert')
      .waitFor({ timeout: 20_000 })
      .then(async () => `refused:${await page.getByTestId('problem-alert').innerText()}`)
      .catch(() => '');
    await page.getByTestId('export-csv').click();
    // The demo exported last month already: a new export needs entries posted since, otherwise the server refuses.
    const outcome = await Promise.race([download, refusal]);
    expect(outcome).toMatch(/^file:gl-demo-fi-\d{4}-\d{2}-[a-z0-9-]+\.csv$|^refused:[\s\S]*exported/i);
  });

  test('a logged report export is downloaded again from the export log', async ({ page, request }) => {
    const entity = await demoEntity(request);
    const response = await request.get(
      `/api/v1/reports/GL_TRIAL_BALANCE?format=csv&legalEntityId=${entity.id}&period=${previousPeriod()}`,
      { headers: { 'X-Actor': 'accountant', 'X-Roles': 'ACCOUNTANT' } },
    );
    expect(response.ok()).toBe(true);
    const exportId = response.headers()['x-report-export'];
    expect(exportId).toBeTruthy();

    await actAs(page, 'accountant');
    await page.goto('/reports?tab=log');
    const download = page.waitForEvent('download');
    await page.getByTestId(`export-download-${exportId}`).click();
    expect((await download).suggestedFilename()).toMatch(/^gl_trial_balance-\d+\.csv$/);
  });

  test('middle office records an EMIR rejection for a reported trade', async ({ page, request }) => {
    const { tradeId, tradeNumber } = await confirmedEuaForward(request);
    const entity = await demoEntity(request);
    const today = new Date().toISOString().slice(0, 10);
    const middle = asPersona(request, 'middle', 'MIDDLE_OFFICE');
    await expect
      .poll(async () =>
        (
          await middle.get<{ tradeId: string; action: string }[]>(
            `/api/v1/reports/emir/events?legalEntityId=${entity.id}&from=${today}&to=${today}`,
          )
        ).some((event) => event.tradeId === tradeId && event.action === 'NEWT'),
      )
      .toBe(true);
    await actAs(page, 'middle');
    await page.goto('/reports?tab=emir');
    await expect(page.getByTestId(`emir-event-${tradeNumber}-NEWT`)).toBeVisible();
    await page.getByTestId(`report-error-${tradeNumber}`).click();
    await page.getByTestId('reason').fill('Rejected by the trade repository in an end-to-end test');
    await page.getByTestId('reason-submit').click();
    await expect(page.getByTestId(`emir-event-${tradeNumber}-EROR`)).toBeVisible();
    await expect(page.getByTestId(`report-error-${tradeNumber}`)).toHaveCount(0);
  });
});

test.describe('ETS, inbox and settlement operations', () => {
  test('the EU ETS page lists the installations of the entity', async ({ page }) => {
    await actAs(page, 'middle');
    await page.goto('/ets');
    const list = page.getByTestId('installation-list');
    await expect(list).toBeVisible();
    await expect(list.locator('tbody tr').first()).toBeVisible();
    await page.getByTestId('installations-show-inactive').click({ force: true });
    await expect(list).toBeVisible();
    await list.getByRole('link').first().click();
    await expect(page).toHaveURL(/\/ets\/installations\/[0-9a-f-]{36}/);
  });

  test('a trader cancels their own approval request with a reason', async ({ page, request }) => {
    const { tradeId, taskId } = await captureDemoTrade(request, 70);
    await actAs(page, 'trader');
    await page.goto(`/inbox/${taskId}`);
    await expect(page.getByTestId('task-summary')).toBeVisible();
    await page.getByTestId('cancel-task').click();
    await page.getByTestId('reason').fill('Captured by mistake in an end-to-end test');
    await page.getByTestId('reason-submit').click();
    await expect(page.getByTestId('task-outcome')).toContainText('Cancelled');
    await expect(page.getByTestId('cancel-task')).toHaveCount(0);

    // The trade itself is withdrawn as well, so no pending trade is left without a task (best effort).
    await asPersona(request, 'trader', 'TRADER')
      .post(`/api/v1/trades/${tradeId}/cancel`, { reason: 'End-to-end clean-up' })
      .catch(() => undefined);
  });

  test('back office opens a settlement item with its pricing and VAT', async ({ page, request }) => {
    const { tradeId, tradeNumber } = await deliveredDemoSale(request, 40);
    const backOffice = asPersona(request, 'backoffice', 'BACK_OFFICE,REGISTRY_OPERATOR');
    let itemId: string | undefined;
    await expect
      .poll(async () => {
        itemId = (await backOffice.get<{ id: string }[]>(`/api/v1/settlement/items?tradeId=${tradeId}`))[0]?.id;
        return itemId;
      })
      .toBeTruthy();

    await actAs(page, 'backoffice');
    await page.goto(`/settlement?view=all&item=${itemId}`);
    await expect(page.getByTestId('item-trade')).toHaveText(tradeNumber);
    await expect(page.getByTestId('item-quantity')).toContainText('40');
    await expect(page.getByTestId('item-status')).toBeVisible();
  });
});
