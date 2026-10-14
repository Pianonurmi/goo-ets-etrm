import { MantineProvider } from '@mantine/core';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { MemoryRouter, Route, Routes } from 'react-router';
import { SessionContext } from '@/shared/auth/SessionContext';
import { EntityProvider } from '@/shared/context/EntityContext';
import type { Schema } from '@/shared/api/types';
import { ORIGIN, server } from '@/test/server';
import { TradeFormPage } from './TradeFormPage';

beforeAll(() => {
  server.listen({ onUnhandledFrame: 'error' });
  // Mantine's autosizing textarea listens for font loading, which jsdom does not have.
  Object.defineProperty(document, 'fonts', {
    configurable: true,
    value: { addEventListener: () => undefined, removeEventListener: () => undefined },
  });
});
afterEach(() => server.resetHandlers());
afterAll(() => server.close());

const me = {
  userId: 'tia',
  displayName: 'Tia',
  roles: ['TRADER'],
  legalEntityIds: [],
  allEntities: true,
  authMode: 'DEV_HEADERS',
} as unknown as Schema<'MeView'>;

const loaded = {
  id: 't1',
  tradeNumber: 'T-0001',
  legalEntityId: 'e1',
  bookId: 'b1',
  counterpartyId: 'c1',
  productFamily: 'EMISSION_ALLOWANCE',
  instrumentType: 'FORWARD',
  tradeDate: '2026-10-01',
  trader: 'tia',
  externalRef: 'REF-1',
  status: 'APPROVED',
  version: 3,
  legs: [
    {
      id: 'l1',
      legNo: 1,
      side: 'SELL',
      allowanceProductCode: 'EUA',
      quantity: 1000,
      price: 70,
      priceCurrency: 'EUR',
      priceType: 'FIXED',
      unit: 'TCO2E',
      deliveries: [
        {
          id: 'd1',
          scheduleNo: 1,
          quantity: 1000,
          deliveredQuantity: 0,
          deliveryDate: '2026-12-15',
          deliveryWindow: { start: '2026-12-15', endExclusive: '2026-12-16' },
          status: 'PENDING',
        },
      ],
    },
  ],
} as unknown as Schema<'TradeView'>;
// Someone else changed the reference and the first delivery's quantity while the form was open.
const current = {
  ...loaded,
  version: 4,
  externalRef: 'REF-2',
  legs: [
    {
      ...loaded.legs[0],
      deliveries: [{ ...loaded.legs[0]!.deliveries[0], quantity: 900 }],
    },
  ],
} as unknown as Schema<'TradeView'>;

function renderForm() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <MantineProvider>
      <QueryClientProvider client={client}>
        <SessionContext
          value={{ config: { authMode: 'DEV_HEADERS', environment: 'TEST', version: 'dev', demo: false }, me }}
        >
          <EntityProvider>
            <MemoryRouter initialEntries={['/trades/t1/amend']}>
              <Routes>
                <Route path="/trades/:tradeId/amend" element={<TradeFormPage />} />
                <Route path="/trades/:tradeId" element={<div data-testid="trade-page" />} />
              </Routes>
            </MemoryRouter>
          </EntityProvider>
        </SessionContext>
      </QueryClientProvider>
    </MantineProvider>,
  );
}

describe('amending a trade that changed in the meantime', () => {
  it('lists what changed, keeps the edits and amends the current version', async () => {
    const bodies: Record<string, unknown>[] = [];
    let served = loaded;
    server.use(
      http.get(`${ORIGIN}/api/v1/trades/t1`, () => HttpResponse.json(served)),
      http.get(`${ORIGIN}/api/v1/reference/counterparties`, () => HttpResponse.json([])),
      http.get(`${ORIGIN}/api/v1/reference/legal-entities/e1/books`, () => HttpResponse.json([])),
      http.get(`${ORIGIN}/api/v1/reference/registry-accounts`, () => HttpResponse.json([])),
      http.get(`${ORIGIN}/api/v1/products/allowances`, () => HttpResponse.json([])),
      http.get(`${ORIGIN}/api/v1/market-data/indices`, () => HttpResponse.json([])),
      http.put(`${ORIGIN}/api/v1/trades/t1`, async ({ request }) => {
        bodies.push((await request.json()) as Record<string, unknown>);
        if (bodies.length === 1) {
          served = current;
          return HttpResponse.json(
            { type: 'urn:etrm:problem:stale-version', title: 'stale-version', status: 409, detail: 'changed' },
            { status: 409 },
          );
        }
        return HttpResponse.json({ ...current, version: 5 });
      }),
    );
    const user = userEvent.setup();
    renderForm();

    // The form re-renders on every keystroke; set whole values to keep the test quick.
    fireEvent.change(await screen.findByLabelText('Reason for the amendment'), {
      target: { value: 'Price correction' },
    });
    fireEvent.change(screen.getByTestId('leg-0-price'), { target: { value: '71' } });
    await user.click(screen.getByTestId('submit-trade'));

    const alert = await screen.findByTestId('stale-version-alert');
    expect(alert).toHaveTextContent('External ref: was REF-1, now REF-2.');
    expect(alert).toHaveTextContent('Leg 1 · Deliveries #1 · Quantity: was 1000, now 900.');

    await user.click(screen.getByTestId('stale-keep-edits'));
    expect(screen.getByTestId('leg-0-price')).toHaveValue('71');
    expect(screen.getByDisplayValue('REF-2')).toBeInTheDocument();
    expect(screen.getByLabelText('Reason for the amendment')).toHaveValue('Price correction');

    await user.click(screen.getByTestId('submit-trade'));
    await waitFor(() => expect(screen.getByTestId('trade-page')).toBeInTheDocument());
    expect(bodies).toHaveLength(2);
    expect(bodies[0]).toMatchObject({ expectedVersion: 3, externalRef: 'REF-1' });
    expect(bodies[1]).toMatchObject({ expectedVersion: 4, externalRef: 'REF-2', reason: 'Price correction' });
  });
});
