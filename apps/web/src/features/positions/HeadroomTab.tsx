import { Alert, Stack, Text } from '@mantine/core';
import type { ColDef } from 'ag-grid-community';
import { useMemo } from 'react';
import { useTranslation } from 'react-i18next';
import { $api } from '@/shared/api/client';
import { EmptyState } from '@/shared/components/EmptyState';
import { QueryState } from '@/shared/components/QueryState';
import { TableSkeleton } from '@/shared/components/Skeletons';
import { formatDate, formatDateTime, formatDecimal } from '@/shared/format/format';
import { DataGrid } from '@/shared/grid/DataGrid';
import { dateField, headroomRows, type HeadroomGridRow, type HeadroomTable } from './model/headroom';

const mwh = (p: { value: unknown }) => formatDecimal(p.value as number);

/** The grid of a headroom table: bucket, vintage, held, then one column per delivery date. */
export function HeadroomGrid({ table }: { table: HeadroomTable }) {
  const { t } = useTranslation();
  const rows = useMemo(() => headroomRows(table), [table]);
  const columns = useMemo<ColDef<HeadroomGridRow>[]>(
    () => [
      { field: 'bucketCode', headerName: t('positions.bucket'), flex: 1, minWidth: 180 },
      { field: 'vintageYear', headerName: t('positions.vintage'), width: 110 },
      {
        field: 'heldMwh',
        headerName: t('positions.headroom.held'),
        width: 120,
        type: 'rightAligned',
        valueFormatter: mwh,
      },
      ...table.deliveryDates.map((date, index): ColDef<HeadroomGridRow> => ({
        field: dateField(index),
        headerName: formatDate(date),
        width: 130,
        type: 'rightAligned',
        valueFormatter: mwh,
      })),
    ],
    [t, table.deliveryDates],
  );
  return (
    <div data-testid="headroom-table">
      <DataGrid<HeadroomGridRow>
        rowData={rows}
        columnDefs={columns}
        rowId={(row) => row.key}
        fitRows={12}
        overlayNoRowsTemplate={t('common.empty')}
      />
    </div>
  );
}

/** What the entity can still sell, by bucket and vintage, for each delivery month-end (upper bounds that share supply). */
export function HeadroomTab({ entityId }: { entityId: string }) {
  const { t } = useTranslation();
  // The first call for an entity prepares the figures, so it can take a moment.
  const table = $api.useQuery(
    'get',
    '/api/v1/allocation/headroom',
    { params: { query: { holderEntityId: entityId } } },
    { retry: false },
  );
  const data = table.data;
  return (
    <Stack>
      <Text size="sm" c="dimmed">
        {t('positions.headroom.lead')}
      </Text>
      <Alert color="gray" variant="light" data-testid="headroom-note">
        {t('positions.headroom.note')}
      </Alert>
      <QueryState isPending={false} error={table.error}>
        {!data ? (
          <TableSkeleton rows={5} />
        ) : data.rows.length === 0 ? (
          <EmptyState title={t('positions.headroom.empty')} description={t('positions.headroom.emptyHint')} />
        ) : (
          <>
            <HeadroomGrid table={data} />
            <Text size="xs" c="dimmed">
              {t('positions.headroom.computedAt', { time: formatDateTime(data.computedAt) })}
            </Text>
          </>
        )}
      </QueryState>
    </Stack>
  );
}
