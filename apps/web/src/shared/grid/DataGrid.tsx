import { useComputedColorScheme } from '@mantine/core';
import { useDensity } from '@/shared/context/preferences';
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

/** The grid in Provena's colours (ADR 0034): slate lines, teal accent, quiet headers, tabular figures. */
const shared = {
  fontFamily: 'inherit',
  fontSize: 13,
  headerFontSize: 12,
  headerFontWeight: 600,
  borderRadius: 8,
  wrapperBorderRadius: 12,
  cellHorizontalPadding: 12,
  columnBorder: false,
} as const;
const lightTheme = themeQuartz.withPart(colorSchemeLight).withParams({
  ...shared,
  accentColor: '#0E6E7E',
  borderColor: '#E2E8F0',
  foregroundColor: '#0F172A',
  headerTextColor: '#475569',
  headerBackgroundColor: '#F8FAFC',
  rowHoverColor: '#F1F5F9',
  selectedRowBackgroundColor: '#E6F4F5',
});
const darkTheme = themeQuartz.withPart(colorSchemeDark).withParams({
  ...shared,
  accentColor: '#3BB6C9',
  backgroundColor: '#111A2B',
  borderColor: '#22304A',
  foregroundColor: '#E2E8F0',
  headerTextColor: '#94A3B8',
  headerBackgroundColor: '#0B1220',
  rowHoverColor: '#1A2538',
  selectedRowBackgroundColor: 'rgba(59, 182, 201, 0.14)',
});
const ROW_HEIGHT = { comfortable: 40, compact: 32 } as const;

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
  const density = useDensity();
  const columnDefaults = useMemo<ColDef<Row>>(() => ({ ...baseColDef, ...overrides }), [overrides]);
  const getRowId = useMemo<GridOptions<Row>['getRowId']>(
    () => (rowId ? (params) => rowId(params.data) : undefined),
    [rowId],
  );
  return (
    <div style={{ height, width: '100%' }}>
      <AgGridReact<Row>
        theme={scheme === 'dark' ? darkTheme : lightTheme}
        rowHeight={ROW_HEIGHT[density]}
        headerHeight={ROW_HEIGHT[density] + 4}
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
