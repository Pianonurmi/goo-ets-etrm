import { useUrlTab } from '@/shared/useUrlState';
import { BarChart } from '@mantine/charts';
import {
  Alert,
  Anchor,
  NumberInput,
  Paper,
  SegmentedControl,
  SimpleGrid,
  Stack,
  Table,
  Tabs,
  Title,
} from '@mantine/core';
import type { ColDef } from 'ag-grid-community';
import { useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link } from 'react-router';
import { $api } from '@/shared/api/client';
import { useCan } from '@/shared/auth/Can';
import type { Schema } from '@/shared/api/types';
import { EmptyState } from '@/shared/components/EmptyState';
import { KpiCard } from '@/shared/components/KpiCard';
import { PageHeader } from '@/shared/components/PageHeader';
import { QueryState } from '@/shared/components/QueryState';
import { TableSkeleton } from '@/shared/components/Skeletons';
import { StatusBadge } from '@/shared/components/StatusBadge';
import { useEntity } from '@/shared/context/EntityContext';
import { formatDate, formatDecimal } from '@/shared/format/format';
import { DataGrid } from '@/shared/grid/DataGrid';
import { FutureSupplyTab } from './FutureSupplyTab';
import { HeadroomTab } from './HeadroomTab';
import { ladderSeries } from './model/ladder';
import { PortfolioTab } from './PortfolioTab';
import { SnapshotsTab } from './SnapshotsTab';

type BucketPosition = Schema<'BucketPosition'>;

const mwh = (p: { value: unknown }) => formatDecimal(p.value as number);

function GooPosition({ entityId }: { entityId: string }) {
  const { t } = useTranslation();
  const report = $api.useQuery('get', '/api/v1/positions/goo', { params: { query: { legalEntityId: entityId } } });
  const summary = $api.useQuery('get', '/api/v1/home/summary', { params: { query: { legalEntityId: entityId } } });
  const goo = summary.data?.goo;
  const columns = useMemo<ColDef<BucketPosition>[]>(
    () => [
      { field: 'bucketCode', headerName: t('positions.bucket'), flex: 1, minWidth: 180 },
      { field: 'productionYear', headerName: t('positions.vintage'), width: 110 },
      { field: 'liveMwh', headerName: t('positions.live'), width: 120, type: 'rightAligned', valueFormatter: mwh },
      { field: 'heldMwh', headerName: t('positions.held'), width: 120, type: 'rightAligned', valueFormatter: mwh },
      {
        field: 'reservedMwh',
        headerName: t('positions.reserved'),
        width: 120,
        type: 'rightAligned',
        valueFormatter: mwh,
      },
      {
        field: 'inTransferMwh',
        headerName: t('positions.inTransfer'),
        width: 130,
        type: 'rightAligned',
        valueFormatter: mwh,
      },
      {
        field: 'expiringWithin90DaysMwh',
        headerName: t('positions.expiring90'),
        width: 160,
        type: 'rightAligned',
        valueFormatter: mwh,
      },
      { field: 'lotCount', headerName: t('positions.lots'), width: 100, type: 'rightAligned' },
    ],
    [t],
  );
  return (
    <QueryState isPending={false} error={report.error}>
      {!report.data ? (
        <TableSkeleton />
      ) : (
        <Stack>
          <SimpleGrid cols={{ base: 1, xs: 2, lg: 4 }} spacing="md" data-testid="goo-kpis">
            <KpiCard
              label={t('positions.liveTotal')}
              value={formatDecimal(report.data.totalLiveMwh)}
              unit="MWh"
              trend={goo && t('positions.kpi.expiring', { mwh: formatDecimal(goo.expiring90Mwh) })}
              trendTone={goo && goo.expiring90Mwh > 0 ? 'warning' : 'neutral'}
              to="/inventory"
            />
            <KpiCard
              label={t('positions.openObligations')}
              value={formatDecimal(report.data.totalOpenObligationMwh)}
              unit="MWh"
              trend={t('positions.kpi.obligations', { count: report.data.coverage.obligations.length })}
              to="/allocation"
            />
            <KpiCard
              label={t('allocation.covered')}
              value={formatDecimal(report.data.coverage.totalCoveredMwh)}
              unit="MWh"
              trend={
                goo?.coveragePct != null && t('positions.kpi.coverage', { pct: formatDecimal(goo.coveragePct, 0, 1) })
              }
              trendTone="up"
            />
            <KpiCard
              label={t('allocation.shortfallLabel')}
              value={formatDecimal(report.data.coverage.totalShortfallMwh)}
              unit="MWh"
              trend={
                report.data.coverage.totalShortfallMwh > 0 ? t('positions.kpi.shortfall') : t('positions.kpi.covered')
              }
              trendTone={report.data.coverage.totalShortfallMwh > 0 ? 'down' : 'up'}
              to="/allocation"
            />
          </SimpleGrid>
          <Paper withBorder radius="lg" p="lg">
            <Title order={3} size="h5" mb="md">
              {t('positions.expiryLadder')}
            </Title>
            <BarChart
              h={240}
              data={ladderSeries(report.data.expiryLadder)}
              dataKey="month"
              series={[{ name: 'mwh', label: 'MWh', color: 'brand.6' }]}
              valueFormatter={(value) => formatDecimal(value)}
              tickLine="y"
              data-testid="expiry-chart"
            />
          </Paper>
          <DataGrid<BucketPosition>
            rowData={report.data.inventory}
            columnDefs={columns}
            rowId={(row) => `${row.bucketCode}-${row.productionYear}`}
            fitRows={8}
            overlayNoRowsTemplate={t('common.empty')}
          />
        </Stack>
      )}
    </QueryState>
  );
}

type GooViewKind = 'position' | 'headroom' | 'portfolio' | 'future';

/** The GoO position, and next to it what the entity can still sell and how its products net against each other. */
function GooView({ entityId }: { entityId: string }) {
  const { t } = useTranslation();
  const canSeeHeadroom = useCan('allocation_headroomTable');
  const canSeePortfolio = useCan('allocation_portfolio');
  const canSeeFuture = useCan('allocation_futureSupply');
  const [view, setView] = useState<GooViewKind>('position');
  if (!canSeeHeadroom && !canSeePortfolio && !canSeeFuture) return <GooPosition entityId={entityId} />;
  const views = [
    { value: 'position', label: t('positions.view.position') },
    ...(canSeeHeadroom ? [{ value: 'headroom', label: t('positions.view.headroom') }] : []),
    ...(canSeePortfolio ? [{ value: 'portfolio', label: t('positions.view.portfolio') }] : []),
    ...(canSeeFuture ? [{ value: 'future', label: t('positions.view.future') }] : []),
  ];
  return (
    <Stack>
      <SegmentedControl
        value={view}
        onChange={(value) =>
          setView(value === 'headroom' || value === 'portfolio' || value === 'future' ? value : 'position')
        }
        data={views}
        aria-label={t('positions.view.label')}
        w="fit-content"
        data-testid="goo-view"
      />
      {view === 'position' && <GooPosition entityId={entityId} />}
      {view === 'headroom' && <HeadroomTab entityId={entityId} />}
      {view === 'portfolio' && <PortfolioTab entityId={entityId} />}
      {view === 'future' && <FutureSupplyTab entityId={entityId} />}
    </Stack>
  );
}

function EtsPosition({ entityId }: { entityId: string }) {
  const { t } = useTranslation();
  const [year, setYear] = useState<number>(new Date().getFullYear());
  const ladder = $api.useQuery('get', '/api/v1/positions/ets', {
    params: { query: { legalEntityId: entityId, complianceYear: year } },
  });
  const data = ladder.data;
  return (
    <Stack>
      <NumberInput
        label={t('positions.complianceYear')}
        value={year}
        onChange={(value) => setYear(Number(value))}
        w={180}
        min={2005}
      />
      <QueryState isPending={false} error={ladder.error}>
        {!data ? (
          <TableSkeleton rows={3} />
        ) : (
          <Stack>
            <SimpleGrid cols={{ base: 1, xs: 2, lg: 4 }} spacing="md" data-testid="ets-position-kpis">
              <KpiCard
                label={t('positions.netPosition')}
                value={formatDecimal(data.netPosition)}
                unit="t"
                trend={data.netPosition < 0 ? t('positions.kpi.short') : t('positions.kpi.long')}
                trendTone={data.netPosition < 0 ? 'down' : 'up'}
              />
              <KpiCard
                label={t('positions.heldAllowances')}
                value={formatDecimal(data.heldAllowances)}
                unit="t"
                trend={t('positions.kpi.surrendered', { tonnes: formatDecimal(data.surrendered) })}
              />
              <KpiCard
                label={t('positions.emissions')}
                value={formatDecimal(data.emissionsBasis)}
                unit="t"
                trend={t('positions.kpi.freeAllocation', {
                  received: formatDecimal(data.freeAllocationReceived),
                  expected: formatDecimal(data.freeAllocationExpected),
                })}
              />
              <KpiCard
                label={t('positions.deadline')}
                value={formatDate(data.surrenderDeadline)}
                trend={t('positions.kpi.openTrades', {
                  bought: formatDecimal(data.openPurchases),
                  sold: formatDecimal(data.openSales),
                })}
              />
            </SimpleGrid>
            {(data.installations ?? []).length === 0 ? (
              <EmptyState title={t('positions.noInstallations')} description={t('ets.noInstallationsHint')} />
            ) : (
              <Paper withBorder radius="lg" p="lg">
                <Table verticalSpacing="sm">
                  <Table.Thead>
                    <Table.Tr>
                      <Table.Th>{t('positions.installation')}</Table.Th>
                      <Table.Th>{t('positions.basis')}</Table.Th>
                      <Table.Th ta="right">{t('positions.emissions')}</Table.Th>
                      <Table.Th ta="right">{t('positions.held')}</Table.Th>
                      <Table.Th ta="right">{t('positions.surrendered')}</Table.Th>
                      <Table.Th>{t('allocation.status')}</Table.Th>
                    </Table.Tr>
                  </Table.Thead>
                  <Table.Tbody className="etrm-figure">
                    {(data.installations ?? []).map((line) => (
                      <Table.Tr key={line.installationId}>
                        <Table.Td>
                          <Anchor component={Link} to={`/ets/installations/${line.installationId}?year=${year}`}>
                            {line.name}
                          </Anchor>
                        </Table.Td>
                        <Table.Td>{t(`ets.basis.${line.basis}`, { defaultValue: line.basis })}</Table.Td>
                        <Table.Td ta="right">{formatDecimal(line.emissions)}</Table.Td>
                        <Table.Td ta="right">{formatDecimal(line.held)}</Table.Td>
                        <Table.Td ta="right">{formatDecimal(line.surrendered)}</Table.Td>
                        <Table.Td style={{ whiteSpace: 'nowrap' }}>
                          <StatusBadge status={line.status} />
                        </Table.Td>
                      </Table.Tr>
                    ))}
                  </Table.Tbody>
                </Table>
              </Paper>
            )}
          </Stack>
        )}
      </QueryState>
    </Stack>
  );
}

/** Coverage, expiry and ETS dashboards of the selected entity, and their end-of-day snapshots. */
export function PositionsPage() {
  const { t } = useTranslation();
  const { entity } = useEntity();
  const [tab, setTab] = useUrlTab(['goo', 'ets', 'snapshots'] as const, 'goo');
  return (
    <Stack>
      <PageHeader
        title={t('positions.title')}
        description={t('positions.description', { entity: entity?.code ?? '' })}
      />
      {!entity ? (
        <Alert color="gray">{t('header.noEntity')}</Alert>
      ) : (
        <Tabs value={tab} onChange={(value) => setTab(value)} keepMounted={false}>
          <Tabs.List>
            <Tabs.Tab value="goo">{t('positions.goo')}</Tabs.Tab>
            <Tabs.Tab value="ets">{t('positions.ets')}</Tabs.Tab>
            <Tabs.Tab value="snapshots" data-testid="tab-snapshots">
              {t('positions.snapshots')}
            </Tabs.Tab>
          </Tabs.List>
          <Tabs.Panel value="goo" pt="md">
            <GooView entityId={entity.id} />
          </Tabs.Panel>
          <Tabs.Panel value="ets" pt="md">
            <EtsPosition entityId={entity.id} />
          </Tabs.Panel>
          <Tabs.Panel value="snapshots" pt="md">
            <SnapshotsTab entityId={entity.id} />
          </Tabs.Panel>
        </Tabs>
      )}
    </Stack>
  );
}
