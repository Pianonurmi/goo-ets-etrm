import { useUrlState } from '@/shared/useUrlState';
import { Paper, Select, Stack, Text } from '@mantine/core';
import type { ColDef, ICellRendererParams } from 'ag-grid-community';
import { useMemo } from 'react';
import { useTranslation } from 'react-i18next';
import { useNavigate } from 'react-router';
import { $api } from '@/shared/api/client';
import { EmptyState } from '@/shared/components/EmptyState';
import { QueryState } from '@/shared/components/QueryState';
import { TableSkeleton } from '@/shared/components/Skeletons';
import { StatusBadge } from '@/shared/components/StatusBadge';
import { formatDateTime } from '@/shared/format/format';
import { DataGrid } from '@/shared/grid/DataGrid';

import {
  BREAK_STATUSES,
  affectedContracts,
  differenceKinds,
  newestFirst,
  statusFilter,
  type Account,
  type ClearingBreak,
} from './model/clearing';

/** Differences between the member's statements and our margin runs, open ones first by default. */
export function BreaksTab({ entityId, account }: { entityId: string; account: Account | null }) {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const { params, set } = useUrlState();
  const status = statusFilter(params.get('status'), BREAK_STATUSES, 'OPEN');
  const breaks = $api.useQuery('get', '/api/v1/clearing/breaks', {
    params: { query: { legalEntityId: entityId, status: status ?? undefined } },
  });
  const rows = useMemo(
    () => newestFirst((breaks.data ?? []).filter((brk) => !account || brk.accountId === account.id)),
    [breaks.data, account],
  );
  const columns = useMemo<ColDef<ClearingBreak>[]>(
    () => [
      { field: 'businessDate', headerName: t('clearing.businessDate'), width: 140, sort: 'desc' },
      {
        field: 'status',
        headerName: t('allocation.status'),
        width: 140,
        cellRenderer: (p: ICellRendererParams<ClearingBreak>) => p.data && <StatusBadge status={p.data.status} />,
      },
      {
        colId: 'kinds',
        headerName: t('clearing.differencesColumn'),
        flex: 1,
        minWidth: 240,
        valueGetter: (p) =>
          p.data
            ? differenceKinds(p.data)
                .map((kind) => t(`clearing.differenceKinds.${kind}`))
                .join(', ')
            : '',
      },
      {
        colId: 'contracts',
        headerName: t('clearing.contracts'),
        width: 200,
        valueGetter: (p) => (p.data ? affectedContracts(p.data).join(', ') : ''),
      },
      { field: 'openedBy', headerName: t('clearing.openedBy'), width: 140 },
      {
        field: 'openedAt',
        headerName: t('clearing.openedAt'),
        width: 160,
        valueFormatter: (p) => formatDateTime(p.value as string),
      },
      { field: 'closedBy', headerName: t('clearing.closedBy'), width: 140 },
      { field: 'resolution', headerName: t('clearing.resolution'), width: 260, tooltipField: 'resolution' },
    ],
    [t],
  );
  return (
    <Stack gap="sm">
      <Select
        aria-label={t('clearing.statusFilter')}
        data={[
          { value: 'ALL', label: t('clearing.anyStatus') },
          ...BREAK_STATUSES.map((value) => ({ value, label: t(`clearing.breakStatuses.${value}`) })),
        ]}
        value={status ?? 'ALL'}
        onChange={(value) => set({ status: value === 'OPEN' ? null : value })}
        allowDeselect={false}
        w={220}
        data-testid="clearing-breaks-status"
      />
      <Text size="sm" c="dimmed" maw={760}>
        {t('clearing.breaksHint')}
      </Text>
      <QueryState isPending={false} error={breaks.error}>
        {breaks.isPending ? (
          <Paper withBorder radius="lg">
            <TableSkeleton rows={4} />
          </Paper>
        ) : rows.length === 0 ? (
          <Paper withBorder radius="lg">
            <EmptyState
              title={status === 'OPEN' ? t('clearing.noOpenBreaks') : t('clearing.noBreaks')}
              description={t('clearing.noBreaksHint')}
              testId="clearing-breaks-empty"
            />
          </Paper>
        ) : (
          <div data-testid="clearing-breaks-grid">
            <DataGrid<ClearingBreak>
              rowData={rows}
              columnDefs={columns}
              rowId={(row) => row.id}
              height={Math.min(480, 46 + rows.length * 40 + 2)}
              onRowClicked={(event) => event.data && void navigate(`/clearing/breaks/${event.data.id}`)}
              rowStyle={{ cursor: 'pointer' }}
            />
          </div>
        )}
      </QueryState>
    </Stack>
  );
}
