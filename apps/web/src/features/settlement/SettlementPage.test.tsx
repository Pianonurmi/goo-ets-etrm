import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import type { Schema } from '@/shared/api/types';
import { gridRowText, renderPage } from '@/test/renderPage';
import { ORIGIN, server } from '@/test/server';
import { SettlementPage } from './SettlementPage';

beforeAll(() => server.listen({ onUnhandledFrame: 'error' }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());

const item = (n: number, deliveredOn: string) =>
  ({
    id: `item-${n}`,
    legalEntityId: 'e1',
    counterpartyId: 'c1',
    tradeId: `t${n}`,
    tradeNumber: `T-000${n}`,
    scheduleId: `s${n}`,
    deliveryId: `d${n}`,
    productFamily: 'GOO',
    direction: 'SALE',
    description: 'GoO delivery',
    quantity: 1000 * n,
    unit: 'MWH',
    unitPrice: 2,
    netAmount: 2000 * n,
    currency: 'EUR',
    deliveredOn,
    status: 'OPEN',
    version: 1,
  }) as unknown as Schema<'SettlementItemView'>;

describe('SettlementPage', () => {
  it('pages the settlement items, latest delivery first, and loads the next page on request', async () => {
    const cursors: (string | null)[] = [];
    server.use(
      http.get(`${ORIGIN}/api/v1/settlement/items/search`, ({ request }) => {
        const cursor = new URL(request.url).searchParams.get('cursor');
        cursors.push(cursor);
        return HttpResponse.json(
          cursor === null
            ? { items: [item(3, '2026-10-03'), item(2, '2026-10-02')], nextCursor: 'page-2' }
            : { items: [item(1, '2026-10-01')], nextCursor: null },
        );
      }),
      http.get(`${ORIGIN}/api/v1/settlement/items`, () => HttpResponse.json([])),
      http.get(`${ORIGIN}/api/v1/settlement/invoices`, () => HttpResponse.json([])),
      http.get(`${ORIGIN}/api/v1/reference/counterparties`, () =>
        HttpResponse.json([
          { id: 'c1', code: 'CP-RHEIN', name: 'Rheinstrom AG', counterpartyType: 'EXTERNAL', active: true },
        ]),
      ),
    );
    const { unanswered } = renderPage(<SettlementPage />, {
      url: '/settlement?view=all',
      route: '/settlement',
      roles: ['BACK_OFFICE'],
    });

    await screen.findByRole('gridcell', { name: 'T-0003' });
    expect(screen.getByTestId('item-count')).toHaveTextContent('2 shown');
    expect(gridRowText('item-3')).toMatch(/^T-0003 \| Open \| Rheinstrom AG \| {2}\| 6,000\.00 EUR \| 2026-10-03/);

    await userEvent.click(screen.getByRole('button', { name: 'Load more' }));
    await screen.findByRole('gridcell', { name: 'T-0001' });
    expect(screen.getByTestId('item-count')).toHaveTextContent('3 shown');
    expect(screen.queryByRole('button', { name: 'Load more' })).toBeNull();
    expect(cursors).toEqual([null, 'page-2']);
    await waitFor(() => expect(unanswered()).toEqual([]));
  });
});
