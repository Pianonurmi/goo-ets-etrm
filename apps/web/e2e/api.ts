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
