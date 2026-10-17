import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import type { Schema } from '@/shared/api/types';
import { gridRowText, renderPage } from '@/test/renderPage';
import { ORIGIN, server } from '@/test/server';
import { LedgerPage } from './LedgerPage';

beforeAll(() => server.listen({ onUnhandledFrame: 'error' }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());

const CHAIN_INTACT = 'The hash chain of the journal is intact.';

const entry = (n: number, description: string, amount: number, status: string, ruleCode: string | null) =>
  ({
    id: `entry-${n}`,
    entryNumber: n,
    legalEntityId: 'e1',
    postingDate: '2026-09-30',
    period: '2026-09',
    ruleCode,
    description,
    currency: 'EUR',
    totalDebit: amount,
    totalCredit: amount,
    status,
    eventType: 'TradeSettled',
    sourceRef: `T-000${n}`,
    hash: 'h',
    createdAt: '2026-09-30T12:00:00Z',
    createdBy: 'system',
    lines: [],
  }) as unknown as Schema<'JournalEntryView'>;

const account = (code: string, name: string, type: string, active = true) =>
  ({ id: `a-${code}`, legalEntityId: 'e1', code, name, type, active }) as unknown as Schema<'GlAccountView'>;

function ledgerApi() {
  server.use(
    http.get(`${ORIGIN}/api/v1/ledger/entries`, () =>
      HttpResponse.json([
        entry(2, 'GoO sale to Rheinstrom', 12345.6, 'POSTED', 'GOO_SALE'),
        entry(1, 'Opening balance correction', 500, 'REVERSED', null),
      ]),
    ),
    http.get(`${ORIGIN}/api/v1/ledger/accounts`, () =>
      HttpResponse.json([
        account('1700', 'Receivables', 'ASSET'),
        account('3000', 'Sales of GoOs', 'REVENUE'),
        account('9999', 'Retired', 'EXPENSE', false),
      ]),
    ),
    http.get(`${ORIGIN}/api/v1/ledger/rules/changes`, () => HttpResponse.json([{ id: 'rc1' }])),
    http.get(`${ORIGIN}/api/v1/ledger/journal/verify`, () => HttpResponse.json({ ok: true, entries: 2 })),
    http.get(`${ORIGIN}/api/v1/ledger/trial-balance`, () =>
      HttpResponse.json([
        {
          accountCode: '1700',
          accountName: 'Receivables',
          type: 'ASSET',
          debit: 12345.6,
          credit: 0,
          balance: 12345.6,
          glMapping: '1700',
        },
        {
          accountCode: '3000',
          accountName: 'Sales of GoOs',
          type: 'REVENUE',
          debit: 0,
          credit: 12345.6,
          balance: -12345.6,
        },
      ] satisfies Schema<'TrialBalanceRow'>[]),
    ),
  );
}

describe('LedgerPage', () => {
  it('lists the journal in the grid, verifies the chain and counts the active accounts', async () => {
    ledgerApi();
    const { unanswered } = renderPage(<LedgerPage />, { url: '/ledger', route: '/ledger', roles: ['ACCOUNTANT'] });

    await screen.findByRole('gridcell', { name: 'GoO sale to Rheinstrom' });
    expect(gridRowText('entry-2')).toMatch(
      /^2 \| 2026-09-30 \| 2026-09 \| GOO_SALE \| GoO sale to Rheinstrom \| 12,345\.60 \| EUR \| Posted/,
    );
    expect(gridRowText('entry-1')).toContain('500.00');

    await userEvent.click(screen.getByRole('button', { name: /verify/i }));
    expect(await screen.findByTestId('verify-result')).toHaveTextContent(CHAIN_INTACT);

    const kpis = screen.getByTestId('ledger-kpis');
    await waitFor(() => expect(kpis).toHaveTextContent(/Active accounts\s*2/i));
    expect(unanswered()).toEqual([]);
  });

  it('shows the trial balance of a period with debits, credits and signed balances', async () => {
    ledgerApi();
    const { unanswered } = renderPage(<LedgerPage />, {
      url: '/ledger?tab=trial-balance',
      route: '/ledger',
      roles: ['ACCOUNTANT'],
    });

    await screen.findByRole('gridcell', { name: 'Sales of GoOs' });
    expect(gridRowText('1700')).toMatch(/^1700 \| Receivables \| Asset \| 12,345\.60 \| 0\.00 \| 12,345\.60 \| 1700/);
    expect(gridRowText('3000')).toMatch(/^3000 \| Sales of GoOs \| Revenue \| 0\.00 \| 12,345\.60 \| -12,345\.60/);
    expect(unanswered()).toEqual([]);
  });
});
