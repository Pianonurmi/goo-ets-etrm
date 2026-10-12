import { MantineProvider } from '@mantine/core';
import { render, screen, within } from '@testing-library/react';
import type { ColDef } from 'ag-grid-community';
import { HeadroomGrid } from './HeadroomTab';
import type { HeadroomGridRow, HeadroomTable } from './model/headroom';

// AG Grid needs a real layout; the view's own job is which columns and cells it hands to the grid.
vi.mock('@/shared/grid/DataGrid', () => ({
  DataGrid: ({ rowData, columnDefs }: { rowData: HeadroomGridRow[]; columnDefs: ColDef<HeadroomGridRow>[] }) => (
    <table>
      <thead>
        <tr>
          {columnDefs.map((column) => (
            <th key={column.field}>{column.headerName}</th>
          ))}
        </tr>
      </thead>
      <tbody>
        {rowData.map((row) => (
          <tr key={row.key} data-testid={`row-${row.key}`}>
            {columnDefs.map((column) => {
              const value = (row as Record<string, unknown>)[column.field as string];
              const format = column.valueFormatter as ((p: { value: unknown }) => string) | undefined;
              return <td key={column.field}>{format ? format({ value }) : String(value)}</td>;
            })}
          </tr>
        ))}
      </tbody>
    </table>
  ),
}));

const table: HeadroomTable = {
  holderEntityId: '00000000-0000-7000-8000-000000000001',
  stamp: 's1',
  tier: 'HELD',
  computedAt: '2026-10-12T08:00:00Z',
  deliveryDates: ['2026-10-31', '2026-11-30', '2026-12-31'],
  rows: [
    { bucketCode: 'FI_HYDRO', vintageYear: 2025, heldMwh: 12500, futureMwh: 0, headroomMwh: [12500, 11000, 9000] },
    { bucketCode: 'SE_WIND', vintageYear: 2026, heldMwh: 800, futureMwh: 0, headroomMwh: [800, 800, 0] },
  ],
};

describe('HeadroomGrid', () => {
  it('has a column for each delivery date after bucket, vintage and held', () => {
    render(
      <MantineProvider>
        <HeadroomGrid table={table} />
      </MantineProvider>,
    );
    const grid = screen.getByTestId('headroom-table');
    const headers = within(grid)
      .getAllByRole('columnheader')
      .map((header) => header.textContent);
    expect(headers).toEqual(['Bucket', 'Vintage', 'Held', '2026-10-31', '2026-11-30', '2026-12-31']);
  });

  it('shows held and headroom per bucket and vintage', () => {
    render(
      <MantineProvider>
        <HeadroomGrid table={table} />
      </MantineProvider>,
    );
    const hydro = within(screen.getByTestId('row-FI_HYDRO-2025'))
      .getAllByRole('cell')
      .map((cell) => cell.textContent);
    expect(hydro).toEqual(['FI_HYDRO', '2025', '12,500', '12,500', '11,000', '9,000']);
    expect(screen.getByTestId('row-SE_WIND-2026')).toHaveTextContent('SE_WIND20268008008000');
  });
});
