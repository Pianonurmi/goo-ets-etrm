import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import type { Schema } from '@/shared/api/types';
import { gridRowText, renderPage } from '@/test/renderPage';
import { ORIGIN, server } from '@/test/server';
import { ItemDrawer } from './ItemDrawer';
import { NettingTab } from './NettingTab';
import { PaymentsTab } from './PaymentsTab';

/** Types [text] into [input] in one go: per-key typing re-renders the form for every character. */
async function fill(input: HTMLElement, text: string) {
  await userEvent.click(input);
  await userEvent.paste(text);
}

beforeAll(() => server.listen({ onUnhandledFrame: 'error' }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());

const counterparties = http.get(`${ORIGIN}/api/v1/reference/counterparties`, () =>
  HttpResponse.json([
    { id: 'c1', code: 'CP-RHEIN', name: 'Rheinstrom AG', active: true, nettingAgreed: false },
    { id: 'c2', code: 'CP-NORD', name: 'Nordvind AS', active: true, nettingAgreed: true },
  ]),
);

const payment = (id: string, unapplied: number, extra: Partial<Schema<'PaymentView'>> = {}) =>
  ({
    id,
    legalEntityId: 'e1',
    direction: 'INCOMING',
    counterpartyId: 'c1',
    amount: 5000,
    unappliedAmount: unapplied,
    currency: 'EUR',
    valueDate: '2026-10-15',
    bankReference: `BANK-${id}`,
    remittanceInfo: 'INV-2026-0001',
    source: 'MANUAL',
    createdBy: 'accountant',
    ...extra,
  }) as Schema<'PaymentView'>;

const openInvoice = {
  id: 'inv1',
  number: 'INV-2026-0001',
  legalEntityId: 'e1',
  counterpartyId: 'c1',
  direction: 'RECEIVABLE',
  status: 'ISSUED',
  currency: 'EUR',
  outstanding: 1200,
};

/** Picks an option of a Mantine select by its label. */
async function choose(label: RegExp | string, option: RegExp | string) {
  await userEvent.click(within(await screen.findByRole('dialog')).getByRole('combobox', { name: label }));
  await userEvent.click(await screen.findByRole('option', { name: option }));
}

describe('PaymentsTab', () => {
  const listing = (rows: Schema<'PaymentView'>[]) =>
    http.get(`${ORIGIN}/api/v1/settlement/payments/search`, () => HttpResponse.json({ items: rows, nextCursor: null }));

  it('lists the payments with their unapplied cash, and allocates the cash to an open invoice', async () => {
    let allocation: unknown;
    server.use(
      counterparties,
      listing([payment('p1', 1200), payment('p2', 0, { direction: 'OUTGOING', counterpartyId: null, source: 'CSV' })]),
      http.get(`${ORIGIN}/api/v1/settlement/invoices`, () => HttpResponse.json([openInvoice])),
      http.post(`${ORIGIN}/api/v1/settlement/payments/p1/allocations`, async ({ request }) => {
        allocation = await request.json();
        return HttpResponse.json(payment('p1', 0));
      }),
    );
    const { unanswered } = renderPage(<PaymentsTab entityId="e1" />, {
      url: '/settlement?tab=payments',
      route: '/settlement',
      roles: ['ACCOUNTANT'],
    });

    await waitFor(() => expect(gridRowText('p1')).toContain('Rheinstrom AG'));
    expect(gridRowText('p1')).toMatch(
      /^2026-10-15 \| Received \| Rheinstrom AG \| 5,000\.00 EUR \| 1,200\.00 EUR \| BANK-p1/,
    );
    expect(gridRowText('p2')).toMatch(/Paid \| {2}\| 5,000\.00 EUR \| 0\.00 EUR .*CSV/);
    expect(screen.getByTestId('payment-count')).toHaveTextContent('2 shown');

    // Only the payment with unapplied cash offers an allocation.
    const allocate = await screen.findAllByRole('button', { name: 'Allocate' });
    expect(allocate).toHaveLength(1);
    await userEvent.click(allocate[0] as HTMLElement);
    const dialog = await screen.findByRole('dialog');
    await userEvent.click(within(dialog).getByRole('combobox', { name: 'Invoice' }));
    await userEvent.click(await screen.findByRole('option', { name: /INV-2026-0001/ }));
    await userEvent.click(within(dialog).getByTestId('allocate-submit'));

    await waitFor(() => expect(allocation).toEqual({ invoiceId: 'inv1' }));
    expect(unanswered()).toEqual([]);
  });

  it('records a payment against an invoice with the amount as a decimal string', async () => {
    let recorded: Record<string, unknown> | undefined;
    server.use(
      counterparties,
      listing([]),
      http.get(`${ORIGIN}/api/v1/settlement/invoices`, () => HttpResponse.json([openInvoice])),
      http.post(`${ORIGIN}/api/v1/settlement/payments`, async ({ request }) => {
        recorded = (await request.json()) as Record<string, unknown>;
        return HttpResponse.json(payment('p3', 0));
      }),
    );
    const { unanswered } = renderPage(<PaymentsTab entityId="e1" />, {
      url: '/settlement?tab=payments',
      route: '/settlement',
      roles: ['ACCOUNTANT'],
    });

    await userEvent.click(await screen.findByTestId('record-payment'));
    await choose('Counterparty', /Rheinstrom AG/);
    await fill(screen.getByTestId('payment-amount'), '1200.50');
    await fill(screen.getByTestId('payment-reference'), 'BANK-7');
    await fill(screen.getByTestId('payment-remittance'), 'INV-2026-0001');
    await userEvent.click(screen.getByTestId('payment-invoices'));
    await userEvent.click(await screen.findByRole('option', { name: /INV-2026-0001/ }));
    await userEvent.click(screen.getByTestId('payment-submit'));

    await waitFor(() => expect(recorded).toBeDefined());
    expect(recorded).toMatchObject({
      legalEntityId: 'e1',
      direction: 'INCOMING',
      counterpartyId: 'c1',
      amount: '1200.50',
      currency: 'EUR',
      bankReference: 'BANK-7',
      remittanceInfo: 'INV-2026-0001',
      invoiceIds: ['inv1'],
    });
    expect(unanswered()).toEqual([]);
  });

  it('imports bank statement lines and asks for payments with unapplied cash only when switched', async () => {
    const unappliedOnly: (string | null)[] = [];
    let uploaded = '';
    server.use(
      counterparties,
      http.get(`${ORIGIN}/api/v1/settlement/payments/search`, ({ request }) => {
        unappliedOnly.push(new URL(request.url).searchParams.get('unappliedOnly'));
        return HttpResponse.json({ items: [], nextCursor: null });
      }),
      http.post(`${ORIGIN}/api/v1/settlement/payments/csv`, async ({ request }) => {
        uploaded = await request.text();
        return HttpResponse.json([payment('p4', 0, { source: 'CSV' })]);
      }),
    );
    const { unanswered } = renderPage(<PaymentsTab entityId="e1" />, {
      url: '/settlement?tab=payments',
      route: '/settlement',
      roles: ['ACCOUNTANT'],
    });

    await userEvent.click(await screen.findByRole('switch', { name: 'Only payments with unapplied cash' }));
    await waitFor(() => expect(unappliedOnly).toEqual(['false', 'true']));

    await userEvent.click(screen.getByRole('button', { name: 'Import bank statement' }));
    const csv = 'bank_reference,value_date,amount\nBANK-9,2026-10-15,100.00';
    await userEvent.click(screen.getByTestId('payments-csv'));
    await userEvent.paste(csv);
    expect(screen.getByRole('dialog')).toHaveTextContent('1');
    await userEvent.click(screen.getByTestId('payments-import'));
    await waitFor(() => expect(uploaded).toBe(csv));
    expect(unanswered()).toEqual([]);
  });
});

describe('NettingTab', () => {
  it('lists the netting statements and prepares one with a counterparty we net with', async () => {
    let prepared: unknown;
    server.use(
      counterparties,
      http.get(`${ORIGIN}/api/v1/settlement/netting`, () =>
        HttpResponse.json([
          {
            id: 'n1',
            legalEntityId: 'e1',
            counterpartyId: 'c2',
            currency: 'EUR',
            status: 'DRAFT',
            valueDate: '2026-11-15',
            receivableTotal: 585,
            payableTotal: 340,
            netAmount: 245,
            invoices: {},
            createdBy: 'accountant',
            version: 1,
          },
        ]),
      ),
      http.post(`${ORIGIN}/api/v1/settlement/netting`, async ({ request }) => {
        prepared = await request.json();
        return HttpResponse.json({ id: 'n2' });
      }),
    );
    const { unanswered } = renderPage(<NettingTab entityId="e1" />, {
      url: '/settlement?tab=netting',
      route: '/settlement',
      roles: ['ACCOUNTANT'],
    });

    await waitFor(() => expect(gridRowText('n1')).toContain('Nordvind AS'));
    expect(gridRowText('n1')).toMatch(
      /^Nordvind AS \| Draft \| 2026-11-15 \| 585\.00 EUR \| 340\.00 EUR \| 245\.00 EUR \| accountant/,
    );

    await userEvent.click(screen.getByTestId('prepare-netting'));
    expect(screen.getByTestId('netting-submit')).toBeDisabled();
    await userEvent.click(within(await screen.findByRole('dialog')).getByRole('combobox', { name: 'Counterparty' }));
    // Only counterparties with a netting agreement are offered.
    expect(screen.queryByRole('option', { name: /Rheinstrom/ })).toBeNull();
    await userEvent.click(await screen.findByRole('option', { name: /Nordvind AS/ }));
    await userEvent.click(screen.getByTestId('netting-submit'));

    await waitFor(() => expect(prepared).toMatchObject({ legalEntityId: 'e1', counterpartyId: 'c2', currency: 'EUR' }));
    expect(unanswered()).toEqual([]);
  });
});

describe('ItemDrawer', () => {
  it('shows the item with its pricing, VAT and the invoice it went to', async () => {
    server.use(
      counterparties,
      http.get(`${ORIGIN}/api/v1/settlement/items/i1`, () =>
        HttpResponse.json({
          id: 'i1',
          legalEntityId: 'e1',
          counterpartyId: 'c1',
          tradeId: 't1',
          tradeNumber: 'T-0042',
          direction: 'SALE',
          description: 'Nordic hydro GoO, delivered',
          quantity: 1000,
          unit: 'MWH',
          unitPrice: 2.1,
          netAmount: 2100,
          vatTreatment: 'REVERSE_CHARGE',
          vatRate: 0,
          vatAmount: 0,
          currency: 'EUR',
          deliveredOn: '2026-10-01',
          status: 'INVOICED',
          invoiceId: 'inv1',
          problem: 'Price fixed after delivery',
          version: 2,
        }),
      ),
    );
    const { unanswered } = renderPage(<ItemDrawer itemId="i1" onClose={() => undefined} />, {
      url: '/settlement',
      route: '/settlement',
      roles: ['ACCOUNTANT'],
    });

    expect(await screen.findByTestId('item-trade')).toHaveTextContent('T-0042');
    expect(screen.getByTestId('item-problem')).toHaveTextContent('Price fixed after delivery');
    expect(screen.getByTestId('item-net')).toHaveTextContent('2,100.00 EUR');
    expect(screen.getByTestId('item-quantity')).toHaveTextContent('1,000');
    expect(screen.getByTestId('item-drawer')).toHaveTextContent('Rheinstrom AG');
    expect(within(screen.getByTestId('item-invoice')).getByRole('link')).toHaveAttribute(
      'href',
      '/settlement/invoices/inv1',
    );
    expect(unanswered()).toEqual([]);
  });
});
