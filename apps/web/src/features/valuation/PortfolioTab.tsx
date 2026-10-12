import { BarChart } from '@mantine/charts';
import { Alert, Box, Group, Paper, SimpleGrid, Stack, Table, Text, Title } from '@mantine/core';
import { IconChartBar, IconInfoCircle } from '@tabler/icons-react';
import type { ColDef, ICellRendererParams } from 'ag-grid-community';
import { useMemo } from 'react';
import { useTranslation } from 'react-i18next';
import { Link } from 'react-router';
import {
  Bar,
  BarChart as RBarChart,
  type BarShapeProps,
  CartesianGrid,
  Rectangle,
  ReferenceLine,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from 'recharts';
import { token } from '@/app/tokens';
import { $api } from '@/shared/api/client';
import { asProblemError } from '@/shared/api/problem';
import type { Schema } from '@/shared/api/types';
import { EmptyState } from '@/shared/components/EmptyState';
import { Money } from '@/shared/components/Figures';
import { KpiCard } from '@/shared/components/KpiCard';
import { QueryState } from '@/shared/components/QueryState';
import { ScrollTable } from '@/shared/components/ScrollTable';
import { CardSkeleton } from '@/shared/components/Skeletons';
import { formatDate, formatDateTime, formatDecimal, formatMoney, formatQuantity } from '@/shared/format/format';
import { DataGrid } from '@/shared/grid/DataGrid';
import { explainBars } from './model/explain';
import { PORTFOLIO_CAUSES, waterfallSteps, type WaterfallKind } from './model/portfolio';

type Bridge = Schema<'PortfolioBridgeView'>;
type BucketRow = Schema<'PortfolioBucketRow'>;
type ObligationRow = Schema<'PortfolioObligationRow'>;

const BAR_COLOR: Record<WaterfallKind, string> = {
  total: token('accent'),
  gain: token('gain'),
  loss: token('loss'),
};

interface BridgeBar {
  key: string;
  label: string;
  kind: WaterfallKind;
  amount: number;
  range: [number, number];
}

function BridgeTooltip({
  active,
  payload,
  currency,
}: {
  active?: boolean;
  payload?: readonly { payload?: unknown }[];
  currency: string;
}) {
  const bar = payload?.[0]?.payload as BridgeBar | undefined;
  if (!active || !bar) return null;
  return (
    <Paper withBorder radius="md" p="xs" shadow="sm">
      <Text size="xs" c="dimmed">
        {bar.label}
      </Text>
      <Text size="sm" fw={600} className="etrm-figure">
        {formatMoney(bar.amount, currency)}
      </Text>
    </Paper>
  );
}

/** Inventory value to portfolio value as a waterfall; the amounts shown are the server's, the offsets are geometry. */
function BridgeChart({ bridge }: { bridge: Bridge }) {
  const { t } = useTranslation();
  const bars = useMemo<BridgeBar[]>(
    () => waterfallSteps(bridge).map((step) => ({ ...step, label: t(`valuation.portfolio.bridge.${step.key}`) })),
    [bridge, t],
  );
  return (
    <Stack gap="md">
      <Box h={320} data-testid="portfolio-bridge" role="img" aria-label={t('valuation.portfolio.bridgeChart')}>
        <ResponsiveContainer width="100%" height="100%">
          <RBarChart data={bars} margin={{ top: 8, right: 16, bottom: 8, left: 8 }}>
            <CartesianGrid vertical={false} stroke={token('line')} />
            <XAxis dataKey="label" tick={{ fill: token('muted'), fontSize: 12 }} stroke={token('line')} interval={0} />
            <YAxis
              tick={{ fill: token('muted'), fontSize: 12 }}
              stroke={token('line')}
              width={72}
              tickFormatter={(value: number) => formatDecimal(value, 0, 0)}
            />
            <ReferenceLine y={0} stroke={token('muted')} />
            <Tooltip content={(props) => <BridgeTooltip {...props} currency={bridge.currency} />} cursor={false} />
            <Bar
              dataKey="range"
              isAnimationActive={false}
              maxBarSize={72}
              shape={(props: BarShapeProps) => (
                <Rectangle {...props} fill={BAR_COLOR[(props.payload as BridgeBar).kind]} />
              )}
            />
          </RBarChart>
        </ResponsiveContainer>
      </Box>
      <ScrollTable label={t('valuation.portfolio.bridgeTitle')} minWidth={360}>
        <Table verticalSpacing="xs" data-testid="portfolio-bridge-table">
          <Table.Tbody className="etrm-figure">
            {bars.map((bar) => (
              <Table.Tr key={bar.key}>
                <Table.Td fw={bar.kind === 'total' ? 600 : undefined}>{bar.label}</Table.Td>
                <Table.Td ta="right" fw={bar.kind === 'total' ? 600 : undefined}>
                  <Money value={bar.amount} currency={bridge.currency} signed={bar.kind !== 'total'} />
                </Table.Td>
              </Table.Tr>
            ))}
          </Table.Tbody>
        </Table>
      </ScrollTable>
    </Stack>
  );
}

/** Causes of the change against the prior official run, by cause (shared by the run page and the explain tab). */
export function CauseChart({
  causes,
  currency,
  testId,
}: {
  causes: Record<string, number>;
  currency: string;
  testId: string;
}) {
  const { t } = useTranslation();
  const bars = explainBars(
    causes,
    (cause) => t(`valuation.portfolio.causes.${cause}`, { defaultValue: cause }),
    PORTFOLIO_CAUSES,
  );
  if (bars.length === 0)
    return (
      <Text size="sm" c="dimmed">
        {t('valuation.portfolio.noChange')}
      </Text>
    );
  return (
    <BarChart
      h={Math.max(160, bars.length * 48 + 40)}
      data={bars}
      dataKey="component"
      orientation="vertical"
      series={[{ name: 'amount', label: currency, color: 'brand.6' }]}
      getBarColor={(value) => (value < 0 ? 'red.7' : 'teal.7')}
      valueFormatter={(value) => formatMoney(value)}
      xAxisProps={{ domain: [(min: number) => Math.min(0, min), (max: number) => Math.max(0, max)] }}
      yAxisProps={{ width: 140 }}
      referenceLines={[{ x: 0, color: 'gray.5' }]}
      gridAxis="x"
      tickLine="x"
      data-testid={testId}
    />
  );
}

function BucketsTable({ rows }: { rows: BucketRow[] }) {
  const { t } = useTranslation();
  const columns = useMemo<ColDef<BucketRow>[]>(
    () => [
      {
        field: 'bucketCode',
        headerName: t('valuation.portfolio.bucket'),
        flex: 1,
        minWidth: 180,
        valueFormatter: (p) => (p.value as string | null) ?? '—',
      },
      { field: 'productionYear', headerName: t('valuation.portfolio.vintage'), width: 110 },
      {
        field: 'heldMwh',
        headerName: t('valuation.portfolio.heldMwh'),
        width: 140,
        type: 'rightAligned',
        valueFormatter: (p) => formatQuantity(p.value as number),
      },
      {
        field: 'allocatedMwh',
        headerName: t('valuation.portfolio.allocatedMwh'),
        width: 160,
        type: 'rightAligned',
        valueFormatter: (p) => formatQuantity(p.value as number),
      },
      {
        field: 'valueEur',
        headerName: t('valuation.portfolio.valueEur'),
        width: 160,
        type: 'rightAligned',
        valueFormatter: (p) => formatDecimal(p.value as number, 2, 2),
      },
    ],
    [t],
  );
  return (
    <div data-testid="portfolio-buckets">
      <DataGrid<BucketRow>
        rowData={rows}
        columnDefs={columns}
        fitRows={10}
        rowId={(row) => `${row.bucketCode ?? ''}:${row.productionYear}`}
        overlayNoRowsTemplate={t('valuation.portfolio.noBuckets')}
      />
    </div>
  );
}

function perMwh(value: number | null | undefined): string {
  return value === null || value === undefined ? '—' : formatDecimal(value, 2, 4);
}

function ObligationsTable({ rows }: { rows: ObligationRow[] }) {
  const { t } = useTranslation();
  const columns = useMemo<ColDef<ObligationRow>[]>(
    () => [
      {
        field: 'tradeRef',
        headerName: t('trades.columns.number'),
        width: 150,
        valueFormatter: (p) => (p.value as string | null) ?? '—',
      },
      {
        field: 'bucketCode',
        headerName: t('valuation.portfolio.bucket'),
        flex: 1,
        minWidth: 180,
        valueFormatter: (p) => (p.value as string | null) ?? '—',
      },
      {
        field: 'vintageYear',
        headerName: t('valuation.portfolio.vintage'),
        width: 110,
        valueFormatter: (p) => (p.value === null || p.value === undefined ? '—' : String(p.value)),
      },
      {
        field: 'demandMwh',
        headerName: t('valuation.portfolio.demandMwh'),
        width: 130,
        type: 'rightAligned',
        valueFormatter: (p) => formatQuantity(p.value as number),
      },
      {
        field: 'allocatedMwh',
        headerName: t('valuation.portfolio.allocatedMwh'),
        width: 160,
        type: 'rightAligned',
        valueFormatter: (p) => formatQuantity(p.value as number),
      },
      {
        field: 'cheapestToDeliverEur',
        headerName: t('valuation.portfolio.ctd'),
        width: 160,
        type: 'rightAligned',
        valueFormatter: (p) => perMwh(p.value as number | null),
      },
      {
        field: 'saleMarkEur',
        headerName: t('valuation.portfolio.saleMark'),
        width: 150,
        type: 'rightAligned',
        valueFormatter: (p) => perMwh(p.value as number | null),
      },
      {
        field: 'giveAwayEur',
        headerName: t('valuation.portfolio.giveAway'),
        width: 150,
        type: 'rightAligned',
        cellRenderer: (p: ICellRendererParams<ObligationRow>) =>
          p.data?.giveAwayEur === null || p.data?.giveAwayEur === undefined ? (
            '—'
          ) : (
            <Money value={p.data.giveAwayEur} size="sm" />
          ),
      },
      {
        field: 'transferFeesEur',
        headerName: t('valuation.portfolio.transferFees'),
        width: 150,
        type: 'rightAligned',
        valueFormatter: (p) => formatDecimal(p.value as number, 2, 2),
      },
    ],
    [t],
  );
  return (
    <div data-testid="portfolio-obligations">
      <DataGrid<ObligationRow>
        rowData={rows}
        columnDefs={columns}
        fitRows={10}
        rowId={(row) => `${row.obligationId}:${row.scheduleId ?? ''}`}
        overlayNoRowsTemplate={t('valuation.portfolio.noObligations')}
      />
    </div>
  );
}

/** The portfolio value of one run: the bridge from held certificates to what the book is worth, a management view. */
export function PortfolioTab({ runId }: { runId: string }) {
  const { t } = useTranslation();
  const portfolio = $api.useQuery(
    'get',
    '/api/v1/valuation/runs/{id}/portfolio',
    { params: { path: { id: runId } } },
    { retry: false },
  );
  const missing = Boolean(portfolio.error) && asProblemError(portfolio.error).status === 404;
  const data = portfolio.data;
  if (missing) {
    return (
      <EmptyState
        icon={<IconChartBar size={22} />}
        title={t('valuation.portfolio.none')}
        description={t('valuation.portfolio.noneHint')}
        testId="portfolio-none"
      />
    );
  }
  return (
    <QueryState isPending={false} error={portfolio.error}>
      {!data ? (
        <CardSkeleton height={320} />
      ) : (
        <Stack gap="lg" data-testid="portfolio-content">
          <Alert color="gray" icon={<IconInfoCircle size={18} />} data-testid="portfolio-management-note">
            {t('valuation.portfolio.lead')}
          </Alert>
          <SimpleGrid cols={{ base: 1, xs: 2, lg: 4 }} spacing="md" data-testid="portfolio-kpis">
            <KpiCard
              label={t('valuation.portfolio.portfolioValue')}
              value={formatMoney(data.portfolioValue)}
              unit={data.currency}
              trend={t('valuation.portfolio.computedAt', { time: formatDateTime(data.computedAt) })}
            />
            <KpiCard
              label={t('valuation.portfolio.inventoryValue')}
              value={formatMoney(data.inventoryValue)}
              unit={data.currency}
              trend={t('valuation.portfolio.inventoryHint')}
            />
            <KpiCard
              label={t('valuation.portfolio.heldMwh')}
              value={formatDecimal(data.heldMwh, 0, 0)}
              unit="MWh"
              trend={t('valuation.portfolio.allocatedOf', { allocated: formatDecimal(data.allocatedMwh, 0, 0) })}
            />
            <KpiCard
              label={t('valuation.portfolio.shortfallMwh')}
              value={formatDecimal(data.shortfallMwh, 0, 0)}
              unit="MWh"
              trend={
                data.unpricedMwh > 0
                  ? t('valuation.portfolio.unpriced', { mwh: formatDecimal(data.unpricedMwh, 0, 0) })
                  : t('valuation.portfolio.allPriced')
              }
              trendTone={data.shortfallMwh > 0 || data.unpricedMwh > 0 ? 'warning' : 'up'}
            />
          </SimpleGrid>
          <Paper withBorder radius="lg" p="lg">
            <Title order={3} size="h5">
              {t('valuation.portfolio.bridgeTitle')}
            </Title>
            <Text size="sm" c="dimmed" mb="md">
              {t('valuation.portfolio.bridgeHint', { currency: data.currency })}
            </Text>
            <BridgeChart bridge={data} />
          </Paper>
          <Paper withBorder radius="lg" p="lg" data-testid="portfolio-run-explain">
            <Group justify="space-between" align="flex-start" mb="md">
              <Stack gap={0}>
                <Title order={3} size="h5">
                  {t('valuation.portfolio.explainTitle')}
                </Title>
                <Text size="sm" c="dimmed">
                  {data.priorRunId
                    ? t('valuation.portfolio.explainHint', { currency: data.currency })
                    : t('valuation.portfolio.explainPending')}
                </Text>
              </Stack>
              {data.priorRunId && (
                <Text size="sm" component={Link} to={`/valuation/runs/${data.priorRunId}`}>
                  {t('valuation.portfolio.priorRun')}
                </Text>
              )}
            </Group>
            {data.priorRunId && (
              <CauseChart causes={data.explain} currency={data.currency} testId="portfolio-explain-chart" />
            )}
          </Paper>
          <Stack gap="sm">
            <Title order={3} size="h5">
              {t('valuation.portfolio.bucketsTitle')}
            </Title>
            <BucketsTable rows={data.buckets} />
          </Stack>
          <Stack gap="sm">
            <Title order={3} size="h5">
              {t('valuation.portfolio.obligationsTitle')}
            </Title>
            <Text size="sm" c="dimmed">
              {t('valuation.portfolio.obligationsHint')}
            </Text>
            <ObligationsTable rows={data.obligations} />
          </Stack>
        </Stack>
      )}
    </QueryState>
  );
}

/** The portfolio value between two dates: totals by cause, a line per official run and the give-away already handed over. */
export function PortfolioExplain({ entityId, from, to }: { entityId: string; from: string; to: string }) {
  const { t } = useTranslation();
  const explain = $api.useQuery(
    'get',
    '/api/v1/valuation/portfolio-explain',
    { params: { query: { legalEntityId: entityId, from, to } } },
    { enabled: Boolean(from && to), retry: false },
  );
  const data = explain.data;
  return (
    <Paper withBorder radius="lg" p="lg" data-testid="portfolio-explain">
      <Title order={3} size="h5">
        {t('valuation.portfolio.explainSectionTitle')}
      </Title>
      <Text size="sm" c="dimmed" mb="md">
        {t('valuation.portfolio.explainSectionHint')}
      </Text>
      {explain.error ? (
        <EmptyState
          icon={<IconChartBar size={22} />}
          title={t('valuation.portfolio.noExplain')}
          description={t('valuation.portfolio.noExplainHint')}
          testId="portfolio-explain-none"
        />
      ) : !data ? (
        <CardSkeleton height={240} />
      ) : data.steps.length === 0 ? (
        <EmptyState
          icon={<IconChartBar size={22} />}
          title={t('valuation.portfolio.noSteps')}
          description={t('valuation.portfolio.noStepsHint')}
          testId="portfolio-explain-none"
        />
      ) : (
        <Stack gap="md">
          <CauseChart causes={data.totals} currency={data.currency} testId="portfolio-explain-totals" />
          {data.realisedGiveAway !== null && data.realisedGiveAway !== undefined && (
            <Group gap="xs" data-testid="portfolio-realised-give-away">
              <Text size="sm" c="dimmed">
                {t('valuation.portfolio.realisedGiveAway')}
              </Text>
              <Money value={data.realisedGiveAway} currency={data.currency} fw={600} size="sm" />
            </Group>
          )}
          <ScrollTable label={t('valuation.portfolio.explainSectionTitle')} minWidth={420}>
            <Table verticalSpacing="xs" data-testid="portfolio-explain-steps">
              <Table.Thead>
                <Table.Tr>
                  <Table.Th>{t('valuation.date')}</Table.Th>
                  <Table.Th ta="right">{t('valuation.portfolio.portfolioValue')}</Table.Th>
                </Table.Tr>
              </Table.Thead>
              <Table.Tbody className="etrm-figure">
                {data.steps.map((step) => (
                  <Table.Tr key={step.runId}>
                    <Table.Td>
                      <Text component={Link} to={`/valuation/runs/${step.runId}?tab=portfolio`} size="sm">
                        {formatDate(step.valuationDate)}
                      </Text>
                    </Table.Td>
                    <Table.Td ta="right">
                      <Money value={step.portfolioValue} currency={data.currency} />
                    </Table.Td>
                  </Table.Tr>
                ))}
              </Table.Tbody>
            </Table>
          </ScrollTable>
        </Stack>
      )}
    </Paper>
  );
}
