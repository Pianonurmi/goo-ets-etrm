import { MantineProvider } from '@mantine/core';
import { fireEvent, render, screen, within } from '@testing-library/react';
import type { ColDef, ICellRendererParams } from 'ag-grid-community';
import { PortfolioGrid } from './PortfolioTab';
import type { PortfolioNode, PortfolioRow, PortfolioView } from './model/portfolio';

// AG Grid needs a real layout; the view's own job is which columns and cells it hands to the grid.
vi.mock('@/shared/grid/DataGrid', () => ({
  DataGrid: ({ rowData, columnDefs }: { rowData: PortfolioRow[]; columnDefs: ColDef<PortfolioRow>[] }) => (
    <table>
      <thead>
        <tr>
          {columnDefs.map((column) => (
            <th key={column.colId}>{column.headerName}</th>
          ))}
        </tr>
      </thead>
      <tbody>
        {rowData.map((row) => (
          <tr key={row.key} data-testid={`row-${row.key}`}>
            {columnDefs.map((column) => {
              const params = { data: row, value: undefined } as unknown as ICellRendererParams<PortfolioRow>;
              const getter = column.valueGetter as ((p: unknown) => unknown) | undefined;
              const value = getter ? getter(params) : undefined;
              const format = column.valueFormatter as ((p: { value: unknown }) => string) | undefined;
              const render = column.cellRenderer as ((p: ICellRendererParams<PortfolioRow>) => unknown) | undefined;
              return (
                <td key={column.colId}>
                  {render ? (render(params) as never) : format ? format({ value }) : String(value)}
                </td>
              );
            })}
          </tr>
        ))}
      </tbody>
    </table>
  ),
}));

const node = (bucketCode: string, parents: string[], extra: Partial<PortfolioNode> = {}): PortfolioNode => ({
  bucketCode,
  name: bucketCode,
  aliases: [],
  parents,
  depth: 0,
  longMwh: 0,
  shortMwh: 0,
  coveredWithinMwh: 0,
  shortAfterNettingMwh: 0,
  naiveNetMwh: 0,
  freeMwh: 0,
  availableToSellMwh: 0,
  ...extra,
});

const view: PortfolioView = {
  holderEntityId: '00000000-0000-7000-8000-000000000001',
  stamp: 's1',
  computedAt: '2026-10-12T08:00:00Z',
  currency: 'EUR',
  nodes: [
    node('ANY_GOO', [], { longMwh: 12500, shortMwh: 1000, coveredWithinMwh: 1000, naiveNetMwh: 11500, freeMwh: 11500 }),
    node('EU_RENEWABLE', ['ANY_GOO'], {
      longMwh: 9000,
      markEur: 1.2,
      cheapestToDeliverEur: 1.5,
      giveAwayPerMwhEur: 0.3,
    }),
    node('EU_HYDRO', ['EU_RENEWABLE'], {
      longMwh: 800,
      shortMwh: 1200,
      coveredWithinMwh: 0,
      shortAfterNettingMwh: 1200,
      naiveNetMwh: -400,
    }),
    node('NORDIC_RENEWABLE', ['EU_RENEWABLE'], {
      aliases: ['NORDIC_RENEWABLE_TWIN'],
      longMwh: 7000,
      shortMwh: 600,
      shortAfterNettingMwh: 600,
      naiveNetMwh: 6400,
    }),
    node('NORDIC_HYDRO', ['EU_HYDRO', 'NORDIC_RENEWABLE'], { longMwh: 500 }),
  ],
};

function renderGrid() {
  return render(
    <MantineProvider>
      <PortfolioGrid view={view} />
    </MantineProvider>,
  );
}

describe('PortfolioGrid', () => {
  it('has the columns of the portfolio after the product', () => {
    renderGrid();
    const headers = within(screen.getByTestId('goo-hierarchy'))
      .getAllByRole('columnheader')
      .map((header) => header.textContent);
    expect(headers).toEqual([
      'Product',
      'Long',
      'Short',
      'Covered within',
      'Short after netting',
      'Naive net',
      'Free',
      'Available to sell',
      'Mark (EUR/MWh)',
      'Cheapest to deliver (EUR/MWh)',
      'Give-away (EUR/MWh)',
    ]);
  });

  it('opens the first two levels, with figures formatted and missing prices as a dash', () => {
    renderGrid();
    expect(screen.queryByTestId('row-ANY_GOO>EU_RENEWABLE>EU_HYDRO>NORDIC_HYDRO')).toBeNull();
    const root = within(screen.getByTestId('row-ANY_GOO'))
      .getAllByRole('cell')
      .map((cell) => cell.textContent);
    expect(root).toEqual(['ANY_GOOANY_GOO', '12,500', '1,000', '1,000', '0', '11,500', '11,500', '0', '—', '—', '—']);
    const renewable = within(screen.getByTestId('row-ANY_GOO>EU_RENEWABLE'))
      .getAllByRole('cell')
      .map((cell) => cell.textContent);
    expect(renewable.slice(-3)).toEqual(['1.20', '1.50', '0.30']);
  });

  it('shows a short that remains after netting in the warning tone, and only then', () => {
    renderGrid();
    const hydro = screen.getByTestId('row-ANY_GOO>EU_RENEWABLE>EU_HYDRO');
    expect(hydro.querySelector('[data-warning="true"]')).toHaveTextContent('1,200');
    expect(screen.getByTestId('row-ANY_GOO').querySelector('[data-warning]')).toBeNull();
  });

  it('opens a branch with its toggle and lists a node with two parents under both, marking the second', () => {
    renderGrid();
    fireEvent.click(screen.getByTestId('portfolio-toggle-EU_HYDRO'));
    fireEvent.click(screen.getByTestId('portfolio-toggle-NORDIC_RENEWABLE'));
    const first = screen.getByTestId('row-ANY_GOO>EU_RENEWABLE>EU_HYDRO>NORDIC_HYDRO');
    const second = screen.getByTestId('row-ANY_GOO>EU_RENEWABLE>NORDIC_RENEWABLE>NORDIC_HYDRO');
    expect(within(first).queryByText(/also under/)).toBeNull();
    expect(within(second).getByText('also under')).toBeVisible();
    expect(within(first).getAllByText('500').length).toBeGreaterThan(0);
    expect(within(second).getAllByText('500').length).toBeGreaterThan(0);
  });

  it('closes a branch again', () => {
    renderGrid();
    expect(screen.getByTestId('row-ANY_GOO>EU_RENEWABLE')).toBeInTheDocument();
    fireEvent.click(screen.getByTestId('portfolio-toggle-EU_RENEWABLE'));
    expect(screen.queryByTestId('row-ANY_GOO>EU_RENEWABLE>EU_HYDRO')).toBeNull();
    expect(screen.getByTestId('portfolio-toggle-EU_RENEWABLE')).toHaveAttribute('aria-expanded', 'false');
  });
});
