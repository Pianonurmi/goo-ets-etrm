import { useComputedColorScheme } from '@mantine/core';
import { useDensity } from '@/shared/context/preferences';
import {
  AllCommunityModule,
  ModuleRegistry,
  colorSchemeDark,
  colorSchemeLight,
  themeQuartz,
  type CellKeyDownEvent,
  type ColDef,
  type FullWidthCellKeyDownEvent,
  type GridOptions,
  type RowClickedEvent,
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
const DEFAULT_HEIGHT = 520;

const baseColDef = { sortable: true, resizable: true, filter: true, minWidth: 90 } as const;

export type DataGridProps<Row> = AgGridReactProps<Row> & {
  /** Height of the grid; grids fill their container otherwise. */
  height?: number | string;
  /** Stable row ids keep selection and scroll position across refetches. */
  rowId?: (row: Row) => string;
  /**
   * Sizes the grid to its rows, up to this many before it scrolls inside. Leaves room for the horizontal scrollbar
   * (AG Grid's `autoHeight` hides the last row behind it) and for the empty-state overlay. Ignored with `height`.
   */
  fitRows?: number;
};

/** Height for `rows` rows at `rowHeight`, at most `maxRows` and at least room for the empty overlay. */
export function fittedHeight(rows: number, maxRows: number, rowHeight: number): number {
  const shown = Math.min(Math.max(rows, 3), maxRows);
  return rowHeight + 4 + shown * rowHeight + 2 + 16;
}

/** Enter and Space open the focused row, as a click does (review finding W3). */
export function opensRow(event: Event | null | undefined): boolean {
  if (!(event instanceof KeyboardEvent) || event.altKey || event.ctrlKey || event.metaKey) return false;
  return event.key === 'Enter' || event.key === ' ';
}

/**
 * AG Grid with the application's theme, defaults and empty state (ADR 0025). A grid whose rows open on click opens
 * them from the keyboard too, so a keyboard-only user can reach every detail page.
 */
export function DataGrid<Row>({ height, rowId, fitRows, defaultColDef: overrides, ...props }: DataGridProps<Row>) {
  const scheme = useComputedColorScheme('light');
  const density = useDensity();
  const columnDefaults = useMemo<ColDef<Row>>(() => ({ ...baseColDef, ...overrides }), [overrides]);
  const getRowId = useMemo<GridOptions<Row>['getRowId']>(
    () => (rowId ? (params) => rowId(params.data) : undefined),
    [rowId],
  );
  const keyDown = (event: CellKeyDownEvent<Row> | FullWidthCellKeyDownEvent<Row>) => {
    props.onCellKeyDown?.(event);
    if (props.onRowClicked && opensRow(event.event)) {
      event.event?.preventDefault();
      props.onRowClicked({ ...event, type: 'rowClicked' } as RowClickedEvent<Row>);
    }
  };
  return (
    <div
      style={{
        height:
          height ?? (fitRows ? fittedHeight(props.rowData?.length ?? 0, fitRows, ROW_HEIGHT[density]) : DEFAULT_HEIGHT),
        width: '100%',
      }}
    >
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
        onCellKeyDown={keyDown}
      />
    </div>
  );
}
