import { BarChart } from '@mantine/charts';
import { Alert, Badge, Paper, SimpleGrid, Stack, Text, Title } from '@mantine/core';
import type { ColDef, ICellRendererParams } from 'ag-grid-community';
import { useMemo } from 'react';
import { useTranslation } from 'react-i18next';
import { $api } from '@/shared/api/client';
import { EmptyState } from '@/shared/components/EmptyState';
import { KpiCard } from '@/shared/components/KpiCard';
import { QueryState } from '@/shared/components/QueryState';
import { TableSkeleton } from '@/shared/components/Skeletons';
import { formatDate, formatDateTime, formatDecimal } from '@/shared/format/format';
import { DataGrid } from '@/shared/grid/DataGrid';
import {
  coverageBars,
  isEmptyView,
  sortedItems,
  type FutureSupplyItem,
  type FutureSupplyView,
} from './model/futureSupply';

const mwh = (p: { value: unknown }) => formatDecimal(p.value as number);

/** Contracted purchases and expected issuance, one row each, in the order they become available. */
export function FutureSupplyGrid({ items }: { items: FutureSupplyItem[] }) {
  const { t } = useTranslation();
  const rows = useMemo(() => sortedItems(items), [items]);
  const columns = useMemo<ColDef<FutureSupplyItem>[]>(
    () => [
      {
        field: 'kind',
        headerName: t('positions.future.kind'),
        width: 180,
        cellRenderer: (p: ICellRendererParams<FutureSupplyItem>) =>
          p.data && (
            <Badge
              variant="light"
              color={p.data.kind === 'CONTRACTED' ? 'brand' : 'gray'}
              tt="none"
              radius="xl"
              data-testid={`future-kind-${p.data.kind}`}
            >
              {t(`positions.future.kinds.${p.data.kind}`)}
            </Badge>
          ),
      },
      { field: 'description', headerName: t('positions.future.description'), flex: 1, minWidth: 260 },
      {
        field: 'availableFrom',
        headerName: t('positions.future.availableFrom'),
        width: 150,
        valueFormatter: (p) => formatDate(p.value as string),
      },
      {
        field: 'quantityMwh',
        headerName: t('positions.future.p50'),
        width: 120,
        type: 'rightAligned',
        headerTooltip: t('positions.future.p50Hint'),
        valueFormatter: mwh,
      },
      {
        field: 'p90Mwh',
        headerName: t('positions.future.p90'),
        width: 120,
        type: 'rightAligned',
        headerTooltip: t('positions.future.p90Hint'),
        valueFormatter: mwh,
      },
      {
        field: 'bucketCode',
        headerName: t('positions.bucket'),
        width: 200,
        valueFormatter: (p) => p.data?.bucketCode ?? '—',
      },
    ],
    [t],
  );
  return (
    <div data-testid="future-supply-items">
      <DataGrid<FutureSupplyItem>
        rowData={rows}
        columnDefs={columns}
        rowId={(row) => `${row.kind}-${row.ref}`}
        fitRows={12}
        tooltipShowDelay={400}
        overlayNoRowsTemplate={t('common.empty')}
      />
    </div>
  );
}

function FutureSupplyBody({ data }: { data: FutureSupplyView }) {
  const { t } = useTranslation();
  const bars = useMemo(() => coverageBars(data.months), [data.months]);
  return (
    <Stack>
      <SimpleGrid cols={{ base: 1, xs: 2, lg: 4 }} spacing="md" data-testid="future-supply-kpis">
        <KpiCard label={t('positions.future.demand')} value={formatDecimal(data.totalDemandMwh)} unit="MWh" />
        <KpiCard
          label={t('positions.future.coveredHeld')}
          value={formatDecimal(data.coveredHeldMwh)}
          unit="MWh"
          trend={t('positions.future.coveredHeldHint')}
          trendTone="up"
        />
        <KpiCard
          label={t('positions.future.coveredContracted')}
          value={formatDecimal(data.coveredWithContractedMwh)}
          unit="MWh"
          trend={t('positions.future.coveredContractedHint')}
        />
        <KpiCard
          label={t('positions.future.coveredExpected')}
          value={formatDecimal(data.coveredWithExpectedMwh)}
          unit="MWh"
          trend={t('positions.future.coveredExpectedHint')}
        />
      </SimpleGrid>
      {bars.length > 0 && (
        <Paper withBorder radius="lg" p="lg" data-testid="future-supply-chart">
          <Title order={3} size="h5" mb="md">
            {t('positions.future.chartTitle')}
          </Title>
          <BarChart
            h={280}
            data={bars}
            dataKey="month"
            type="stacked"
            withLegend
            legendProps={{ verticalAlign: 'bottom', height: 28 }}
            series={[
              { name: 'held', label: t('positions.future.series.held'), color: 'teal.7' },
              { name: 'purchases', label: t('positions.future.series.purchases'), color: 'blue.5' },
              { name: 'expected', label: t('positions.future.series.expected'), color: 'grape.4' },
              { name: 'short', label: t('positions.future.series.short'), color: 'red.6' },
            ]}
            valueFormatter={(value) => `${formatDecimal(value)} MWh`}
            tickLine="y"
          />
        </Paper>
      )}
      {data.items.length === 0 ? (
        <EmptyState
          title={t('positions.future.noItems')}
          description={t('positions.future.noItemsHint')}
          testId="future-supply-no-items"
        />
      ) : (
        <FutureSupplyGrid items={data.items} />
      )}
      <Text size="xs" c="dimmed">
        {t('positions.future.computedAt', { time: formatDateTime(data.computedAt) })}
      </Text>
    </Stack>
  );
}

/**
 * What the entity will have beyond its holdings, month by month: purchases agreed but not yet delivered and the
 * issuance expected from production devices, against the obligations due.
 */
export function FutureSupplyTab({ entityId }: { entityId: string }) {
  const { t } = useTranslation();
  // The first call for an entity prepares the figures, so it can take a moment.
  const view = $api.useQuery(
    'get',
    '/api/v1/allocation/future-supply',
    { params: { query: { holderEntityId: entityId } } },
    { retry: false },
  );
  const data = view.data;
  return (
    <Stack data-testid="future-supply">
      <Text size="sm" c="dimmed">
        {t('positions.future.lead')}
      </Text>
      <Alert color="gray" variant="light" data-testid="future-supply-note">
        {t('positions.future.note')}
      </Alert>
      <QueryState isPending={false} error={view.error}>
        {!data ? (
          <TableSkeleton rows={5} />
        ) : isEmptyView(data) ? (
          <EmptyState title={t('positions.future.empty')} description={t('positions.future.emptyHint')} />
        ) : (
          <FutureSupplyBody data={data} />
        )}
      </QueryState>
    </Stack>
  );
}
