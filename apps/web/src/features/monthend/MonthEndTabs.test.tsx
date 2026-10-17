import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { renderPage } from '@/test/renderPage';
import { ORIGIN, server } from '@/test/server';
import { GlInterface } from './GlInterface';
import { Policies } from './Policies';

beforeAll(() => server.listen({ onUnhandledFrame: 'error' }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());

describe('Policies', () => {
  it('shows the MTM and NRV policies with their sign-off, and requests an NRV change', async () => {
    let requested: unknown;
    server.use(
      http.get(`${ORIGIN}/api/v1/ledger/period-end/policies/mtm`, () =>
        HttpResponse.json([
          {
            id: 'p1',
            bookType: 'TRADING',
            treatment: 'POST_PL',
            legalEntityId: null,
            signedOffBy: 'controller',
            signedOffAt: '2026-01-02T09:00:00Z',
          },
          { id: 'p2', bookType: 'HEDGING', treatment: 'POST_OCI', legalEntityId: 'e1', signedOffBy: null },
        ]),
      ),
      http.get(`${ORIGIN}/api/v1/ledger/period-end/policies/nrv`, () =>
        HttpResponse.json({
          gooCostToSellPerMwh: 0.05,
          allowanceCostToSell: 0.25,
          currency: 'EUR',
          expiryDays: 90,
          signedOffBy: 'controller',
          signedOffAt: '2026-01-02T09:00:00Z',
        }),
      ),
      http.get(`${ORIGIN}/api/v1/ledger/period-end/policy-changes`, () =>
        HttpResponse.json([{ id: 'c1' }, { id: 'c2' }]),
      ),
      http.post(`${ORIGIN}/api/v1/ledger/period-end/policy-changes`, async ({ request }) => {
        requested = await request.json();
        return HttpResponse.json({ id: 'c3' });
      }),
    );
    const { unanswered } = renderPage(<Policies entityId="e1" />, {
      url: '/month-end?tab=policies',
      route: '/month-end',
      roles: ['ACCOUNTANT'],
    });

    const rows = await screen.findAllByRole('row');
    expect(rows[1]).toHaveTextContent(/Post to P&L\s*Group default\s*Signed off by controller/);
    expect(rows[2]).toHaveTextContent(/Post to OCI\s*This entity\s*Not signed off/);
    expect(await screen.findByText('2 policy changes wait for approval')).toBeInTheDocument();
    expect(await screen.findByText(/GoO cost to sell \(per MWh\): 0\.05 EUR/)).toBeInTheDocument();
    expect(screen.getByText(/Expiry horizon \(days\): 90/)).toBeInTheDocument();

    await userEvent.click(screen.getByRole('button', { name: 'Request policy change' }));
    const dialog = await screen.findByRole('dialog');
    await userEvent.click(within(dialog).getByRole('combobox', { name: 'Kind' }));
    await userEvent.click(await screen.findByRole('option', { name: 'Net realisable value policy' }));
    await userEvent.type(within(dialog).getByRole('textbox', { name: 'GoO cost to sell (per MWh)' }), '0.07');
    await userEvent.type(within(dialog).getByRole('textbox', { name: 'Reason' }), 'Broker fees rose');
    await userEvent.click(within(dialog).getByRole('button', { name: 'Send for approval' }));

    await waitFor(() =>
      expect(requested).toEqual({
        kind: 'NRV',
        legalEntityId: 'e1',
        gooCostToSellPerMwh: '0.07',
        currency: 'EUR',
        reason: 'Broker fees rose',
      }),
    );
    expect(unanswered()).toEqual([]);
  });
});

describe('GlInterface', () => {
  const glExport = {
    id: 'x1',
    exportSeq: 3,
    kind: 'PERIOD',
    format: 'GENERIC_CSV',
    entryCount: 42,
    status: 'DELIVERED',
    exportedBy: 'accountant',
    exportedAt: '2026-10-02T09:00:00Z',
    contentHash: 'abcdef0123456789abcdef',
    period: '2026-09',
    content: '',
  };

  function handlers(calls: { method: string; path: string; body?: unknown }[]) {
    const record = async (request: Request) => {
      const url = new URL(request.url);
      const text = await request.text();
      calls.push({
        method: request.method,
        path: `${url.pathname}${url.search}`,
        body: text ? JSON.parse(text) : undefined,
      });
      return HttpResponse.json({ ...glExport, exportSeq: 4, entryCount: 7 });
    };
    return [
      http.get(`${ORIGIN}/api/v1/ledger/export-formats`, () =>
        HttpResponse.json([
          { code: 'GENERIC_CSV', name: 'Generic CSV', active: true, verified: true },
          { code: 'SAP_GL', name: 'SAP GL', active: true, verified: false },
        ]),
      ),
      http.get(`${ORIGIN}/api/v1/ledger/exports`, () => HttpResponse.json([glExport])),
      http.get(`${ORIGIN}/api/v1/ledger/reconciliation`, () =>
        HttpResponse.json([
          {
            glAccount: '1610',
            subLedger: 110000,
            exported: 110000,
            notExported: 0,
            generalLedger: 109500,
            glDifference: 500,
            reconciled: false,
          },
        ]),
      ),
      http.post(`${ORIGIN}/api/v1/ledger/exports/2026-09`, ({ request }) => record(request)),
      http.post(`${ORIGIN}/api/v1/ledger/exports/x1/acknowledge`, ({ request }) => record(request)),
      http.put(`${ORIGIN}/api/v1/ledger/gl-balances/2026-09`, ({ request }) => record(request)),
    ];
  }

  it('lists the exports and the reconciliation, creates an export, acknowledges one and imports balances', async () => {
    const calls: { method: string; path: string; body?: unknown }[] = [];
    server.use(...handlers(calls));
    const { unanswered } = renderPage(<GlInterface entityId="e1" period="2026-09" />, {
      url: '/month-end',
      route: '/month-end',
      roles: ['ACCOUNTANT'],
    });

    const exportRow = (await screen.findByText('abcdef012345')).closest('tr') as HTMLElement;
    expect(exportRow).toHaveTextContent(/3.*GENERIC_CSV\s*42/);
    expect(exportRow).toHaveTextContent('accountant');
    const reconciliationRow = (await screen.findByText('1610')).closest('tr') as HTMLElement;
    expect(reconciliationRow).toHaveTextContent(/110,000\.00\s*110,000\.00\s*0\.00\s*109,500\.00\s*500\.00/);

    await userEvent.click(screen.getByRole('button', { name: 'Export to GL' }));
    await waitFor(() => expect(calls).toHaveLength(1));
    expect(calls[0]).toMatchObject({
      method: 'POST',
      path: '/api/v1/ledger/exports/2026-09?legalEntityId=e1&format=GENERIC_CSV',
    });

    await userEvent.click(within(exportRow).getByRole('button', { name: 'Acknowledge' }));
    const ack = await screen.findByRole('dialog', { name: 'Acknowledge' });
    await userEvent.type(within(ack).getByRole('textbox', { name: 'GL reference' }), 'SAP-4711');
    await userEvent.click(within(ack).getByRole('button', { name: 'Confirm' }));
    await waitFor(() => expect(calls).toHaveLength(2));
    expect(calls[1]).toMatchObject({ path: '/api/v1/ledger/exports/x1/acknowledge', body: { reference: 'SAP-4711' } });

    await userEvent.click(screen.getByRole('button', { name: 'Import GL balances' }));
    const importDialog = await screen.findByRole('dialog', { name: 'Import GL balances' });
    await userEvent.click(within(importDialog).getByRole('textbox'));
    await userEvent.paste('glAccount;balance;currency\n1610;109500.00;EUR\n3000;-2100.50;EUR');
    await userEvent.click(within(importDialog).getByRole('button', { name: 'Import 2 balances' }));
    await waitFor(() => expect(calls).toHaveLength(3));
    expect(calls[2]).toMatchObject({
      method: 'PUT',
      path: '/api/v1/ledger/gl-balances/2026-09?legalEntityId=e1',
      body: [
        { glAccount: '1610', balance: '109500.00', currency: 'EUR' },
        { glAccount: '3000', balance: '-2100.50', currency: 'EUR' },
      ],
    });
    expect(unanswered()).toEqual([]);
  });

  it('refuses a pasted line it cannot read', async () => {
    server.use(...handlers([]));
    renderPage(<GlInterface entityId="e1" period="2026-09" />, {
      url: '/month-end',
      route: '/month-end',
      roles: ['ACCOUNTANT'],
    });

    await userEvent.click(await screen.findByRole('button', { name: 'Import GL balances' }));
    const dialog = await screen.findByRole('dialog', { name: 'Import GL balances' });
    await userEvent.click(within(dialog).getByRole('textbox'));
    await userEvent.paste('glAccount;balance;currency\n1610;110 000,00;EUR');
    expect(within(dialog).getByText('Lines 2 could not be read')).toBeInTheDocument();
    expect(within(dialog).getByRole('button', { name: /^Import/ })).toBeDisabled();
  });

  it('is closed to those who neither keep nor audit the books', async () => {
    server.use(...handlers([]));
    renderPage(<GlInterface entityId="e1" period="2026-09" />, {
      url: '/month-end',
      route: '/month-end',
      roles: ['TRADER'],
    });

    expect(await screen.findByText('The GL interface is for accountants and auditors.')).toBeInTheDocument();
  });
});
