import { MantineProvider } from '@mantine/core';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { ColDef } from 'ag-grid-community';
import { http, HttpResponse } from 'msw';
import { MemoryRouter } from 'react-router';
import { ORIGIN, server } from '@/test/server';
import { ClearingPage } from './ClearingPage';

// AG Grid needs a real layout; these tests are about what the page asks for and hands to its grids.
vi.mock('@/shared/grid/DataGrid', () => ({
  DataGrid: ({ rowData, columnDefs }: { rowData: unknown[]; columnDefs: ColDef[] }) => (
    <table>
      <tbody>
        {rowData.map((row, index) => (
          <tr key={index} data-testid="grid-row">
            {columnDefs.map((column) => (
              <td key={column.colId ?? column.field}>
                {(row as Record<string, string | number | null>)[column.field ?? ''] ?? ''}
              </td>
            ))}
          </tr>
        ))}
      </tbody>
    </table>
  ),
}));

vi.mock('@/shared/context/EntityContext', () => ({
  useEntity: () => ({
    entity: { id: 'e1', code: 'DEMO-FI', name: 'Demo Energia Oy', baseCurrency: 'EUR' },
    entities: [],
    select: () => undefined,
  }),
}));

let roles = ['BACK_OFFICE'];
vi.mock('@/shared/auth/SessionContext', () => ({
  useSession: () => ({ me: { roles, userId: 'bo', legalEntityIds: [], allEntities: true } }),
}));

beforeAll(() => server.listen({ onUnhandledFrame: 'error' }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());

const account = {
  id: 'a1',
  code: 'ECC-DEMO-FI',
  legalEntityId: 'e1',
  clearingHouseId: 'ch1',
  clearingMember: 'Demo Bank',
  currency: 'EUR',
  initialMargin: 250000,
  active: true,
  createdAt: '2026-01-01T00:00:00Z',
  createdBy: 'mo',
};

function handlers(openBreaks: number) {
  server.use(
    http.get(`${ORIGIN}/api/v1/clearing/accounts`, () => HttpResponse.json([account])),
    http.get(`${ORIGIN}/api/v1/clearing/margin-posted`, () =>
      HttpResponse.json([
        {
          accountId: 'a1',
          clearingHouseId: 'ch1',
          currency: 'EUR',
          initialMargin: 250000,
          cashBalance: 180000,
          openCalls: 70000,
        },
      ]),
    ),
    http.get(`${ORIGIN}/api/v1/clearing/breaks`, () =>
      HttpResponse.json(
        Array.from({ length: openBreaks }, (_, index) => ({
          id: `b${index}`,
          accountId: 'a1',
          legalEntityId: 'e1',
          businessDate: '2026-09-30',
          statementId: 's1',
          status: 'OPEN',
          openedAt: '2026-10-01T06:00:00Z',
          openedBy: 'bo',
          differences: [{ kind: 'VARIATION_MARGIN', contractCode: 'EUA-DEC26', ours: 1, theirs: 2 }],
        })),
      ),
    ),
    http.get(`${ORIGIN}/api/v1/clearing/margin-calls`, () =>
      HttpResponse.json([
        {
          id: 'c1',
          accountId: 'a1',
          legalEntityId: 'e1',
          statementId: 's1',
          businessDate: '2026-09-30',
          amount: 70000,
          currency: 'EUR',
          status: 'OPEN',
          requestedAt: '2026-10-01T06:00:00Z',
          requestedBy: 'bo',
        },
      ]),
    ),
    http.get(`${ORIGIN}/api/v1/reference/counterparties`, () =>
      HttpResponse.json([{ id: 'ch1', name: 'EEX Clearing', active: true, counterpartyType: 'CLEARING_HOUSE' }]),
    ),
  );
}

function renderPage(url = '/clearing') {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <MantineProvider>
      <QueryClientProvider client={client}>
        <MemoryRouter initialEntries={[url]}>
          <ClearingPage />
        </MemoryRouter>
      </QueryClientProvider>
    </MantineProvider>,
  );
}

describe('ClearingPage', () => {
  it('shows the margin held, the cash at the member and what is open for the account', async () => {
    handlers(2);
    renderPage();
    const kpis = await screen.findByTestId('clearing-kpis');
    const initial = within(kpis).getByTestId('clearing-kpi-initial-margin');
    expect(await within(initial).findByText('250,000.00')).toBeInTheDocument();
    expect(await within(kpis).findByText('180,000.00')).toBeInTheDocument();
    expect(await within(within(kpis).getByTestId('clearing-kpi-breaks')).findByText('2')).toBeInTheDocument();
    expect(await within(within(kpis).getByTestId('clearing-kpi-calls')).findByText('1')).toBeInTheDocument();
    expect(within(kpis).getByTestId('clearing-kpi-calls')).toHaveTextContent('70,000.00 EUR to pay');
    // One account only: no picker.
    expect(screen.queryByTestId('clearing-account-select')).toBeNull();
  });

  it('opens on the accounts tab and lists the account for the clearing house', async () => {
    handlers(0);
    renderPage();
    const grid = await screen.findByTestId('clearing-accounts-grid');
    expect(within(grid).getAllByTestId('grid-row')).toHaveLength(1);
    expect(screen.getByTestId('clearing-new-account')).toBeInTheDocument();
  });

  it('keeps the tab in the URL and offers import and compute only to those who may', async () => {
    handlers(1);
    roles = ['AUDITOR'];
    const user = userEvent.setup();
    renderPage('/clearing?tab=statements');
    expect(await screen.findByTestId('clearing-tab-statements')).toHaveAttribute('aria-selected', 'true');
    expect(screen.queryByTestId('clearing-import-statement')).toBeNull();
    await user.click(screen.getByTestId('clearing-tab-margin'));
    expect(screen.getByTestId('clearing-tab-margin')).toHaveAttribute('aria-selected', 'true');
    expect(screen.queryByTestId('clearing-compute-margin')).toBeNull();
    roles = ['BACK_OFFICE'];
  });

  it('offers import and compute to back office', async () => {
    handlers(1);
    server.use(
      http.get(`${ORIGIN}/api/v1/clearing/accounts/a1/statements`, () => HttpResponse.json([])),
      http.get(`${ORIGIN}/api/v1/clearing/accounts/a1/margin`, () => HttpResponse.json([])),
    );
    const user = userEvent.setup();
    renderPage('/clearing?tab=statements');
    expect(await screen.findByTestId('clearing-import-statement')).toBeInTheDocument();
    await user.click(screen.getByTestId('clearing-tab-margin'));
    expect(await screen.findByTestId('clearing-compute-margin')).toBeInTheDocument();
  });
});
