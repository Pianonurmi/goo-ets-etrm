import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { vi } from 'vitest';
import { gridRowText, renderPage } from '@/test/renderPage';
import { ORIGIN, server } from '@/test/server';
import { EntryDrawer } from './EntryDrawer';
import { RulesTab } from './RulesTab';

/** Types [text] into [input] in one go: per-key typing re-renders the form for every character. */
async function fill(input: HTMLElement, text: string) {
  await userEvent.click(input);
  await userEvent.paste(text);
}

beforeAll(() => server.listen({ onUnhandledFrame: 'error' }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());

const entry = (extra: object = {}) => ({
  id: 'j1',
  entryNumber: 'JE-2026-000042',
  legalEntityId: 'e1',
  status: 'POSTED',
  description: 'GoO sale delivered',
  postingDate: '2026-10-01',
  period: '2026-10',
  ruleCode: 'GOO_SALE_DELIVERED',
  eventType: 'DeliveryRecorded',
  sourceType: 'TRADE',
  sourceRef: 'T-0042',
  createdBy: 'system',
  createdAt: '2026-10-01T10:00:00Z',
  hash: '0123456789abcdef0123456789abcdef',
  currency: 'EUR',
  totalDebit: 2100,
  totalCredit: 2100,
  lines: [
    {
      lineNo: 1,
      accountCode: '1700',
      accountName: 'Receivables',
      debit: 2100,
      credit: 0,
      dimensions: { trade: 'T-0042' },
    },
    { lineNo: 2, accountCode: '3000', accountName: 'Revenue', debit: 0, credit: 2100, memo: 'Sale', dimensions: {} },
  ],
  ...extra,
});

describe('EntryDrawer', () => {
  it('shows the entry and its lines, and reverses it with a reason', async () => {
    let reversal: unknown;
    server.use(
      http.get(`${ORIGIN}/api/v1/ledger/entries/j1`, () => HttpResponse.json(entry())),
      http.post(`${ORIGIN}/api/v1/ledger/entries/j1/reverse`, async ({ request }) => {
        reversal = await request.json();
        return HttpResponse.json(entry({ id: 'j2', entryNumber: 'JE-2026-000043', reversalOf: 'j1' }));
      }),
    );
    const { unanswered } = renderPage(<EntryDrawer entryId="j1" onClose={() => undefined} onOpen={() => undefined} />, {
      url: '/ledger',
      route: '/ledger',
      roles: ['ACCOUNTANT'],
    });

    const lines = await screen.findByTestId('entry-lines');
    const rows = within(lines).getAllByRole('row');
    expect(rows[1]).toHaveTextContent(/1700 · Receivables\s*2,100\.00\s*trade=T-0042/);
    expect(rows[2]).toHaveTextContent(/3000 · Revenue\s*2,100\.00\s*Sale/);
    expect(rows[3]).toHaveTextContent(/EUR\s*2,100\.00\s*2,100\.00/);
    expect(screen.getByText(/GOO_SALE_DELIVERED · DeliveryRecorded/)).toBeInTheDocument();
    expect(screen.getByText('TRADE T-0042')).toBeInTheDocument();
    expect(screen.getByText('0123456789abcdef…')).toBeInTheDocument();

    const reverse = screen.getByRole('button', { name: 'Reverse entry' });
    expect(reverse).toBeDisabled();
    await fill(screen.getByLabelText('Reason for the reversal'), 'Posted to the wrong period');
    await userEvent.click(reverse);
    await waitFor(() => expect(reversal).toEqual({ reason: 'Posted to the wrong period' }));
    expect(unanswered()).toEqual([]);
  });

  it('links a reversal to the entry it reverses and offers no reversal of its own', async () => {
    const onOpen = vi.fn();
    server.use(
      http.get(`${ORIGIN}/api/v1/ledger/entries/j2`, () =>
        HttpResponse.json(entry({ id: 'j2', status: 'REVERSAL', ruleCode: null, reversalOf: 'j1' })),
      ),
    );
    renderPage(<EntryDrawer entryId="j2" onClose={() => undefined} onOpen={onOpen} />, {
      url: '/ledger',
      route: '/ledger',
      roles: ['ACCOUNTANT'],
    });

    expect(await screen.findByText('Reverses')).toBeInTheDocument();
    expect(screen.getByText(/Manual · DeliveryRecorded/)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Reverse entry' })).toBeNull();
    await userEvent.click(screen.getByText('Open entry'));
    expect(onOpen).toHaveBeenCalledWith('j1');
  });
});

describe('RulesTab', () => {
  const rule = {
    id: 'r1',
    ruleCode: 'GOO_SALE_DELIVERED',
    eventType: 'DeliveryRecorded',
    priority: 10,
    version: 3,
    effectiveFrom: '2026-01-01',
    active: true,
    lines: [
      { side: 'DEBIT', account: '1700', amount: 'gross' },
      { side: 'CREDIT', account: '3000', amount: 'net' },
    ],
  };

  it('lists the posting rules, names the pending changes, and shows a rule history', async () => {
    server.use(
      http.get(`${ORIGIN}/api/v1/ledger/rules`, () => HttpResponse.json([rule])),
      http.get(`${ORIGIN}/api/v1/ledger/rules/changes`, () =>
        HttpResponse.json([{ id: 'ch1', ruleCode: 'GOO_PURCHASE_RECEIVED', status: 'PENDING' }]),
      ),
      http.get(`${ORIGIN}/api/v1/ledger/rules/GOO_SALE_DELIVERED/history`, () =>
        HttpResponse.json([{ version: 3, recordedAt: '2026-09-01T08:00:00Z', rule }]),
      ),
    );
    const { unanswered } = renderPage(<RulesTab entityId="e1" />, {
      url: '/ledger?tab=rules',
      route: '/ledger',
      roles: ['ACCOUNTANT'],
    });

    await waitFor(() => expect(gridRowText('r1')).toContain('GOO_SALE_DELIVERED'));
    expect(gridRowText('r1')).toMatch(
      /^GOO_SALE_DELIVERED \| DeliveryRecorded \| 10 \| 3 \| 2026-01-01 \| Dr 1700, Cr 3000 \| Active/,
    );
    expect(screen.getByTestId('pending-rule-changes')).toHaveTextContent(
      'Rule changes waiting for approval: GOO_PURCHASE_RECEIVED',
    );

    await userEvent.click(screen.getByRole('gridcell', { name: 'GOO_SALE_DELIVERED' }));
    const drawer = await screen.findByRole('dialog', { name: 'History of GOO_SALE_DELIVERED' });
    expect(await within(drawer).findByText(/^Version 3 ·/)).toBeInTheDocument();
    expect(within(drawer).getByText('DEBIT 1700 gross')).toBeInTheDocument();
    expect(within(drawer).getByTestId('rule-propose-change')).toBeInTheDocument();
    expect(unanswered()).toEqual([]);
  });
});
