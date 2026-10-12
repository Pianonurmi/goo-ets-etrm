import { MantineProvider } from '@mantine/core';
import { render, screen } from '@testing-library/react';
import type { Schema } from '@/shared/api/types';
import { ShadowPricesPanel } from './ShadowPricesPanel';

// AG Grid needs a real layout; the panel's own job is which figures and rows it hands to the grids.
vi.mock('@/shared/grid/DataGrid', () => ({
  DataGrid: ({ rowData, rowId }: { rowData: unknown[]; rowId: (row: never) => string }) => (
    <ul>
      {rowData.map((row) => (
        <li key={rowId(row as never)}>{rowId(row as never)}</li>
      ))}
    </ul>
  ),
}));

type PlanPricing = Schema<'PlanPricing'>;

const pricing: PlanPricing = {
  status: 'OPTIMAL',
  currency: 'EUR',
  lpCostEur: 900.25,
  planCostEur: 1000.5,
  gapEur: 100.25,
  obligations: [
    { obligationId: '00000000-0000-7000-8000-000000000001', coveredMwh: 100, short: false, marginalCostEur: 1.75 },
    { obligationId: '00000000-0000-7000-8000-000000000002', coveredMwh: 40, short: true, marginalCostEur: 0.5 },
  ],
  supply: [
    {
      bucketCode: 'FI_HYDRO',
      productionYear: 2025,
      supplyMwh: 500,
      usedMwh: 140,
      averageRentEur: 0.3,
      maxRentEur: 0.9,
    },
  ],
};

function renderPanel(value: PlanPricing) {
  return render(
    <MantineProvider>
      <ShadowPricesPanel
        pricing={value}
        obligationLabel={() => 'Sale'}
        kpiTestId="shadow-kpis"
        obligationsTestId="shadow-obligations"
        supplyTestId="shadow-supply"
      />
    </MantineProvider>,
  );
}

describe('ShadowPricesPanel', () => {
  it('shows the indicative note, the three figures and the rows of both grids', () => {
    renderPanel(pricing);
    expect(screen.getByTestId('shadow-prices-note')).toHaveTextContent('Indicative only');
    const kpis = screen.getByTestId('shadow-kpis');
    expect(kpis).toHaveTextContent('LP minimum cost');
    expect(kpis).toHaveTextContent('900.25');
    expect(kpis).toHaveTextContent('Plan cost');
    expect(kpis).toHaveTextContent('1,000.50');
    expect(kpis).toHaveTextContent('Gap');
    expect(kpis).toHaveTextContent('100.25');
    expect(screen.getByTestId('shadow-obligations')).toHaveTextContent('00000000-0000-7000-8000-000000000001');
    expect(screen.getByTestId('shadow-obligations')).toHaveTextContent('00000000-0000-7000-8000-000000000002');
    expect(screen.getByTestId('shadow-supply')).toHaveTextContent('FI_HYDRO:2025');
    expect(screen.queryByTestId('shadow-prices-unavailable')).not.toBeInTheDocument();
  });

  it('gives the reason when the prices were not computed', () => {
    renderPanel({ status: 'SKIPPED', reason: 'Too many lots for the linear program' });
    const alert = screen.getByTestId('shadow-prices-unavailable');
    expect(alert).toHaveTextContent('Skipped');
    expect(alert).toHaveTextContent('Too many lots for the linear program');
    expect(screen.queryByTestId('shadow-kpis')).not.toBeInTheDocument();
  });
});
