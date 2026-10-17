import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import type { Schema } from '@/shared/api/types';
import { renderPage } from '@/test/renderPage';
import { ORIGIN, server } from '@/test/server';
import { TradeDetailPage } from './TradeDetailPage';

beforeAll(() => server.listen({ onUnhandledFrame: 'error' }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());

const trade = {
  id: 't1',
  tradeNumber: 'T-0042',
  legalEntityId: 'e1',
  bookId: 'b1',
  counterpartyId: 'c1',
  productFamily: 'GOO',
  instrumentType: 'FORWARD',
  tradeDate: '2026-10-01',
  trader: 'tia',
  externalRef: 'BRK-77',
  masterAgreementRef: 'EFET-2024-07',
  status: 'APPROVED',
  version: 2,
  notional: 5000,
  legs: [
    {
      id: 'l1',
      legNo: 1,
      side: 'SELL',
      criteriaSummary: 'Nordic wind, 2026',
      quantity: 2500,
      price: 2,
      priceCurrency: 'EUR',
      priceType: 'FIXED',
      unit: 'MWH',
      notional: 5000,
      deliveries: [
        {
          id: 'd1',
          scheduleNo: 1,
          quantity: 2500,
          deliveredQuantity: 1000,
          deliveryDate: '2026-12-15',
          deliveryWindow: { start: '2026-12-01', endExclusive: '2027-01-01' },
          status: 'PARTIALLY_DELIVERED',
        },
      ],
    },
  ],
} as unknown as Schema<'TradeView'>;

describe('TradeDetailPage', () => {
  it('shows the trade, its counterparty and book, and the delivery schedule of each leg', async () => {
    server.use(
      http.get(`${ORIGIN}/api/v1/trades/t1`, () => HttpResponse.json(trade)),
      http.get(`${ORIGIN}/api/v1/trades/t1/versions`, () =>
        HttpResponse.json([
          {
            version: 1,
            status: 'PENDING_APPROVAL',
            recordedBy: 'tia',
            recordedAt: '2026-10-01T09:00:00Z',
            trade: { ...trade, version: 1, status: 'PENDING_APPROVAL', externalRef: 'BRK-7' },
          },
          {
            version: 2,
            status: 'APPROVED',
            recordedBy: 'mo',
            recordedAt: '2026-10-01T10:00:00Z',
            reason: 'Broker ref corrected',
            trade,
          },
        ]),
      ),
      http.get(`${ORIGIN}/api/v1/reference/counterparties`, () =>
        HttpResponse.json([
          { id: 'c1', code: 'CP-RHEIN', name: 'Rheinstrom AG', counterpartyType: 'EXTERNAL', active: true },
        ]),
      ),
      http.get(`${ORIGIN}/api/v1/reference/legal-entities/e1/books`, () =>
        HttpResponse.json([{ id: 'b1', code: 'GOO-NORDIC', name: 'Nordic GoO', legalEntityId: 'e1', active: true }]),
      ),
    );
    const { unanswered } = renderPage(<TradeDetailPage />, {
      url: '/trades/t1',
      route: '/trades/:tradeId',
      roles: ['MIDDLE_OFFICE'],
    });

    expect(await screen.findByTestId('trade-number')).toHaveTextContent('T-0042');
    await waitFor(() => expect(document.body).toHaveTextContent('Rheinstrom AG'));
    expect(document.body).toHaveTextContent('GOO-NORDIC');
    expect(document.body).toHaveTextContent('EFET-2024-07');
    const schedule = screen.getAllByRole('row').find((row) => within(row).queryByText('Partially delivered'));
    expect(schedule).toHaveTextContent(
      /^1\s*01\/12\/2026\s*01\/01\/2027\s*15\/12\/2026\s*2,500\s*1,000\s*Partially delivered/,
    );

    await userEvent.click(screen.getByRole('tab', { name: /versions/i }));
    expect(await screen.findByText('Broker ref corrected')).toBeInTheDocument();
    expect(screen.getByText('externalRef')).toBeInTheDocument();
    expect(screen.getByText('"BRK-7"')).toBeInTheDocument();
    expect(screen.getByText('"BRK-77"')).toBeInTheDocument();
    await waitFor(() => expect(unanswered()).toEqual([]));
  });
});
