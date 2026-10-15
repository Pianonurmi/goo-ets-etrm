import { Button, Group, Paper, SegmentedControl, Select, Stack, Text } from '@mantine/core';
import type { ColDef, ICellRendererParams } from 'ag-grid-community';
import { useMemo } from 'react';
import { useTranslation } from 'react-i18next';
import { useNavigate } from 'react-router';
import { $api } from '@/shared/api/client';
import { EmptyState } from '@/shared/components/EmptyState';
import { QueryState } from '@/shared/components/QueryState';
import { TableSkeleton } from '@/shared/components/Skeletons';
import { statusLabel } from '@/shared/components/StatusBadge';
import { formatDateTime } from '@/shared/format/format';
import { DataGrid } from '@/shared/grid/DataGrid';
import { oneOfOrNull } from '@/shared/urlParams';
import { useUrlState } from '@/shared/useUrlState';
import { QUEUE_VIEWS, asQueueView, queueStatuses, subject, type Instruction } from './model/instructions';
import { InstructionStatusBadge } from './shared';
import { useRegistries } from './TransferModal';

export const ASSET_KINDS: NonNullable<Instruction['assetKind']>[] = ['GOO_LOT', 'ALLOWANCE'];

export const PAGE = 100;

export function InstructionsTab({ entityId }: { entityId: string }) {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const { params, set: patchUrl } = useUrlState();
  const registries = useRegistries();
  const view = asQueueView(params.get('view'));
  const registryCode = params.get('registry');
  const assetKind = oneOfOrNull(params.get('asset'), ASSET_KINDS);
  const set = (key: string, value: string | null) => patchUrl({ [key]: value });
  const search = $api.useInfiniteQuery(
    'get',
    '/api/v1/registry/instructions/search',
    {
      params: {
        query: {
          holderEntityId: entityId,
          status: queueStatuses(view),
          registryCode: registryCode ?? undefined,
          assetKind: assetKind ?? undefined,
          limit: PAGE,
        },
      },
    },
    {
      initialPageParam: null as string | null,
      getNextPageParam: (page) => page.nextCursor ?? undefined,
      pageParamName: 'cursor',
    },
  );
  const rows = useMemo(() => search.data?.pages.flatMap((page) => page.items) ?? [], [search.data]);
  const columns = useMemo<ColDef<Instruction>[]>(
    () => [
      { field: 'instructionKey', headerName: t('registry.key'), width: 220, cellStyle: { fontWeight: 600 } },
      {
        field: 'status',
        headerName: t('allocation.status'),
        width: 160,
        cellRenderer: (p: ICellRendererParams<Instruction>) =>
          p.data && <InstructionStatusBadge status={p.data.status} />,
      },
      {
        field: 'type',
        headerName: t('registry.type'),
        width: 170,
        valueFormatter: (p) => t(`registry.types.${String(p.value)}`),
      },
      {
        colId: 'subject',
        headerName: t('registry.what'),
        width: 180,
        valueGetter: (p) => (p.data ? subject(p.data) : ''),
      },
      {
        colId: 'route',
        headerName: t('registry.route'),
        flex: 1,
        minWidth: 220,
        valueGetter: (p) =>
          p.data ? `${p.data.registryCode} → ${p.data.toRegistryCode ?? ''} ${p.data.toAccountRef ?? ''}`.trim() : '',
      },
      {
        field: 'channel',
        headerName: t('registry.channel'),
        width: 120,
        valueFormatter: (p) => (p.value ? statusLabel(String(p.value)) : ''),
      },
      { field: 'attempts', headerName: t('registry.attempts'), width: 100, type: 'rightAligned' },
      { field: 'lastError', headerName: t('registry.lastError'), width: 240, tooltipField: 'lastError' },
      {
        field: 'expectedEffectiveAt',
        headerName: t('registry.expectedEffective'),
        width: 170,
        valueFormatter: (p) => formatDateTime(p.value as string | null),
      },
      {
        field: 'createdAt',
        headerName: t('registry.submitted'),
        width: 160,
        valueFormatter: (p) => formatDateTime(p.value as string),
      },
      { field: 'submittedBy', headerName: t('registry.submittedBy'), width: 130 },
    ],
    [t],
  );
  return (
    <Stack gap="sm">
      <Group align="flex-end" gap="sm">
        <SegmentedControl
          value={view}
          onChange={(value) => set('view', value === 'open' ? null : value)}
          data={QUEUE_VIEWS.map((value) => ({ value, label: t(`registry.views.${value}`) }))}
          data-testid="queue-view"
        />
        <Select
          aria-label={t('registry.registry')}
          placeholder={t('registry.allRegistries')}
          data={(registries.data ?? []).map((registry) => ({ value: registry.code, label: registry.code }))}
          value={registryCode}
          onChange={(value) => set('registry', value)}
          clearable
          w={200}
        />
        <Select
          aria-label={t('registry.assetKind')}
          placeholder={t('registry.allAssets')}
          data={[
            { value: 'GOO_LOT', label: t('registry.assets.GOO_LOT') },
            { value: 'ALLOWANCE', label: t('registry.assets.ALLOWANCE') },
          ]}
          value={assetKind}
          onChange={(value) => set('asset', value)}
          clearable
          w={200}
        />
      </Group>
      <QueryState isPending={false} error={search.error}>
        {search.isPending ? (
          <Paper withBorder radius="lg">
            <TableSkeleton rows={8} />
          </Paper>
        ) : rows.length === 0 ? (
          <Paper withBorder radius="lg">
            <EmptyState
              title={t(`registry.empty.${view}`)}
              description={t('registry.emptyHint')}
              testId="instructions-empty"
            />
          </Paper>
        ) : (
          <DataGrid<Instruction>
            rowData={rows}
            columnDefs={columns}
            rowId={(row) => row.id}
            height={Math.min(520, 46 + rows.length * 40 + 2)}
            onRowClicked={(event) => event.data && void navigate(`/registry/instructions/${event.data.id}`)}
            rowStyle={{ cursor: 'pointer' }}
          />
        )}
        <Group justify="space-between">
          <Text size="sm" c="dimmed" data-testid="instruction-count">
            {t('registry.shown', { count: rows.length })}
          </Text>
          {search.hasNextPage && (
            <Button variant="light" loading={search.isFetchingNextPage} onClick={() => void search.fetchNextPage()}>
              {t('inbox.loadMore')}
            </Button>
          )}
        </Group>
      </QueryState>
    </Stack>
  );
}
