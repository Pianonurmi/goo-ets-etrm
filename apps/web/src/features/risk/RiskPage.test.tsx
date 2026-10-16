import { screen, waitFor } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import type { Schema } from '@/shared/api/types';
import { gridRowText, renderPage } from '@/test/renderPage';
import { ORIGIN, server } from '@/test/server';
import { RiskPage } from './RiskPage';

beforeAll(() => server.listen({ onUnhandledFrame: 'error' }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());

const limit = (id: string, type: string, amount: number, scope: object = {}) =>
  ({
    id,
    legalEntityId: 'e1',
    type,
    scope,
    amount,
    currency: 'EUR',
    warningPct: 75,
    status: 'ACTIVE',
    reason: 'Board mandate',
    requestedBy: 'risk',
    requestedAt: '2026-10-01T08:00:00Z',
    validFrom: '2026-10-01',
  }) as unknown as Schema<'RiskLimitView'>;

const utilisation = (limitId: string, status: string, value: number, utilisationPct: number) =>
  ({
    limitId,
    status,
    value,
    utilisation: utilisationPct,
    measuredAt: '2026-10-16T06:00:00Z',
    incomplete: false,
    incompleteReasons: [],
    leftOutCurrencies: [],
  }) as unknown as Schema<'LimitUtilisationView'>;

describe('RiskPage', () => {
  it('lists the limits in the grid with their latest utilisation, and counts them in the KPIs', async () => {
    server.use(
      http.get(`${ORIGIN}/api/v1/risk/limits`, () =>
        HttpResponse.json([
          limit('l1', 'CREDIT_EXPOSURE', 40000, { counterpartyId: 'c1' }),
          limit('l2', 'STOP_LOSS_MTD', 250000),
        ]),
      ),
      http.get(`${ORIGIN}/api/v1/risk/limits/l1/utilisation`, () =>
        HttpResponse.json(utilisation('l1', 'WARNING', 32400, 81)),
      ),
      http.get(`${ORIGIN}/api/v1/risk/limits/l2/utilisation`, () =>
        HttpResponse.json(utilisation('l2', 'BREACH', 260000, 104)),
      ),
      http.get(`${ORIGIN}/api/v1/risk/breaches`, () => HttpResponse.json([])),
      http.get(`${ORIGIN}/api/v1/reference/counterparties`, () =>
        HttpResponse.json([{ id: 'c1', code: 'CP-RHEIN', name: 'Rheinstrom AG', active: true }]),
      ),
      http.get(`${ORIGIN}/api/v1/reference/legal-entities/e1/books`, () => HttpResponse.json([])),
    );
    const { unanswered } = renderPage(<RiskPage />, { url: '/risk', route: '/risk', roles: ['RISK'] });

    await screen.findByRole('gridcell', { name: 'Rheinstrom AG' });
    await waitFor(() => expect(gridRowText('l2')).toContain('104 %'));
    expect(gridRowText('l1')).toMatch(
      /^Credit exposure \| Rheinstrom AG \| 40,000 EUR \| 75 \| 81 % \| 32,400 \| Active/,
    );
    expect(gridRowText('l2')).toMatch(
      /^Stop loss \(month to date\) \| Whole entity \| 250,000 EUR \| 75 \| 104 % \| 260,000/,
    );

    // One limit in breach, one past its warning level, the highest at 104 %.
    const kpis = screen.getByTestId('risk-kpis');
    expect(kpis).toHaveTextContent(/Limits in breach\s*1/);
    expect(kpis).toHaveTextContent(/Near their limit\s*1/);
    expect(kpis).toHaveTextContent(/Highest utilisation\s*104\s*%/);
    expect(kpis).toHaveTextContent('2 of 2 measured');
    expect(unanswered()).toEqual([]);
  });
});
