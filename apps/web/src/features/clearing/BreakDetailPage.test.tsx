import { MantineProvider } from '@mantine/core';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, within } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { MemoryRouter, Route, Routes } from 'react-router';
import type { Schema } from '@/shared/api/types';
import { ORIGIN, server } from '@/test/server';
import { BreakDetailPage } from './BreakDetailPage';

beforeAll(() => server.listen({ onUnhandledFrame: 'error' }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());

const BREAK_ID = '0193aaaa-0000-7000-8000-000000000001';
const ACCOUNT_ID = '0193aaaa-0000-7000-8000-000000000002';

const brk = {
  id: BREAK_ID,
  accountId: ACCOUNT_ID,
  legalEntityId: 'e1',
  businessDate: '2026-09-30',
  statementId: '0193aaaa-0000-7000-8000-000000000003',
  runId: '0193aaaa-0000-7000-8000-000000000004',
  status: 'OPEN',
  openedAt: '2026-10-01T06:00:00Z',
  openedBy: 'bo',
  differences: [
    { kind: 'VARIATION_MARGIN', contractCode: 'EUA-DEC26', ours: 1250.5, theirs: 1200 },
    { kind: 'POSITION', contractCode: 'EUA-DEC27', ours: 10, theirs: 12 },
  ],
} as unknown as Schema<'ClearingBreakView'>;

const account = {
  id: ACCOUNT_ID,
  code: 'ECC-DEMO-FI',
  legalEntityId: 'e1',
  clearingHouseId: 'ch1',
  clearingMember: 'Demo Bank',
  currency: 'EUR',
  initialMargin: 0,
  active: true,
  createdAt: '2026-01-01T00:00:00Z',
  createdBy: 'mo',
};

function renderPage() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <MantineProvider>
      <QueryClientProvider client={client}>
        <MemoryRouter initialEntries={[`/clearing/breaks/${BREAK_ID}`]}>
          <Routes>
            <Route path="/clearing/breaks/:breakId" element={<BreakDetailPage />} />
          </Routes>
        </MemoryRouter>
      </QueryClientProvider>
    </MantineProvider>,
  );
}

describe('BreakDetailPage', () => {
  it('shows the differences, ours against theirs, and the open inbox task', async () => {
    server.use(
      http.get(`${ORIGIN}/api/v1/clearing/breaks/${BREAK_ID}`, () => HttpResponse.json(brk)),
      http.get(`${ORIGIN}/api/v1/clearing/accounts`, () => HttpResponse.json([account])),
      http.get(`${ORIGIN}/api/v1/reference/counterparties`, () =>
        HttpResponse.json([{ id: 'ch1', name: 'EEX Clearing', active: true, counterpartyType: 'CLEARING_HOUSE' }]),
      ),
      http.get(`${ORIGIN}/api/v1/workflow/tasks`, () =>
        HttpResponse.json({ items: [{ id: 'task-1', type: 'CLEARING_BREAK' }], nextCursor: null }),
      ),
    );
    renderPage();
    const page = await screen.findByTestId('clearing-break-detail');
    expect(await within(page).findByTestId('clearing-break-status')).toHaveTextContent('Open');
    const table = within(page).getByTestId('clearing-break-differences');
    const margin = within(table).getByTestId('clearing-difference-VARIATION_MARGIN');
    expect(margin).toHaveTextContent('EUA-DEC26');
    expect(margin).toHaveTextContent('1,250.50 EUR');
    expect(margin).toHaveTextContent('1,200.00 EUR');
    expect(within(table).getByTestId('clearing-difference-POSITION')).toHaveTextContent('1012');
    expect(await within(page).findByTestId('clearing-break-task')).toHaveAttribute('href', '/inbox/task-1');
    expect(within(page).getByTestId('clearing-break-statement')).toHaveAttribute(
      'href',
      `/clearing?tab=statements&account=${ACCOUNT_ID}&statement=${brk.statementId}`,
    );
    expect(within(page).getByTestId('clearing-break-run')).toBeInTheDocument();
  });

  it('says so when the day has no margin run', async () => {
    server.use(
      http.get(`${ORIGIN}/api/v1/clearing/breaks/${BREAK_ID}`, () =>
        HttpResponse.json({
          ...brk,
          runId: null,
          status: 'ACCEPTED',
          closedBy: 'mo',
          closedAt: '2026-10-02T06:00:00Z',
          resolution: 'Member figures agreed',
          differences: [{ kind: 'NO_RUN' }],
        }),
      ),
      http.get(`${ORIGIN}/api/v1/clearing/accounts`, () => HttpResponse.json([account])),
      http.get(`${ORIGIN}/api/v1/reference/counterparties`, () => HttpResponse.json([])),
    );
    renderPage();
    const page = await screen.findByTestId('clearing-break-detail');
    expect(await within(page).findByTestId('clearing-break-resolution')).toHaveTextContent('Member figures agreed');
    expect(within(page).getByTestId('clearing-difference-NO_RUN')).toHaveTextContent('No margin run');
    expect(within(page).queryByTestId('clearing-break-run')).toBeNull();
    expect(within(page).queryByTestId('clearing-break-task')).toBeNull();
  });
});
