import { useComputedColorScheme } from '@mantine/core';
import {
  AllCommunityModule,
  ModuleRegistry,
  colorSchemeDark,
  colorSchemeLight,
  themeQuartz,
  type ColDef,
  type GridOptions,
} from 'ag-grid-community';
import { AgGridReact, type AgGridReactProps } from 'ag-grid-react';
import { useMemo } from 'react';

ModuleRegistry.registerModules([AllCommunityModule]);

const lightTheme = themeQuartz.withPart(colorSchemeLight).withParams({ fontFamily: 'inherit', headerFontWeight: 600 });
const darkTheme = themeQuartz.withPart(colorSchemeDark).withParams({ fontFamily: 'inherit', headerFontWeight: 600 });

const baseColDef = { sortable: true, resizable: true, filter: true, minWidth: 90 } as const;

export type DataGridProps<Row> = AgGridReactProps<Row> & {
  /** Height of the grid; grids fill their container otherwise. */
  height?: number | string;
  /** Stable row ids keep selection and scroll position across refetches. */
  rowId?: (row: Row) => string;
};

/** AG Grid with the application's theme, defaults and empty state (ADR 0025). */
export function DataGrid<Row>({ height = 520, rowId, defaultColDef: overrides, ...props }: DataGridProps<Row>) {
  const scheme = useComputedColorScheme('light');
  const columnDefaults = useMemo<ColDef<Row>>(() => ({ ...baseColDef, ...overrides }), [overrides]);
  const getRowId = useMemo<GridOptions<Row>['getRowId']>(
    () => (rowId ? (params) => rowId(params.data) : undefined),
    [rowId],
  );
  return (
    <div style={{ height, width: '100%' }}>
      <AgGridReact<Row>
        theme={scheme === 'dark' ? darkTheme : lightTheme}
        defaultColDef={columnDefaults}
        getRowId={getRowId}
        animateRows={false}
        enableCellTextSelection
        ensureDomOrder
        {...props}
      />
    </div>
  );
}
