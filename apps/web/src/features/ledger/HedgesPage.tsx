import { useUrlState } from '@/shared/useUrlState';
import { Alert, Button, Group, Paper, Select, SimpleGrid, Stack, Text } from '@mantine/core';
import { IconPlus } from '@tabler/icons-react';
import type { ColDef, ICellRendererParams } from 'ag-grid-community';
import { useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useNavigate } from 'react-router';
import { $api } from '@/shared/api/client';
import { Can } from '@/shared/auth/Can';
import { EmptyState } from '@/shared/components/EmptyState';
import { KpiCard } from '@/shared/components/KpiCard';
import { PageHeader } from '@/shared/components/PageHeader';
import { QueryState } from '@/shared/components/QueryState';
import { TableSkeleton } from '@/shared/components/Skeletons';
import { StatusBadge } from '@/shared/components/StatusBadge';
import { useEntity } from '@/shared/context/EntityContext';
import { formatDate, formatPercent, formatQuantity } from '@/shared/format/format';
import { DataGrid } from '@/shared/grid/DataGrid';
import { HedgeDesignateModal } from './HedgeDesignateModal';
import { HEDGE_STATUSES, hedgeCounts, hedgeProduct, type Hedge } from './model/hedge';

/** Headline counts: what is active, what waits for a second accountant, what is being discontinued. */
function HedgeKpis({ hedges, loading }: { hedges: readonly Hedge[]; loading: boolean }) {
  const { t } = useTranslation();
  const counts = hedgeCounts(hedges);
  return (
    <SimpleGrid cols={{ base: 1, xs: 2, lg: 3 }} spacing="md" data-testid="hedge-kpis">
      <KpiCard
        label={t('hedges.kpi.active')}
        value={counts.active}
        loading={loading}
        trend={t('hedges.kpi.activeHint')}
        to="/ledger/hedges?status=ACTIVE"
        testId="hedge-kpi-active"
      />
      <KpiCard
        label={t('hedges.kpi.pending')}
        value={counts.pending}
        loading={loading}
        trend={counts.pending > 0 ? t('hedges.kpi.pendingHint') : t('hedges.kpi.nonePending')}
        trendTone={counts.pending > 0 ? 'warning' : 'neutral'}
        to="/ledger/hedges?status=PENDING"
        testId="hedge-kpi-pending"
      />
      <KpiCard
        label={t('hedges.kpi.discontinuing')}
        value={counts.discontinuing}
        loading={loading}
        trend={counts.discontinuing > 0 ? t('hedges.kpi.discontinuingHint') : t('hedges.kpi.noneDiscontinuing')}
        trendTone={counts.discontinuing > 0 ? 'warning' : 'neutral'}
        testId="hedge-kpi-discontinuing"
      />
    </SimpleGrid>
  );
}

/** Cash flow hedge relationships of the selected legal entity (ADR 0045): designations, status and ratio. */
export function HedgesPage() {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const { entity } = useEntity();
  const { params, set: patchUrl } = useUrlState();
  const [designating, setDesignating] = useState(false);
  const requested = params.get('status');
  const status = HEDGE_STATUSES.find((value) => value === requested) ?? null;
  const hedges = $api.useQuery(
    'get',
    '/api/v1/ledger/hedges',
    { params: { query: { legalEntityId: entity?.id ?? '' } } },
    { enabled: Boolean(entity) },
  );
  const all = useMemo(() => hedges.data ?? [], [hedges.data]);
  const rows = useMemo(() => (status ? all.filter((hedge) => hedge.status === status) : all), [all, status]);

  const columns = useMemo<ColDef<Hedge>[]>(
    () => [
      {
        field: 'number',
        headerName: t('hedges.columns.number'),
        width: 130,
        pinned: 'left',
        cellStyle: { fontWeight: 600 },
      },
      {
        colId: 'direction',
        headerName: t('hedges.columns.direction'),
        width: 120,
        valueGetter: (p) => (p.data ? t(`hedges.directions.${p.data.item.direction}`) : ''),
      },
      {
        colId: 'product',
        headerName: t('hedges.columns.product'),
        flex: 1,
        minWidth: 200,
        valueGetter: (p) => (p.data ? hedgeProduct(p.data.item) : ''),
      },
      {
        colId: 'quantity',
        headerName: t('hedges.columns.quantity'),
        width: 160,
        type: 'rightAligned',
        valueGetter: (p) =>
          p.data ? formatQuantity(p.data.item.quantity, t(`hedges.units.${p.data.item.productFamily}`)) : '',
      },
      {
        colId: 'expectedDate',
        headerName: t('hedges.columns.expectedDate'),
        width: 140,
        valueGetter: (p) => (p.data ? formatDate(p.data.item.expectedDate) : ''),
      },
      {
        colId: 'ratio',
        headerName: t('hedges.columns.ratio'),
        width: 130,
        type: 'rightAligned',
        valueGetter: (p) => (p.data ? formatPercent(p.data.hedgeRatio) : ''),
      },
      {
        colId: 'instruments',
        headerName: t('hedges.columns.instruments'),
        width: 200,
        valueGetter: (p) => (p.data ? p.data.instruments.map((instrument) => instrument.tradeNumber).join(', ') : ''),
      },
      {
        field: 'status',
        headerName: t('hedges.columns.status'),
        width: 190,
        cellRenderer: (p: ICellRendererParams<Hedge>) =>
          p.data ? <StatusBadge status={p.data.status} label={t(`hedges.statuses.${p.data.status}`)} /> : null,
      },
      {
        colId: 'designationDate',
        headerName: t('hedges.columns.designationDate'),
        width: 140,
        valueGetter: (p) => (p.data ? formatDate(p.data.designationDate) : ''),
      },
    ],
    [t],
  );

  const setStatus = (value: string | null) => patchUrl({ status: value });
  const designate = (
    <Can op="hedge_requestDesignation">
      <Button leftSection={<IconPlus size={16} />} onClick={() => setDesignating(true)} data-testid="hedge-designate">
        {t('hedges.designate')}
      </Button>
    </Can>
  );

  return (
    <Stack gap="lg">
      <PageHeader
        breadcrumbs={[{ label: t('ledger.title'), to: '/ledger' }]}
        title={t('hedges.title')}
        description={t('hedges.description', { entity: entity?.code ?? '' })}
        actions={designate}
      />
      {!entity ? (
        <Alert color="gray">{t('header.noEntity')}</Alert>
      ) : (
        <>
          <HedgeKpis hedges={all} loading={hedges.isPending} />
          <Group align="flex-end" gap="sm">
            <Select
              label={t('hedges.status')}
              data={HEDGE_STATUSES.map((value) => ({ value, label: t(`hedges.statuses.${value}`) }))}
              value={status}
              onChange={setStatus}
              placeholder={t('hedges.allStatuses')}
              clearable
              w={240}
              data-testid="hedge-status-filter"
            />
          </Group>
          <QueryState isPending={false} error={hedges.error}>
            {hedges.isPending ? (
              <Paper withBorder radius="lg">
                <TableSkeleton rows={6} />
              </Paper>
            ) : rows.length === 0 ? (
              <Paper withBorder radius="lg">
                <EmptyState
                  title={status ? t('hedges.emptyFiltered') : t('hedges.empty')}
                  description={status ? t('hedges.emptyFilteredHint') : t('hedges.emptyHint')}
                  testId="hedges-empty"
                  action={
                    status ? (
                      <Button variant="light" onClick={() => setStatus(null)}>
                        {t('hedges.showAll')}
                      </Button>
                    ) : (
                      designate
                    )
                  }
                />
              </Paper>
            ) : (
              <Stack gap="xs">
                <div data-testid="hedges-grid">
                  <DataGrid<Hedge>
                    rowData={rows}
                    columnDefs={columns}
                    rowId={(row) => row.id}
                    onRowClicked={(event) => {
                      if (event.data) void navigate(`/ledger/hedges/${event.data.id}`);
                    }}
                    rowStyle={{ cursor: 'pointer' }}
                    overlayNoRowsTemplate={t('common.empty')}
                    fitRows={12}
                  />
                </div>
                <Text size="sm" c="dimmed">
                  {t('hedges.shown', { count: rows.length })}
                </Text>
              </Stack>
            )}
          </QueryState>
        </>
      )}
      {designating && entity && <HedgeDesignateModal entityId={entity.id} onClose={() => setDesignating(false)} />}
    </Stack>
  );
}
