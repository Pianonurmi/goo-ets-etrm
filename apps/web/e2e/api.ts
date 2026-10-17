import type { APIRequestContext } from '@playwright/test';

/** API calls as a dev persona, for setting up a journey (the demo data provides the reference data). */
export function asPersona(request: APIRequestContext, actor: string, roles: string) {
  const headers = { 'X-Actor': actor, 'X-Roles': roles };
  return {
    async get<T>(path: string): Promise<T> {
      const response = await request.get(path, { headers });
      if (!response.ok()) throw new Error(`GET ${path}: ${response.status()} ${await response.text()}`);
      return (await response.json()) as T;
    },
    async post<T>(path: string, data: unknown): Promise<T> {
      const response = await request.post(path, { headers, data });
      if (!response.ok()) throw new Error(`POST ${path}: ${response.status()} ${await response.text()}`);
      return (await response.json()) as T;
    },
    async postText<T>(path: string, text: string): Promise<T> {
      const response = await request.post(path, { headers: { ...headers, 'Content-Type': 'text/csv' }, data: text });
      if (!response.ok()) throw new Error(`POST ${path}: ${response.status()} ${await response.text()}`);
      return (await response.json()) as T;
    },
  };
}

interface Identified {
  id: string;
  code: string;
}

/**
 * Captures a Nordic hydro GoO sale for DEMO-FI as [capturedBy] (a trader, or middle office capturing on a trader's
 * behalf); returns the trade id and its approval task.
 */
export async function captureDemoTrade(
  request: APIRequestContext,
  quantity = 120,
  capturedBy: { actor: string; roles: string } = { actor: 'trader', roles: 'TRADER' },
): Promise<{ tradeId: string; taskId: string }> {
  const trader = asPersona(request, capturedBy.actor, capturedBy.roles);
  const admin = asPersona(request, 'admin', 'ADMIN');
  const entity = (await admin.get<Identified[]>('/api/v1/reference/legal-entities')).find((e) => e.code === 'DEMO-FI');
  if (!entity) throw new Error('Demo data missing: start the jar with the dev,demo profiles');
  const book = (await admin.get<Identified[]>(`/api/v1/reference/legal-entities/${entity.id}/books`)).find(
    (b) => b.code === 'DEMO-FI-TRD',
  );
  const counterparty = (await admin.get<Identified[]>('/api/v1/reference/counterparties')).find(
    (c) => c.code === 'CP-AURORA',
  );
  const account = (
    await admin.get<Identified[]>(`/api/v1/reference/registry-accounts?holderType=LEGAL_ENTITY&holderId=${entity.id}`)
  )[0];
  if (!book || !counterparty || !account) throw new Error('Demo reference data incomplete');
  const year = new Date().getUTCMonth() >= 11 ? new Date().getUTCFullYear() + 1 : new Date().getUTCFullYear();
  const tomorrow = new Date(Date.now() + 86_400_000).toISOString().slice(0, 10);
  const trade = await trader.post<{ id: string }>('/api/v1/trades', {
    legalEntityId: entity.id,
    bookId: book.id,
    counterpartyId: counterparty.id,
    productFamily: 'GUARANTEE_OF_ORIGIN',
    instrumentType: 'FORWARD',
    tradeDate: new Date().toISOString().slice(0, 10),
    legs: [
      {
        side: 'SELL',
        criterion: {
          type: 'all',
          of: [
            { type: 'countryInRegion', region: 'NORDIC' },
            { type: 'descendantOf', attribute: 'ENERGY_SOURCE', code: 'HYDRO' },
          ],
        },
        quantity,
        unit: 'MWH',
        price: '2.10',
        priceCurrency: 'EUR',
        deliveryAccountId: account.id,
        counterpartyAccountRef: 'SIMULATOR:SIM-EXT-E2E',
        deliveries: [
          {
            deliveryWindow: { start: tomorrow, endExclusive: `${year + 1}-01-01` },
            deliveryDate: `${year}-12-15`,
            quantity,
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
  return { tradeId: trade.id, taskId: task.id };
}

/**
 * A demo GoO sale to Aurora, approved by `middle2` and confirmed by back office, so its delivery is open; returns
 * the trade id, number and delivery schedule.
 */
export async function confirmedDemoSale(
  request: APIRequestContext,
  quantity = 80,
): Promise<{ tradeId: string; tradeNumber: string; scheduleId: string }> {
  const { tradeId, taskId } = await captureDemoTrade(request, quantity);
  await asPersona(request, 'middle2', 'MIDDLE_OFFICE').post(`/api/v1/workflow/tasks/${taskId}/approve`, {});
  const backOffice = asPersona(request, 'backoffice', 'BACK_OFFICE,REGISTRY_OPERATOR');
  await backOffice.post(`/api/v1/trades/${tradeId}/confirm`, { confirmationRef: `E2E-${tradeId.slice(-8)}` });
  const trade = await backOffice.get<{ tradeNumber: string; legs: { deliveries: { id: string }[] }[] }>(
    `/api/v1/trades/${tradeId}`,
  );
  const scheduleId = trade.legs[0]?.deliveries[0]?.id;
  if (!scheduleId) throw new Error(`Trade ${tradeId} has no delivery schedule`);
  return { tradeId, tradeNumber: trade.tradeNumber, scheduleId };
}

/**
 * A demo GoO sale to Aurora, approved, confirmed and delivered today, so it waits as an open settlement item;
 * returns the trade id and number.
 */
export async function deliveredDemoSale(
  request: APIRequestContext,
  quantity = 80,
): Promise<{ tradeId: string; tradeNumber: string }> {
  const { tradeId, tradeNumber, scheduleId } = await confirmedDemoSale(request, quantity);
  const backOffice = asPersona(request, 'backoffice', 'BACK_OFFICE,REGISTRY_OPERATOR');
  await backOffice.post(`/api/v1/trades/${tradeId}/deliveries`, {
    scheduleId,
    quantity,
    deliveredOn: new Date().toISOString().slice(0, 10),
    idempotencyKey: `e2e-delivery:${tradeId}`,
  });
  return { tradeId, tradeNumber };
}

interface DraftInvoice {
  id: string;
  lines: { description: string }[];
}

/**
 * A delivered demo sale invoiced and issued through four eyes (submitted by `accountant`, approved by `controller`);
 * returns the invoice id, number and gross amount.
 */
export async function issuedDemoInvoice(
  request: APIRequestContext,
  quantity = 60,
): Promise<{ invoiceId: string; number: string; grossTotal: number }> {
  const { tradeNumber } = await deliveredDemoSale(request, quantity);
  const accountant = asPersona(request, 'accountant', 'ACCOUNTANT');
  const admin = asPersona(request, 'admin', 'ADMIN');
  const entity = (await admin.get<Identified[]>('/api/v1/reference/legal-entities')).find((e) => e.code === 'DEMO-FI');
  const aurora = (await admin.get<Identified[]>('/api/v1/reference/counterparties')).find(
    (c) => c.code === 'CP-AURORA',
  );
  if (!entity || !aurora) throw new Error('Demo reference data incomplete');
  const drafts = await accountant.post<DraftInvoice[]>('/api/v1/settlement/invoice-runs', {
    legalEntityId: entity.id,
    upTo: new Date().toISOString().slice(0, 10),
    counterpartyId: aurora.id,
  });
  const draft = drafts.find((invoice) => invoice.lines.some((line) => line.description.includes(tradeNumber)));
  if (!draft) throw new Error(`No draft invoice for ${tradeNumber}`);
  await accountant.post(`/api/v1/settlement/invoices/${draft.id}/submit`, {});
  const controller = asPersona(request, 'controller', 'ACCOUNTANT');
  const tasks = await controller.get<{ items: { id: string }[] }>(
    `/api/v1/workflow/tasks?subjectRef=${draft.id}&type=INVOICE_APPROVAL&status=OPEN`,
  );
  const task = tasks.items[0];
  if (!task) throw new Error(`No approval task for invoice ${draft.id}`);
  await controller.post(`/api/v1/workflow/tasks/${task.id}/approve`, {});
  const issued = await controller.get<{ number: string; grossTotal: number }>(
    `/api/v1/settlement/invoices/${draft.id}`,
  );
  return { invoiceId: draft.id, number: issued.number, grossTotal: issued.grossTotal };
}

/** A suffix that makes codes and references unique, so a journey can run again on the same database. */
export function unique(): string {
  return `${Date.now().toString(36)}${Math.floor(Math.random() * 1296).toString(36)}`.toUpperCase();
}

interface DemoContext {
  entityId: string;
  bookId: string;
  accountId: string;
}

/** The demo entity DEMO-FI with its trading book and GoO account. */
export async function demoContext(request: APIRequestContext): Promise<DemoContext> {
  const admin = asPersona(request, 'admin', 'ADMIN');
  const entity = (await admin.get<Identified[]>('/api/v1/reference/legal-entities')).find((e) => e.code === 'DEMO-FI');
  if (!entity) throw new Error('Demo data missing: start the jar with the dev,demo profiles');
  const book = (await admin.get<Identified[]>(`/api/v1/reference/legal-entities/${entity.id}/books`)).find(
    (b) => b.code === 'DEMO-FI-TRD',
  );
  const account = (
    await admin.get<(Identified & { accountNumber: string })[]>(
      `/api/v1/reference/registry-accounts?holderType=LEGAL_ENTITY&holderId=${entity.id}`,
    )
  ).find((a) => a.accountNumber === 'SIM-DEMO-FI');
  if (!book || !account) throw new Error('Demo reference data incomplete');
  return { entityId: entity.id, bookId: book.id, accountId: account.id };
}

/**
 * A new Norwegian counterparty we net with, created by middle office like the demo's Nordvind (outside the EU, so
 * neither side's invoice carries VAT); returns its id and code.
 */
export async function nettingCounterparty(request: APIRequestContext): Promise<{ id: string; code: string }> {
  const suffix = unique();
  return asPersona(request, 'middle', 'MIDDLE_OFFICE').post<Identified>('/api/v1/reference/counterparties', {
    code: `CP-E2E-NET-${suffix}`,
    name: `End-to-end netting ${suffix}`,
    country: 'NO',
    vatNumber: `NO${String(Date.now()).slice(-9)}MVA`,
    nettingAgreed: true,
    settlementCurrency: 'EUR',
    paymentTermsDays: 14,
  });
}

/**
 * A Nordic hydro GoO spot trade with [counterpartyId], approved, confirmed and delivered today in full, as the demo
 * seeder records them; returns the trade id and number.
 */
export async function deliveredGoo(
  request: APIRequestContext,
  {
    counterpartyId,
    side,
    quantity,
    price,
  }: { counterpartyId: string; side: 'BUY' | 'SELL'; quantity: number; price: string },
): Promise<{ tradeId: string; tradeNumber: string }> {
  const demo = await demoContext(request);
  const today = new Date().toISOString().slice(0, 10);
  const tomorrow = new Date(Date.now() + 86_400_000).toISOString().slice(0, 10);
  const trade = await asPersona(request, 'trader', 'TRADER').post<{ id: string }>('/api/v1/trades', {
    legalEntityId: demo.entityId,
    bookId: demo.bookId,
    counterpartyId,
    productFamily: 'GUARANTEE_OF_ORIGIN',
    instrumentType: 'SPOT',
    tradeDate: today,
    legs: [
      {
        side,
        criterion: {
          type: 'all',
          of: [
            { type: 'countryInRegion', region: 'NORDIC' },
            { type: 'descendantOf', attribute: 'ENERGY_SOURCE', code: 'HYDRO' },
          ],
        },
        quantity,
        unit: 'MWH',
        price,
        priceCurrency: 'EUR',
        deliveryAccountId: demo.accountId,
        counterpartyAccountRef: `SIMULATOR:SIM-EXT-${counterpartyId.slice(-6)}`,
        deliveries: [{ deliveryWindow: { start: today, endExclusive: tomorrow }, deliveryDate: today, quantity }],
      },
    ],
  });
  await approveTrade(request, trade.id);
  const backOffice = asPersona(request, 'backoffice', 'BACK_OFFICE,REGISTRY_OPERATOR');
  await backOffice.post(`/api/v1/trades/${trade.id}/confirm`, { confirmationRef: `E2E-${trade.id.slice(-8)}` });
  const confirmed = await backOffice.get<{ tradeNumber: string; legs: { deliveries: { id: string }[] }[] }>(
    `/api/v1/trades/${trade.id}`,
  );
  await backOffice.post(`/api/v1/trades/${trade.id}/deliveries`, {
    scheduleId: confirmed.legs[0]?.deliveries[0]?.id,
    quantity,
    deliveredOn: today,
    idempotencyKey: `e2e-delivery:${trade.id}`,
  });
  return { tradeId: trade.id, tradeNumber: confirmed.tradeNumber };
}

/**
 * Approves a trade's approval task as the role it asks for: middle office, or risk when the pre-trade checks sent it
 * there (a limit the demo's exposure has grown into on a database the suite ran on before).
 */
export async function approveTrade(request: APIRequestContext, tradeId: string): Promise<void> {
  // The task always offers middle office and risk; a trade whose checks found a limit to exceed takes risk's.
  const trade = await asPersona(request, 'middle2', 'MIDDLE_OFFICE').get<{ requiresRiskApproval: boolean }>(
    `/api/v1/trades/${tradeId}`,
  );
  if (trade.requiresRiskApproval) await approveTask(request, 'risk2', 'RISK', 'TRADE_APPROVAL', tradeId);
  else await approveTask(request, 'middle2', 'MIDDLE_OFFICE', 'TRADE_APPROVAL', tradeId);
}

/** Approves the open task of [type] on [subjectRef] as [actor]. */
export async function approveTask(
  request: APIRequestContext,
  actor: string,
  roles: string,
  type: string,
  subjectRef: string,
): Promise<void> {
  const decider = asPersona(request, actor, roles);
  const tasks = await decider.get<{ items: { id: string }[] }>(
    `/api/v1/workflow/tasks?subjectRef=${subjectRef}&type=${type}&status=OPEN`,
  );
  const task = tasks.items[0];
  if (!task) throw new Error(`No open ${type} task for ${subjectRef}`);
  await decider.post(`/api/v1/workflow/tasks/${task.id}/approve`, {});
}

/** Invoices what was delivered to [counterpartyId] and issues it (accountant submits, controller approves). */
export async function issuedInvoiceFor(request: APIRequestContext, counterpartyId: string): Promise<string> {
  const demo = await demoContext(request);
  const accountant = asPersona(request, 'accountant', 'ACCOUNTANT');
  const drafts = await accountant.post<DraftInvoice[]>('/api/v1/settlement/invoice-runs', {
    legalEntityId: demo.entityId,
    upTo: new Date().toISOString().slice(0, 10),
    counterpartyId,
  });
  const draft = drafts[0];
  if (!draft || drafts.length !== 1)
    throw new Error(`Expected one draft invoice for ${counterpartyId}, got ${drafts.length}`);
  await accountant.post(`/api/v1/settlement/invoices/${draft.id}/submit`, {});
  await approveTask(request, 'controller', 'ACCOUNTANT', 'INVOICE_APPROVAL', draft.id);
  return draft.id;
}

/**
 * A netting statement in draft with a new counterparty: a 300 MWh sale at 1.95 and a 200 MWh purchase at 1.70,
 * both invoiced and issued, as in the demo; returns the statement id and the counterparty's code.
 */
export async function draftNetting(
  request: APIRequestContext,
): Promise<{ nettingId: string; counterpartyCode: string }> {
  const demo = await demoContext(request);
  const counterparty = await nettingCounterparty(request);
  await deliveredGoo(request, { counterpartyId: counterparty.id, side: 'SELL', quantity: 300, price: '1.95' });
  await issuedInvoiceFor(request, counterparty.id);
  await deliveredGoo(request, { counterpartyId: counterparty.id, side: 'BUY', quantity: 200, price: '1.70' });
  const accountant = asPersona(request, 'accountant', 'ACCOUNTANT');
  const purchase = await accountant.post<{ id: string }>('/api/v1/settlement/purchase-invoices', {
    legalEntityId: demo.entityId,
    counterpartyId: counterparty.id,
    supplierInvoiceRef: `NV-${counterparty.code}`,
    invoiceDate: new Date().toISOString().slice(0, 10),
    currency: 'EUR',
    netAmount: 340,
    vatAmount: 0,
  });
  await accountant.post(`/api/v1/settlement/invoices/${purchase.id}/submit`, {});
  await approveTask(request, 'controller', 'ACCOUNTANT', 'INVOICE_APPROVAL', purchase.id);
  const valueDate = new Date(Date.now() + 30 * 86_400_000).toISOString().slice(0, 10);
  const netting = await accountant.post<{ id: string }>('/api/v1/settlement/netting', {
    legalEntityId: demo.entityId,
    counterpartyId: counterparty.id,
    currency: 'EUR',
    valueDate,
  });
  return { nettingId: netting.id, counterpartyCode: counterparty.code };
}

/**
 * An approved demo sale whose counterparty confirms a higher price than ours, as in the demo: the confirmation is
 * mismatched and waits for middle office; returns its id and the trade's.
 */
export async function mismatchedConfirmation(
  request: APIRequestContext,
): Promise<{ confirmationId: string; tradeId: string }> {
  const quantity = 90;
  const { tradeId, taskId } = await captureDemoTrade(request, quantity);
  const middle = asPersona(request, 'middle2', 'MIDDLE_OFFICE');
  await middle.post(`/api/v1/workflow/tasks/${taskId}/approve`, {});
  const ours = (
    await middle.get<{ direction: string; terms: { legs: Record<string, unknown>[] } & Record<string, unknown> }[]>(
      `/api/v1/trades/${tradeId}/confirmations`,
    )
  ).find((confirmation) => confirmation.direction === 'OURS');
  const leg = ours?.terms.legs[0];
  if (!ours || !leg) throw new Error(`Trade ${tradeId} has no confirmation of ours`);
  // Received by `middle`, so the override is decided by someone else (`middle2`).
  const theirs = await asPersona(request, 'middle', 'MIDDLE_OFFICE').post<{ id: string }>(
    '/api/v1/confirmations/counterparty',
    {
      terms: { ...ours.terms, legs: [{ ...leg, price: '2.20', amount: (quantity * 2.2).toFixed(2) }] },
      reference: `AURORA-${tradeId.slice(-8)}`,
      source: 'MANUAL',
    },
  );
  return { confirmationId: theirs.id, tradeId };
}

/** The last weekday before today, as the clearing member's statements are dated. */
function lastBusinessDay(): string {
  const day = new Date(Date.now() - 86_400_000);
  while (day.getUTCDay() === 0 || day.getUTCDay() === 6) day.setUTCDate(day.getUTCDate() - 1);
  return day.toISOString().slice(0, 10);
}

/** The demo's clearing account of DEMO-FI (journeys add accounts of their own, so the page is told which). */
export async function demoClearingAccount(request: APIRequestContext): Promise<string> {
  const demo = await demoContext(request);
  const account = (
    await asPersona(request, 'backoffice', 'BACK_OFFICE').get<Identified[]>(
      `/api/v1/clearing/accounts?legalEntityId=${demo.entityId}`,
    )
  ).find((a) => a.code === 'ECC-DEMO-FI');
  if (!account) throw new Error('Demo data missing: no clearing account ECC-DEMO-FI');
  return account.id;
}

/**
 * A clearing account of DEMO-FI at a new clearing house, so nothing of the demo's is margined on it: the day's margin
 * run is computed (no futures, so nothing), then the member's statement of that day reports a position and variation
 * margin, which opens a break, and cash 1,250.00 short of the initial margin, which opens a margin call. Imported by
 * back office; returns the account and the break.
 */
export async function clearingBreak(request: APIRequestContext): Promise<{ accountId: string; breakId: string }> {
  const demo = await demoContext(request);
  const suffix = unique();
  const house = await asPersona(request, 'middle', 'MIDDLE_OFFICE').post<Identified>(
    '/api/v1/reference/counterparties',
    {
      code: `CP-E2E-CCP-${suffix}`,
      name: `End-to-end clearing house ${suffix}`,
      country: 'DE',
      counterpartyType: 'CLEARING_HOUSE',
    },
  );
  const backOffice = asPersona(request, 'backoffice', 'BACK_OFFICE');
  const account = await backOffice.post<Identified>('/api/v1/clearing/accounts', {
    code: `E2E-${suffix}`,
    legalEntityId: demo.entityId,
    clearingHouseId: house.id,
    clearingMember: 'End-to-end Clearing Bank (fictitious)',
    currency: 'EUR',
  });
  const date = lastBusinessDay();
  await backOffice.post(`/api/v1/clearing/accounts/${account.id}/margin`, { businessDate: date });
  const imported = await backOffice.postText<{ breaks: { id: string }[] }>(
    `/api/v1/clearing/accounts/${account.id}/statements?source=e2e-${suffix}.csv`,
    [
      'Date,Account,Record,Contract,Position,SettlementPrice,Amount,Currency',
      `${date},${account.code},VM,EUAZ-E2E,2000,70.00,-412.50,EUR`,
      `${date},${account.code},IM,,,,5000.00,EUR`,
      `${date},${account.code},CASH,,,,3750.00,EUR`,
    ].join('\n'),
  );
  const opened = imported.breaks[0];
  if (!opened) throw new Error(`The statement of ${account.code} opened no break`);
  return { accountId: account.id, breakId: opened.id };
}

/**
 * An EUA forward bought from a new counterparty for the date of the demo's hedged surrender, approved, and designated
 * by the accountant as the hedge of that purchase; the designation waits for a second accountant. Returns the hedge.
 */
export async function pendingHedge(request: APIRequestContext): Promise<{ hedgeId: string }> {
  const demo = await demoContext(request);
  const accountant = asPersona(request, 'accountant', 'ACCOUNTANT');
  const demoHedge = (
    await accountant.get<{ item: { expectedDate: string } }[]>(`/api/v1/ledger/hedges?legalEntityId=${demo.entityId}`)
  )[0];
  if (!demoHedge) throw new Error('Demo data missing: no hedge to take the surrender date from');
  const delivery = demoHedge.item.expectedDate;
  const next = new Date(`${delivery}T00:00:00Z`);
  next.setUTCDate(next.getUTCDate() + 1);
  const suffix = unique();
  const seller = await asPersona(request, 'middle', 'MIDDLE_OFFICE').post<Identified>(
    '/api/v1/reference/counterparties',
    {
      code: `CP-E2E-EUA-${suffix}`,
      name: `End-to-end allowance seller ${suffix}`,
      country: 'DE',
    },
  );
  const tonnes = 500;
  const trade = await asPersona(request, 'trader', 'TRADER').post<{ id: string }>('/api/v1/trades', {
    legalEntityId: demo.entityId,
    bookId: demo.bookId,
    counterpartyId: seller.id,
    productFamily: 'EMISSION_ALLOWANCE',
    instrumentType: 'FORWARD',
    tradeDate: new Date().toISOString().slice(0, 10),
    legs: [
      {
        side: 'BUY',
        allowanceProductCode: 'EUA',
        quantity: tonnes,
        unit: 'TCO2E',
        price: '70.00',
        priceCurrency: 'EUR',
        deliveries: [
          {
            deliveryWindow: { start: delivery, endExclusive: next.toISOString().slice(0, 10) },
            deliveryDate: delivery,
            quantity: tonnes,
          },
        ],
      },
    ],
  });
  await approveTrade(request, trade.id);
  const hedge = await accountant.post<{ id: string }>('/api/v1/ledger/hedges', {
    legalEntityId: demo.entityId,
    objective: `Fix the price of ${tonnes} t of allowances bought for the surrender (${suffix})`,
    item: {
      direction: 'PURCHASE',
      productFamily: 'EMISSION_ALLOWANCE',
      allowanceProductCode: 'EUA',
      quantity: tonnes,
      expectedDate: delivery,
      currency: 'EUR',
    },
    instruments: [{ tradeId: trade.id, proportion: 1 }],
  });
  return { hedgeId: hedge.id };
}
