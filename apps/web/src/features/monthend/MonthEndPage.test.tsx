import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import type { Schema } from '@/shared/api/types';
import { renderPage } from '@/test/renderPage';
import { ORIGIN, server } from '@/test/server';
import { MonthEndPage } from './MonthEndPage';

beforeAll(() => server.listen({ onUnhandledFrame: 'error' }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());

const run = {
  id: 'pe-1',
  legalEntityId: 'e1',
  period: '2026-09',
  seq: 1,
  runBy: 'controller',
  runAt: '2026-10-02T07:00:00Z',
  entryIds: ['j1', 'j2'],
  warnings: ['No GoO mark for bucket NORDIC-WIND on 2026-09-30'],
  components: [
    { kind: 'MTM', key: 'T-0042', required: 1200.5, booked: 1000, posted: 200.5, disclosedOnly: false },
    { kind: 'NRV', key: 'NORDIC-WIND', required: 0, booked: 0, posted: 0, disclosedOnly: true },
  ],
} as unknown as Schema<'PeriodEndRunView'>;

describe('MonthEndPage', () => {
  it('lists the hard-close blockers, keeps hard close disabled, and runs the period end until it is posted', async () => {
    let runs: Schema<'PeriodEndRunView'>[] = [];
    let polls = 0;
    server.use(
      http.get(`${ORIGIN}/api/v1/ledger/periods`, () =>
        HttpResponse.json([{ legalEntityId: 'e1', period: '2026-09', status: 'OPEN' }]),
      ),
      http.get(`${ORIGIN}/api/v1/ledger/periods/2026-09/hard-close-blockers`, () =>
        HttpResponse.json(['2 settlement items need attention', 'The GL export is not acknowledged']),
      ),
      http.get(`${ORIGIN}/api/v1/ledger/period-end/runs`, () => HttpResponse.json(runs)),
      http.get(`${ORIGIN}/api/v1/ledger/exports`, () => HttpResponse.json([])),
      http.post(`${ORIGIN}/api/v1/ledger/period-end/runs`, () =>
        HttpResponse.json(
          {
            id: 'run-1',
            kind: 'PERIOD_END',
            status: 'RUNNING',
            requestedBy: 'tester',
            requestedAt: '2026-10-02T07:00:00Z',
          },
          { status: 202 },
        ),
      ),
      http.get(`${ORIGIN}/api/v1/runs/run-1`, () => {
        polls += 1;
        runs = [run];
        return HttpResponse.json({
          id: 'run-1',
          kind: 'PERIOD_END',
          status: 'SUCCEEDED',
          resultId: 'pe-1',
          requestedBy: 'tester',
          requestedAt: '2026-10-02T07:00:00Z',
        });
      }),
    );
    const { unanswered } = renderPage(<MonthEndPage />, {
      url: '/month-end?period=2026-09',
      route: '/month-end',
      roles: ['ACCOUNTANT'],
    });

    const checklist = await screen.findByTestId('hard-close-checklist');
    expect(await within(checklist).findByText('2 settlement items need attention')).toBeInTheDocument();
    expect(within(checklist).getByText('The GL export is not acknowledged')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Hard close' })).toBeDisabled();
    const kpis = screen.getByTestId('month-end-kpis');
    expect(kpis).toHaveTextContent(/Hard-close blockers\s*2/);
    expect(kpis).toHaveTextContent('Run period end, then soft close');

    await userEvent.click(screen.getByRole('button', { name: 'Run period end' }));
    const posted = await screen.findByTestId('period-end-run', {}, { timeout: 3000 });
    expect(polls).toBe(1);
    expect(posted).toHaveTextContent('No GoO mark for bucket NORDIC-WIND on 2026-09-30');
    const rows = within(posted).getAllByRole('row');
    expect(rows[1]).toHaveTextContent(/Mark to market\s*T-0042\s*1,200\.50\s*1,000\.00\s*200\.50/);
    expect(rows[2]).toHaveTextContent(/Net realisable value/);
    await waitFor(() => expect(kpis).toHaveTextContent(/Run 1\s*1 warning/));
    expect(unanswered()).toEqual([]);
  });
});
