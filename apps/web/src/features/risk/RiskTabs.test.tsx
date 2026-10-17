import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import type { Schema } from '@/shared/api/types';
import { gridRowText, renderPage } from '@/test/renderPage';
import { ORIGIN, server } from '@/test/server';
import { BreachesTab } from './BreachesTab';
import { ExposuresTab } from './ExposuresTab';
import { PfeTab } from './PfeTab';

beforeAll(() => server.listen({ onUnhandledFrame: 'error' }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());

const counterparties = http.get(`${ORIGIN}/api/v1/reference/counterparties`, () =>
  HttpResponse.json([
    { id: 'c1', code: 'CP-RHEIN', name: 'Rheinstrom AG', active: true },
    { id: 'c2', code: 'CP-NORD', name: 'Nordvind AS', active: true },
  ]),
);

const exposure = (counterpartyId: string, value: number, extra: Partial<Schema<'CounterpartyExposureView'>> = {}) => ({
  counterpartyId,
  legalEntityId: 'e1',
  currency: 'EUR',
  mtm: 1200.5,
  receivables: 3000,
  payablesNetted: -500,
  pfe: 800,
  collateral: 1000,
  exposure: value,
  incomplete: false,
  incompleteReasons: [],
  leftOutCurrencies: [],
  measuredAt: '2026-10-16T06:00:00Z',
  ...extra,
});

describe('ExposuresTab', () => {
  it('lists each counterparty exposure with its parts, and flags a measurement that left a currency out', async () => {
    server.use(
      counterparties,
      http.get(`${ORIGIN}/api/v1/risk/exposures`, () =>
        HttpResponse.json([
          exposure('c1', 3500.5),
          exposure('c2', 900, { incomplete: true, leftOutCurrencies: ['NOK'], incompleteReasons: ['No NOK rate'] }),
        ]),
      ),
    );
    const { unanswered } = renderPage(<ExposuresTab entityId="e1" />, {
      url: '/risk',
      route: '/risk',
      roles: ['RISK'],
    });

    await screen.findByRole('gridcell', { name: 'Rheinstrom AG' });
    await waitFor(() => expect(gridRowText('c2')).toContain('NOK'));
    expect(gridRowText('c1')).toMatch(
      /^Rheinstrom AG \| 1,200\.50 \| 3,000\.00 \| -500\.00 \| 800\.00 \| 1,000\.00 \| 3,500\.50 \| EUR/,
    );
    expect(gridRowText('c2')).toMatch(/^Nordvind AS .* 900\.00 \| EUR \| NOK/);
    expect(unanswered()).toEqual([]);
  });

  it('says so when nothing has been measured', async () => {
    server.use(
      counterparties,
      http.get(`${ORIGIN}/api/v1/risk/exposures`, () => HttpResponse.json([])),
    );
    renderPage(<ExposuresTab entityId="e1" />, { url: '/risk', route: '/risk', roles: ['RISK'] });

    expect(await screen.findByText(/No exposure measured yet/)).toBeInTheDocument();
  });
});

describe('BreachesTab', () => {
  it('lists open breaches with their limit, and shows closed ones too once the switch is off', async () => {
    const asked: (string | null)[] = [];
    server.use(
      counterparties,
      http.get(`${ORIGIN}/api/v1/reference/legal-entities/e1/books`, () => HttpResponse.json([])),
      http.get(`${ORIGIN}/api/v1/risk/limits`, () =>
        HttpResponse.json([
          {
            id: 'l1',
            legalEntityId: 'e1',
            type: 'CREDIT_EXPOSURE',
            scope: { counterpartyId: 'c1' },
            amount: 40000,
            currency: 'EUR',
            warningPct: 75,
            status: 'ACTIVE',
            requestedBy: 'risk',
            requestedAt: '2026-10-01T08:00:00Z',
            validFrom: '2026-10-01',
          },
        ]),
      ),
      http.get(`${ORIGIN}/api/v1/risk/breaches`, ({ request }) => {
        const openOnly = new URL(request.url).searchParams.get('openOnly');
        asked.push(openOnly);
        const open = {
          id: 'b1',
          limitId: 'l1',
          openedAt: '2026-10-15T06:00:00Z',
          firstValue: 41000,
          peakValue: 43250,
          status: 'OPEN',
        };
        const closed = {
          id: 'b2',
          limitId: 'l1',
          openedAt: '2026-09-02T06:00:00Z',
          firstValue: 40500,
          peakValue: 40500,
          status: 'CLOSED',
          closedAt: '2026-09-03T06:00:00Z',
        };
        return HttpResponse.json(openOnly === 'false' ? [open, closed] : [open]);
      }),
    );
    const { unanswered } = renderPage(<BreachesTab entityId="e1" />, {
      url: '/risk',
      route: '/risk',
      roles: ['RISK'],
    });

    await waitFor(() => expect(gridRowText('b1')).toContain('Rheinstrom AG'));
    expect(gridRowText('b1')).toMatch(/Credit exposure \| Rheinstrom AG \| 40,000 EUR \| 41,000 \| 43,250/);
    expect(document.querySelector('[row-id="b2"]')).toBeNull();

    await userEvent.click(screen.getByRole('switch', { name: 'Open breaches only' }));
    await waitFor(() => expect(gridRowText('b2')).toContain('40,500'));
    expect(asked).toEqual(['true', 'false']);
    expect(unanswered()).toEqual([]);
  });
});

describe('PfeTab', () => {
  it('lists the add-ons by family and tenor, and marks the uncalibrated ones', async () => {
    server.use(
      http.get(`${ORIGIN}/api/v1/risk/pfe-add-ons`, () =>
        HttpResponse.json([
          { productFamily: 'GUARANTEE_OF_ORIGIN', maxTenorMonths: 12, pct: 5, calibrated: true },
          { productFamily: 'EMISSION_ALLOWANCE', maxTenorMonths: 9999, pct: 12.5, calibrated: false },
        ]),
      ),
    );
    const { unanswered } = renderPage(<PfeTab />, { url: '/risk', route: '/risk', roles: ['RISK'] });

    const rows = await screen.findAllByRole('row');
    expect(rows).toHaveLength(3);
    expect(rows[1]).toHaveTextContent(/12\s*5 %\s*Yes/);
    expect(within(rows[2] as HTMLElement).getByText('Longer')).toBeInTheDocument();
    expect(rows[2]).toHaveTextContent('12.5 %');
    expect(within(rows[2] as HTMLElement).getByText('Not calibrated')).toBeInTheDocument();
    expect(unanswered()).toEqual([]);
  });
});
