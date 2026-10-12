import { BarChart } from '@mantine/charts';
import { Alert, Button, Group, Paper, Select, SimpleGrid, Stack, Tabs, Text, TextInput, Title } from '@mantine/core';
import { IconChartBar, IconInbox, IconPlayerPlay, IconSend } from '@tabler/icons-react';
import { useQueryClient } from '@tanstack/react-query';
import type { ColDef, ICellRendererParams } from 'ag-grid-community';
import dayjs from 'dayjs';
import { useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link, useNavigate, useParams, useSearchParams } from 'react-router';
import { $api } from '@/shared/api/client';
import type { Schema } from '@/shared/api/types';
import { Can } from '@/shared/auth/Can';
import { DetailLayout } from '@/shared/components/DetailLayout';
import { EmptyState } from '@/shared/components/EmptyState';
import { Money } from '@/shared/components/Figures';
import { KpiCard } from '@/shared/components/KpiCard';
import { LifecycleTimeline } from '@/shared/components/LifecycleTimeline';
import { notifySuccess } from '@/shared/components/notify';
import { StatusBadge, statusLabel } from '@/shared/components/StatusBadge';
import { PageHeader } from '@/shared/components/PageHeader';
import { ProblemAlert } from '@/shared/components/ProblemAlert';
import { QueryState } from '@/shared/components/QueryState';
import { CardSkeleton, TableSkeleton } from '@/shared/components/Skeletons';
import { useEntity } from '@/shared/context/EntityContext';
import { formatDate, formatDecimal, formatMoney } from '@/shared/format/format';
import { DataGrid } from '@/shared/grid/DataGrid';
import { useBooks } from '@/features/trades/hooks';
import { signOffSteps } from '@/features/marketdata/model/lifecycle';
import { explainBars } from './model/explain';
import { OfficialRun } from './OfficialRun';
import { PortfolioExplain, PortfolioTab } from './PortfolioTab';

type Run = Schema<'ValuationRunView'>;
type Result = Schema<'ValuationResultView'>;

export function RunStatusBadge({ status }: { status: string }) {
  return <StatusBadge status={status} data-testid="run-status" />;
}

/** An MtM figure in a grid cell, green or red by sign. */
function MtmCell({ value, currency }: { value: number | null | undefined; currency?: string }) {
  return <Money value={value} currency={currency} signed size="sm" />;
}

function useRecentRuns(entityId: string) {
  return $api.useQuery('get', '/api/v1/valuation/runs', {
    params: {
      query: {
        legalEntityId: entityId,
        from: dayjs().subtract(90, 'day').format('YYYY-MM-DD'),
        to: dayjs().add(1, 'day').format('YYYY-MM-DD'),
      },
    },
  });
}

/** The latest official MtM and its change (the server's figures), runs waiting for sign-off, and exceptions. */
function ValuationKpis({ entityId }: { entityId: string }) {
  const { t } = useTranslation();
  const summary = $api.useQuery('get', '/api/v1/home/summary', { params: { query: { legalEntityId: entityId } } });
  const runs = useRecentRuns(entityId);
  const valuation = summary.data?.valuation;
  const change = valuation?.change;
  const latest = [...(runs.data ?? [])].sort((a, b) => b.valuationDate.localeCompare(a.valuationDate))[0];
  const waiting = (runs.data ?? []).filter((run) => run.status === 'PENDING_SIGN_OFF' || run.status === 'DRAFT');
  return (
    <SimpleGrid cols={{ base: 1, xs: 2, lg: 4 }} spacing="md" data-testid="valuation-kpis">
      <KpiCard
        label={
          valuation ? t('valuation.kpi.mtm', { date: formatDate(valuation.valuationDate) }) : t('valuation.totalMtm')
        }
        value={valuation ? formatDecimal(valuation.totalMtm, 0, 0) : '—'}
        unit={valuation?.currency}
        loading={summary.isPending}
        trend={
          change === null || change === undefined
            ? t('valuation.kpi.firstRun')
            : `${change < 0 ? '▼' : '▲'} ${formatMoney(change)}`
        }
        trendTone={change === null || change === undefined ? 'neutral' : change < 0 ? 'down' : 'up'}
        spark={valuation?.history.map((point) => point.amount)}
        to="/valuation?tab=explain"
      />
      <KpiCard
        label={t('valuation.kpi.latestRun')}
        value={latest ? formatDate(latest.valuationDate) : '—'}
        loading={runs.isPending}
        trend={
          latest ? `${t(`valuation.kinds.${latest.kind}`)} · ${statusLabel(latest.status)}` : t('valuation.kpi.noRuns')
        }
        trendTone={latest?.status === 'OFFICIAL' ? 'up' : latest ? 'warning' : 'neutral'}
        to={latest ? `/valuation/runs/${latest.id}` : undefined}
      />
      <KpiCard
        label={t('valuation.kpi.waiting')}
        value={waiting.length}
        loading={runs.isPending}
        trend={waiting.length > 0 ? t('valuation.kpi.waitingHint') : t('valuation.kpi.allSignedOff')}
        trendTone={waiting.length > 0 ? 'warning' : 'up'}
      />
      <KpiCard
        label={t('valuation.kpi.exceptions')}
        value={latest ? latest.exceptions : '—'}
        loading={runs.isPending}
        trend={latest && latest.exceptions > 0 ? t('valuation.kpi.exceptionsHint') : t('valuation.kpi.noExceptions')}
        trendTone={latest && latest.exceptions > 0 ? 'warning' : 'up'}
        to={latest ? `/valuation/runs/${latest.id}` : undefined}
      />
    </SimpleGrid>
  );
}

function Runs({ entityId }: { entityId: string }) {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const [date, setDate] = useState(dayjs().subtract(1, 'day').format('YYYY-MM-DD'));
  const runs = useRecentRuns(entityId);
  const start = $api.useMutation('post', '/api/v1/valuation/runs', {
    meta: { notify: false },
    onSuccess: (run) => void navigate(`/valuation/runs/${run.id}`),
  });
  const columns = useMemo<ColDef<Run>[]>(
    () => [
      { field: 'valuationDate', headerName: t('valuation.date'), width: 140, sort: 'desc' },
      {
        field: 'kind',
        headerName: t('marketData.kind'),
        width: 130,
        valueFormatter: (p) => t(`valuation.kinds.${String(p.value)}`),
      },
      {
        field: 'status',
        headerName: t('allocation.status'),
        width: 170,
        cellRenderer: (p: ICellRendererParams<Run>) => p.data && <StatusBadge status={p.data.status} />,
      },
      {
        field: 'totalMtmBase',
        headerName: t('valuation.totalMtm'),
        flex: 1,
        minWidth: 170,
        type: 'rightAligned',
        cellRenderer: (p: ICellRendererParams<Run>) =>
          p.data && <MtmCell value={p.data.totalMtmBase} currency={p.data.baseCurrency} />,
      },
      {
        field: 'exceptions',
        headerName: t('valuation.exceptions'),
        width: 130,
        type: 'rightAligned',
        cellStyle: (p) => ((p.value as number) > 0 ? { color: 'var(--mantine-color-orange-text)' } : null),
      },
      { field: 'createdBy', headerName: t('marketData.recordedBy'), width: 130 },
      { field: 'signedOffBy', headerName: t('curves.signedOffBy'), width: 140 },
    ],
    [t],
  );
  return (
    <Stack>
      <Can op="valuation_run">
        <Group align="flex-end">
          <TextInput
            type="date"
            label={t('valuation.date')}
            value={date}
            onChange={(e) => setDate(e.currentTarget.value)}
          />
          <Button
            leftSection={<IconPlayerPlay size={16} />}
            loading={start.isPending}
            onClick={() => start.mutate({ body: { legalEntityId: entityId, valuationDate: date, kind: 'EOD' } })}
            data-testid="start-run"
          >
            {t('valuation.start')}
          </Button>
        </Group>
      </Can>
      <ProblemAlert error={start.error} />
      <QueryState isPending={false} error={runs.error}>
        {runs.isPending ? (
          <TableSkeleton />
        ) : (
          <DataGrid<Run>
            rowData={runs.data ?? []}
            columnDefs={columns}
            rowId={(run) => run.id}
            height={440}
            onRowClicked={(event) => {
              if (event.data) void navigate(`/valuation/runs/${event.data.id}`);
            }}
            rowStyle={{ cursor: 'pointer' }}
            overlayNoRowsTemplate={t('valuation.noRuns')}
          />
        )}
      </QueryState>
    </Stack>
  );
}

/** The two latest official runs, oldest first: the natural default for a P&L explain. */
function useLatestOfficialDates(entityId: string): { dates: [string, string] | null; ready: boolean } {
  const runs = $api.useQuery('get', '/api/v1/valuation/runs', {
    params: {
      query: {
        legalEntityId: entityId,
        from: dayjs().subtract(120, 'day').format('YYYY-MM-DD'),
        to: dayjs().add(1, 'day').format('YYYY-MM-DD'),
      },
    },
  });
  const dates = [
    ...new Set((runs.data ?? []).filter((run) => run.status === 'OFFICIAL').map((run) => run.valuationDate)),
  ].sort();
  const to = dates[dates.length - 1];
  const from = dates[dates.length - 2];
  return { dates: from && to ? [from, to] : null, ready: !runs.isPending };
}

function Explain({ entityId }: { entityId: string }) {
  const { dates, ready } = useLatestOfficialDates(entityId);
  if (!ready) return <CardSkeleton height={320} />;
  return <ExplainFor entityId={entityId} initial={dates} />;
}

function ExplainFor({ entityId, initial }: { entityId: string; initial: [string, string] | null }) {
  const { t } = useTranslation();
  const [from, setFrom] = useState(initial?.[0] ?? dayjs().subtract(1, 'month').format('YYYY-MM-DD'));
  const [to, setTo] = useState(initial?.[1] ?? dayjs().format('YYYY-MM-DD'));
  const explain = $api.useQuery(
    'get',
    '/api/v1/valuation/pnl-explain',
    { params: { query: { legalEntityId: entityId, from, to } } },
    { retry: false },
  );
  const data = explain.data;
  const bars = data ? explainBars(data.components, (component) => t(`valuation.components.${component}`)) : [];
  return (
    <Stack>
      <Group align="flex-end">
        <TextInput
          type="date"
          label={t('valuation.fromRun')}
          value={from}
          onChange={(e) => setFrom(e.currentTarget.value)}
        />
        <TextInput type="date" label={t('valuation.toRun')} value={to} onChange={(e) => setTo(e.currentTarget.value)} />
      </Group>
      {explain.error ? (
        <EmptyState
          icon={<IconChartBar size={22} />}
          title={t('valuation.noExplain')}
          description={t('valuation.noExplainHint')}
        />
      ) : !data ? (
        <CardSkeleton height={320} />
      ) : (
        <Stack>
          <SimpleGrid cols={{ base: 1, xs: 2 }} spacing="md">
            <KpiCard
              label={t('valuation.mtmFrom', { date: formatDate(data.fromDate) })}
              value={formatMoney(data.mtmFrom)}
              unit={data.baseCurrency}
              to={`/valuation/runs/${data.fromRunId}`}
            />
            <KpiCard
              label={t('valuation.mtmTo', { date: formatDate(data.toDate) })}
              value={formatMoney(data.mtmTo)}
              unit={data.baseCurrency}
              to={`/valuation/runs/${data.toRunId}`}
            />
          </SimpleGrid>
          <Paper withBorder radius="lg" p="lg">
            <Title order={3} size="h5">
              {t('valuation.explain')}
            </Title>
            <Text size="sm" c="dimmed" mb="md">
              {t('valuation.explainHint', { currency: data.baseCurrency })}
            </Text>
            <BarChart
              h={Math.max(160, bars.length * 48 + 40)}
              data={bars}
              dataKey="component"
              orientation="vertical"
              series={[{ name: 'amount', label: data.baseCurrency, color: 'brand.6' }]}
              getBarColor={(value) => (value < 0 ? 'red.7' : 'teal.7')}
              valueFormatter={(value) => formatMoney(value)}
              xAxisProps={{ domain: [(min: number) => Math.min(0, min), (max: number) => Math.max(0, max)] }}
              yAxisProps={{ width: 110 }}
              referenceLines={[{ x: 0, color: 'gray.5' }]}
              gridAxis="x"
              tickLine="x"
              data-testid="explain-chart"
            />
          </Paper>
        </Stack>
      )}
      <PortfolioExplain entityId={entityId} from={from} to={to} />
    </Stack>
  );
}

/** Valuation runs of the selected entity, the official run as of a date and the P&L explain between two official runs. */
export function ValuationPage() {
  const { t } = useTranslation();
  const { entity } = useEntity();
  const [params, setParams] = useSearchParams();
  const tab = params.get('tab') ?? 'runs';
  return (
    <Stack>
      <PageHeader
        title={t('valuation.title')}
        description={t('valuation.description', { entity: entity?.code ?? '' })}
      />
      {!entity ? (
        <Alert color="gray">{t('header.noEntity')}</Alert>
      ) : (
        <>
          <ValuationKpis entityId={entity.id} />
          <Tabs
            value={tab}
            onChange={(value) => setParams(value && value !== 'runs' ? { tab: value } : {}, { replace: true })}
            keepMounted={false}
          >
            <Tabs.List>
              <Tabs.Tab value="runs">{t('valuation.runs')}</Tabs.Tab>
              <Tabs.Tab value="official" data-testid="tab-official">
                {t('valuation.official')}
              </Tabs.Tab>
              <Tabs.Tab value="explain">{t('valuation.explain')}</Tabs.Tab>
            </Tabs.List>
            <Tabs.Panel value="runs" pt="md">
              <Runs entityId={entity.id} />
            </Tabs.Panel>
            <Tabs.Panel value="official" pt="md">
              <OfficialRun entityId={entity.id} />
            </Tabs.Panel>
            <Tabs.Panel value="explain" pt="md">
              <Explain entityId={entity.id} />
            </Tabs.Panel>
          </Tabs>
        </>
      )}
    </Stack>
  );
}

/** One valuation run: its total and exceptions, the results per delivery, sign-off and its lifecycle. */
export function RunDetailPage() {
  const { t } = useTranslation();
  const { runId = '' } = useParams();
  const [params, setParams] = useSearchParams();
  const tab = params.get('tab') === 'portfolio' ? 'portfolio' : 'results';
  const queryClient = useQueryClient();
  const run = $api.useQuery('get', '/api/v1/valuation/runs/{id}', { params: { path: { id: runId } } });
  const books = useBooks(run.data?.legalEntityId);
  const [bookId, setBookId] = useState<string | null>(null);
  const results = $api.useQuery('get', '/api/v1/valuation/runs/{id}/results', {
    params: { path: { id: runId }, query: { bookId: bookId ?? undefined } },
  });
  const task = $api.useQuery(
    'get',
    '/api/v1/workflow/tasks',
    { params: { query: { subjectRef: runId, type: ['VALUATION_SIGN_OFF'] } } },
    { enabled: run.data?.status === 'PENDING_SIGN_OFF' },
  );
  const submit = $api.useMutation('post', '/api/v1/valuation/runs/{id}/submit', {
    onSuccess: () => {
      notifySuccess(t('valuation.submitted'));
      void queryClient.invalidateQueries({
        predicate: (q) =>
          typeof q.queryKey[1] === 'string' &&
          (q.queryKey[1].startsWith('/api/v1/valuation') || q.queryKey[1].startsWith('/api/v1/workflow')),
      });
    },
  });
  const currency = run.data?.baseCurrency;
  const columns = useMemo<ColDef<Result>[]>(
    () => [
      { field: 'tradeNumber', headerName: t('trades.columns.number'), width: 150 },
      { field: 'legNo', headerName: t('valuation.leg'), width: 80 },
      { field: 'deliveryDate', headerName: t('trades.form.deliveryDate'), width: 130 },
      { field: 'productKey', headerName: t('trades.columns.product'), flex: 1, minWidth: 160 },
      {
        field: 'quantity',
        headerName: t('trades.columns.quantity'),
        width: 120,
        type: 'rightAligned',
        valueFormatter: (p) => formatDecimal(p.value as number, 0, 3),
      },
      {
        field: 'contractPrice',
        headerName: t('trades.mtm.contractPrice'),
        width: 140,
        type: 'rightAligned',
        valueFormatter: (p) => formatDecimal(p.value as number, 2, 6),
      },
      {
        field: 'marketPriceContract',
        headerName: t('trades.mtm.marketPrice'),
        width: 140,
        type: 'rightAligned',
        valueFormatter: (p) => formatDecimal(p.value as number, 2, 6),
      },
      {
        field: 'mtmBase',
        headerName: t('trades.mtm.mtmBase'),
        width: 160,
        type: 'rightAligned',
        cellRenderer: (p: ICellRendererParams<Result>) =>
          p.data && <MtmCell value={p.data.mtmBase} currency={currency} />,
      },
      {
        field: 'ctdPriceEur',
        headerName: t('valuation.ctdPrice'),
        headerTooltip: t('valuation.ctdHint'),
        width: 150,
        type: 'rightAligned',
        valueFormatter: (p) =>
          p.value === null || p.value === undefined ? '—' : formatDecimal(p.value as number, 2, 6),
      },
      {
        field: 'ctdMtmBase',
        headerName: t('valuation.ctdMtm'),
        headerTooltip: t('valuation.ctdHint'),
        width: 160,
        type: 'rightAligned',
        cellRenderer: (p: ICellRendererParams<Result>) =>
          p.data?.ctdMtmBase === null || p.data?.ctdMtmBase === undefined ? (
            '—'
          ) : (
            <MtmCell value={p.data.ctdMtmBase} currency={currency} />
          ),
      },
      {
        field: 'status',
        headerName: t('allocation.status'),
        width: 150,
        cellRenderer: (p: ICellRendererParams<Result>) => p.data && <StatusBadge status={p.data.status} />,
      },
      { field: 'note', headerName: t('marketData.note'), width: 220 },
    ],
    [t, currency],
  );
  const breadcrumbs = [
    { label: t('valuation.title'), to: '/valuation' },
    { label: t('valuation.runs'), to: '/valuation' },
  ];
  const data = run.data;
  if (!data) {
    return (
      <Stack gap={0}>
        <PageHeader breadcrumbs={breadcrumbs} title={t('valuation.title')} />
        <QueryState isPending={false} error={run.error}>
          <CardSkeleton height={260} />
        </QueryState>
      </Stack>
    );
  }
  const signOffTask = task.data?.items[0];
  return (
    <Stack gap="lg">
      <PageHeader
        breadcrumbs={breadcrumbs}
        title={t('valuation.runTitle', { date: formatDate(data.valuationDate), kind: data.kind })}
        badge={<RunStatusBadge status={data.status} />}
        description={t('valuation.runDescription', { currency: data.baseCurrency })}
        actions={
          <>
            {signOffTask && (
              <Button component={Link} to={`/inbox/${signOffTask.id}`} leftSection={<IconInbox size={16} />}>
                {t('curves.signOffTask')}
              </Button>
            )}
            {data.status === 'DRAFT' && (
              <Can op="valuation_submit">
                <Button
                  leftSection={<IconSend size={16} />}
                  loading={submit.isPending}
                  onClick={() => submit.mutate({ params: { path: { id: runId } } })}
                  data-testid="submit-run"
                >
                  {t('valuation.submit')}
                </Button>
              </Can>
            )}
          </>
        }
      />
      <DetailLayout
        summary={[
          {
            label: t('valuation.totalMtm'),
            value: <Money value={data.totalMtmBase} currency={data.baseCurrency} signed fw={600} size="lg" />,
          },
          {
            label: t('valuation.exceptions'),
            value: (
              <Text component="span" size="sm" c={data.exceptions > 0 ? 'orange' : undefined}>
                {data.exceptions}
              </Text>
            ),
          },
          { label: t('valuation.date'), value: formatDate(data.valuationDate) },
          { label: t('marketData.kind'), value: t(`valuation.kinds.${data.kind}`) },
        ]}
        aside={
          <LifecycleTimeline
            title={t('curves.lifecycle.title')}
            steps={signOffSteps({ ...data, builtBy: data.createdBy, builtAt: data.createdAt }, t)}
            testId="run-lifecycle"
          />
        }
      >
        {data.exceptions > 0 && (
          <Alert color="orange">{t('valuation.exceptionsAre', { count: data.exceptions })}</Alert>
        )}
        {(data.note ?? data.signOffNote) && (
          <Paper withBorder radius="lg" p="lg">
            {data.note && <Text size="sm">{data.note}</Text>}
            {data.signOffNote && (
              <Text size="sm" c="dimmed">
                {data.signOffNote}
              </Text>
            )}
          </Paper>
        )}
      </DetailLayout>
      <Tabs
        value={tab}
        onChange={(value) => setParams(value && value !== 'results' ? { tab: value } : {}, { replace: true })}
      >
        <Tabs.List>
          <Tabs.Tab value="results" data-testid="results-tab">
            {t('valuation.resultsTab')}
          </Tabs.Tab>
          <Tabs.Tab value="portfolio" data-testid="portfolio-tab">
            {t('valuation.portfolio.tab')}
          </Tabs.Tab>
        </Tabs.List>
        <Tabs.Panel value="results" pt="md">
          <Stack gap="sm">
            <Group justify="space-between" align="flex-end">
              <Title order={3} size="h5">
                {t('valuation.results')}
              </Title>
              <Select
                aria-label={t('trades.columns.book')}
                data={books.list.map((book) => ({ value: book.id, label: `${book.code} · ${book.name}` }))}
                value={bookId}
                onChange={setBookId}
                clearable
                placeholder={t('valuation.allBooks')}
                w={280}
              />
            </Group>
            <QueryState isPending={false} error={results.error}>
              {results.isPending ? (
                <TableSkeleton />
              ) : (
                <DataGrid<Result>
                  rowData={results.data ?? []}
                  columnDefs={columns}
                  rowId={(r) => r.scheduleId}
                  height={460}
                  overlayNoRowsTemplate={t('common.empty')}
                />
              )}
            </QueryState>
          </Stack>
        </Tabs.Panel>
        <Tabs.Panel value="portfolio" pt="md">
          <PortfolioTab runId={runId} />
        </Tabs.Panel>
      </Tabs>
    </Stack>
  );
}
