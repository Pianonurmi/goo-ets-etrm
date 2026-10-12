import { MantineProvider } from '@mantine/core';
import { render, screen } from '@testing-library/react';
import type { Schema } from '@/shared/api/types';
import { PlanValuePanel } from './PlanValuePanel';

// AG Grid needs a real layout; the panel's own job is which figures and rows it hands to the grid.
vi.mock('@/shared/grid/DataGrid', () => ({
  DataGrid: ({ rowData }: { rowData: { obligationId: string }[] }) => (
    <ul>
      {rowData.map((row) => (
        <li key={row.obligationId}>{row.obligationId}</li>
      ))}
    </ul>
  ),
}));

type PlanValue = Schema<'PlanValue'>;

const value: PlanValue = {
  currency: 'EUR',
  totalCostEur: 1234.5,
  deliveredValueEur: 5000,
  expiryHaircutEur: 42,
  transferFeesEur: 7.25,
  giveAwayEur: 310,
  unpricedMwh: 0,
  byObligation: [
    {
      obligationId: '00000000-0000-7000-8000-000000000001',
      saleBucketCode: 'EU_RENEWABLE',
      saleMarkEur: 1.2,
      mwh: 100,
      deliveredValueEur: 150,
      averageDeliveredMarkEur: 1.5,
      giveAwayEur: 30,
    },
  ],
};

function renderPanel(plan: PlanValue) {
  return render(
    <MantineProvider>
      <PlanValuePanel
        value={plan}
        obligationLabel={() => 'Sale 1'}
        kpiTestId="value-kpis"
        gridTestId="give-away-grid"
      />
    </MantineProvider>,
  );
}

describe('PlanValuePanel', () => {
  it('shows the five figures and the give-away rows', () => {
    renderPanel(value);
    const kpis = screen.getByTestId('value-kpis');
    expect(kpis).toHaveTextContent('Total cost');
    expect(kpis).toHaveTextContent('1,234.50');
    expect(kpis).toHaveTextContent('Delivered value');
    expect(kpis).toHaveTextContent('Give-away');
    expect(kpis).toHaveTextContent('Expiry haircut');
    expect(kpis).toHaveTextContent('Transfer fees');
    expect(kpis).toHaveTextContent('7.25');
    expect(screen.getByTestId('give-away-grid')).toHaveTextContent('00000000-0000-7000-8000-000000000001');
  });

  it('warns about unpriced MWh only when there are some', () => {
    const { unmount } = renderPanel(value);
    expect(screen.queryByTestId('plan-value-unpriced')).not.toBeInTheDocument();
    unmount();
    renderPanel({ ...value, unpricedMwh: 25 });
    expect(screen.getByTestId('plan-value-unpriced')).toHaveTextContent('25 MWh');
  });
});
