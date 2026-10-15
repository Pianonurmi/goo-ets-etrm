import { Alert, Button, Group, Paper, Select, Stack, Tabs, Text, Title } from '@mantine/core';
import { IconInbox, IconSend } from '@tabler/icons-react';
import { useQueryClient } from '@tanstack/react-query';
import type { ColDef, ICellRendererParams, ValueFormatterParams } from 'ag-grid-community';
import type { TFunction } from 'i18next';
import { useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link, useParams } from 'react-router';
import { signOffSteps } from '@/features/marketdata/model/lifecycle';
import { useBooks } from '@/features/trades/hooks';
import { $api } from '@/shared/api/client';
import { Can } from '@/shared/auth/Can';
import { DetailLayout } from '@/shared/components/DetailLayout';
import { Money } from '@/shared/components/Figures';
import { LifecycleTimeline } from '@/shared/components/LifecycleTimeline';
import { notifySuccess } from '@/shared/components/notify';
import { PageHeader } from '@/shared/components/PageHeader';
import { QueryState } from '@/shared/components/QueryState';
import { CardSkeleton, TableSkeleton } from '@/shared/components/Skeletons';
import { StatusBadge } from '@/shared/components/StatusBadge';
import { formatDate, formatDecimal, formatMoney } from '@/shared/format/format';
import { DataGrid } from '@/shared/grid/DataGrid';
import { LoadMore } from '@/shared/grid/LoadMore';
import { useUrlTab } from '@/shared/useUrlState';
import { PortfolioTab } from './PortfolioTab';
import { MtmCell, type Result, RunStatusBadge } from './shared';

/** Results fetched per page of a run's result list. */
export const RESULT_PAGE = 200;

export const optionNumber = (digits: number) => (p: ValueFormatterParams<Result>) =>
  p.value === null || p.value === undefined ? '' : formatDecimal(p.value as number, digits, digits);

/** The option block of a result; empty for forwards and futures. */
export function optionColumns(t: TFunction): ColDef<Result>[] {
  const column = (
    id: string,
    headerName: string,
    value: (option: NonNullable<Result['option']>) => number | string,
    width: number,
    format?: (p: ValueFormatterParams<Result>) => string,
  ): ColDef<Result> => ({
    colId: `option-${id}`,
    headerName,
    width,
    type: format ? 'rightAligned' : undefined,
    valueGetter: (p) => (p.data?.option ? value(p.data.option) : null),
    valueFormatter: format,
  });
  const vega = column('vega', t('valuation.option.vega'), (o) => o.vega, 100, optionNumber(4));
  return [
    column(
      'type',
      t('valuation.option.type'),
      (o) =>
        o.optionType === 'CALL' || o.optionType === 'PUT' ? t(`trades.optionTypes.${o.optionType}`) : o.optionType,
      110,
    ),
    column('strike', t('valuation.option.strike'), (o) => o.strike, 110, optionNumber(2)),
    column('underlying', t('valuation.option.underlyingPrice'), (o) => o.underlyingPrice, 150, optionNumber(2)),
    column('vol', t('valuation.option.volatility'), (o) => o.volatility, 100, optionNumber(2)),
    column('delta', t('valuation.option.delta'), (o) => o.delta, 100, optionNumber(4)),
    { ...vega, headerTooltip: t('valuation.option.vegaHint') },
  ];
}

/** One valuation run: its total and exceptions, the results per delivery, sign-off and its lifecycle. */
export function RunDetailPage() {
  const { t } = useTranslation();
  const { runId = '' } = useParams();
  const [tab, setTab] = useUrlTab(['results', 'portfolio'] as const, 'results');
  const queryClient = useQueryClient();
  const run = $api.useQuery('get', '/api/v1/valuation/runs/{id}', { params: { path: { id: runId } } });
  const books = useBooks(run.data?.legalEntityId);
  const [bookId, setBookId] = useState<string | null>(null);
  const results = $api.useInfiniteQuery(
    'get',
    '/api/v1/valuation/runs/{id}/results/search',
    { params: { path: { id: runId }, query: { bookId: bookId ?? undefined, limit: RESULT_PAGE } } },
    {
      initialPageParam: null as string | null,
      getNextPageParam: (page) => page.nextCursor ?? undefined,
      pageParamName: 'cursor',
    },
  );
  const resultRows = useMemo(() => results.data?.pages.flatMap((page) => page.items) ?? [], [results.data]);
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
      { field: 'payDate', headerName: t('valuation.payDate'), width: 120 },
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
        field: 'discountFactor',
        headerName: t('valuation.discountFactor'),
        headerTooltip: t('valuation.discountHint'),
        width: 130,
        type: 'rightAligned',
        valueFormatter: (p) =>
          p.value === null || p.value === undefined ? '—' : formatDecimal(p.value as number, 2, 6),
      },
      {
        field: 'mtmUndiscounted',
        headerName: t('valuation.mtmUndiscounted'),
        headerTooltip: t('valuation.discountHint'),
        width: 170,
        type: 'rightAligned',
        valueFormatter: (p) =>
          p.value === null || p.value === undefined ? '—' : formatMoney(p.value as number, p.data?.contractCurrency),
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
      ...optionColumns(t),
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
      <Tabs value={tab} onChange={(value) => setTab(value)}>
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
                  rowData={resultRows}
                  columnDefs={columns}
                  rowId={(r) => r.scheduleId}
                  height={460}
                  overlayNoRowsTemplate={t('common.empty')}
                />
              )}
              <LoadMore query={results} shown={resultRows.length} testId="result-count" />
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
