import { BarChart } from '@mantine/charts';
import {
  Alert,
  Anchor,
  Badge,
  Button,
  Card,
  Group,
  Select,
  SimpleGrid,
  Stack,
  Tabs,
  Text,
  TextInput,
  Title,
} from '@mantine/core';
import { IconArrowLeft, IconInbox, IconPlayerPlay } from '@tabler/icons-react';
import { useQueryClient } from '@tanstack/react-query';
import type { ColDef } from 'ag-grid-community';
import dayjs from 'dayjs';
import { useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link, useNavigate, useParams, useSearchParams } from 'react-router';
import { $api } from '@/shared/api/client';
import type { Schema } from '@/shared/api/types';
import { Can } from '@/shared/auth/Can';
import { notifySuccess } from '@/shared/components/notify';
import { PageHeader } from '@/shared/components/PageHeader';
import { ProblemAlert } from '@/shared/components/ProblemAlert';
import { QueryState } from '@/shared/components/QueryState';
import { useEntity } from '@/shared/context/EntityContext';
import { formatDateTime, formatDecimal, formatMoney } from '@/shared/format/format';
import { DataGrid } from '@/shared/grid/DataGrid';
import { useBooks } from '@/features/trades/hooks';
import { explainBars } from './model/explain';

type Run = Schema<'ValuationRunView'>;
type Result = Schema<'ValuationResultView'>;

export function RunStatusBadge({ status }: { status: string }) {
  const colors: Record<string, string> = {
    DRAFT: 'gray',
    PENDING_SIGN_OFF: 'yellow',
    OFFICIAL: 'teal',
    SUPERSEDED: 'gray',
    REJECTED: 'red',
  };
  return (
    <Badge color={colors[status] ?? 'gray'} variant="light" data-testid="run-status">
      {status.replace(/_/g, ' ')}
    </Badge>
  );
}

function Runs({ entityId }: { entityId: string }) {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const [date, setDate] = useState(dayjs().subtract(1, 'day').format('YYYY-MM-DD'));
  const runs = $api.useQuery('get', '/api/v1/valuation/runs', {
    params: {
      query: {
        legalEntityId: entityId,
        from: dayjs().subtract(90, 'day').format('YYYY-MM-DD'),
        to: dayjs().add(1, 'day').format('YYYY-MM-DD'),
      },
    },
  });
  const start = $api.useMutation('post', '/api/v1/valuation/runs', {
    meta: { notify: false },
    onSuccess: (run) => void navigate(`/valuation/runs/${run.id}`),
  });
  const columns = useMemo<ColDef<Run>[]>(
    () => [
      { field: 'valuationDate', headerName: t('valuation.date'), width: 130, sort: 'desc' },
      { field: 'kind', headerName: t('marketData.kind'), width: 110 },
      { field: 'status', headerName: t('allocation.status'), width: 160 },
      {
        field: 'totalMtmBase',
        headerName: t('valuation.totalMtm'),
        width: 170,
        type: 'rightAligned',
        valueFormatter: (p) => formatMoney(p.value as number, p.data?.baseCurrency),
      },
      { field: 'exceptions', headerName: t('valuation.exceptions'), width: 120, type: 'rightAligned' },
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
      <QueryState isPending={runs.isPending} error={runs.error}>
        <DataGrid<Run>
          rowData={runs.data ?? []}
          columnDefs={columns}
          rowId={(run) => run.id}
          height={420}
          onRowClicked={(event) => {
            if (event.data) void navigate(`/valuation/runs/${event.data.id}`);
          }}
          overlayNoRowsTemplate={t('common.empty')}
        />
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
  if (!ready) return null;
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
        <Alert color="gray">{t('valuation.noExplain')}</Alert>
      ) : (
        <QueryState isPending={explain.isPending} error={null}>
          {explain.data && (
            <Stack>
              <SimpleGrid cols={{ base: 2, md: 3 }}>
                <Card withBorder>
                  <Text size="xs" c="dimmed">
                    {t('valuation.mtmFrom', { date: explain.data.fromDate })}
                  </Text>
                  <Text fw={700}>{formatMoney(explain.data.mtmFrom, explain.data.baseCurrency)}</Text>
                </Card>
                <Card withBorder>
                  <Text size="xs" c="dimmed">
                    {t('valuation.mtmTo', { date: explain.data.toDate })}
                  </Text>
                  <Text fw={700}>{formatMoney(explain.data.mtmTo, explain.data.baseCurrency)}</Text>
                </Card>
              </SimpleGrid>
              <Card withBorder>
                <Title order={5} mb="sm">
                  {t('valuation.explain')}
                </Title>
                <BarChart
                  h={260}
                  data={explainBars(explain.data.components)}
                  dataKey="component"
                  series={[{ name: 'amount', label: explain.data.baseCurrency, color: 'teal.6' }]}
                  data-testid="explain-chart"
                />
              </Card>
            </Stack>
          )}
        </QueryState>
      )}
    </Stack>
  );
}

/** Valuation runs of the selected entity and the P&L explain between two official runs. */
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
        <Tabs
          value={tab}
          onChange={(value) => setParams(value && value !== 'runs' ? { tab: value } : {}, { replace: true })}
          keepMounted={false}
        >
          <Tabs.List>
            <Tabs.Tab value="runs">{t('valuation.runs')}</Tabs.Tab>
            <Tabs.Tab value="explain">{t('valuation.explain')}</Tabs.Tab>
          </Tabs.List>
          <Tabs.Panel value="runs" pt="md">
            <Runs entityId={entity.id} />
          </Tabs.Panel>
          <Tabs.Panel value="explain" pt="md">
            <Explain entityId={entity.id} />
          </Tabs.Panel>
        </Tabs>
      )}
    </Stack>
  );
}

export function RunDetailPage() {
  const { t } = useTranslation();
  const { runId = '' } = useParams();
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
  const columns = useMemo<ColDef<Result>[]>(
    () => [
      { field: 'tradeNumber', headerName: t('trades.columns.number'), width: 150 },
      { field: 'legNo', headerName: t('valuation.leg'), width: 80 },
      { field: 'deliveryDate', headerName: t('trades.form.deliveryDate'), width: 130 },
      { field: 'productKey', headerName: t('trades.columns.product'), flex: 1, minWidth: 180 },
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
        width: 150,
        type: 'rightAligned',
        valueFormatter: (p) => formatDecimal(p.value as number, 2, 2),
      },
      { field: 'status', headerName: t('allocation.status'), width: 150 },
      { field: 'note', headerName: t('marketData.note'), width: 220 },
    ],
    [t],
  );
  return (
    <Stack>
      <Anchor component={Link} to="/valuation" size="sm">
        <Group gap={4}>
          <IconArrowLeft size={14} />
          {t('valuation.back')}
        </Group>
      </Anchor>
      <QueryState isPending={run.isPending} error={run.error}>
        {run.data && (
          <Card withBorder>
            <Group justify="space-between">
              <Group gap="xs">
                <Title order={3}>
                  {t('valuation.runTitle', { date: run.data.valuationDate, kind: run.data.kind })}
                </Title>
                <RunStatusBadge status={run.data.status} />
              </Group>
              <Group>
                {task.data?.items[0] && (
                  <Button
                    component={Link}
                    to={`/inbox/${task.data.items[0].id}`}
                    variant="light"
                    leftSection={<IconInbox size={16} />}
                  >
                    {t('curves.signOffTask')}
                  </Button>
                )}
                {run.data.status === 'DRAFT' && (
                  <Can op="valuation_submit">
                    <Button
                      loading={submit.isPending}
                      onClick={() => submit.mutate({ params: { path: { id: runId } } })}
                      data-testid="submit-run"
                    >
                      {t('valuation.submit')}
                    </Button>
                  </Can>
                )}
              </Group>
            </Group>
            <SimpleGrid cols={{ base: 2, md: 4 }} mt="md">
              <Text size="sm">
                {t('valuation.totalMtmIs', { amount: formatMoney(run.data.totalMtmBase, run.data.baseCurrency) })}
              </Text>
              <Text size="sm" c={run.data.exceptions > 0 ? 'orange' : undefined}>
                {t('valuation.exceptionsAre', { count: run.data.exceptions })}
              </Text>
              <Text size="sm">
                {t('curves.builtByWho', { who: run.data.createdBy, at: formatDateTime(run.data.createdAt) })}
              </Text>
              <Text size="sm">
                {run.data.signedOffBy
                  ? t('curves.signedOffByWho', { who: run.data.signedOffBy, at: formatDateTime(run.data.signedOffAt) })
                  : t('curves.notSignedOff')}
              </Text>
            </SimpleGrid>
          </Card>
        )}
      </QueryState>
      <Select
        label={t('trades.columns.book')}
        data={books.list.map((book) => ({ value: book.id, label: `${book.code} · ${book.name}` }))}
        value={bookId}
        onChange={setBookId}
        clearable
        placeholder={t('valuation.allBooks')}
        w={300}
      />
      <QueryState isPending={results.isPending} error={results.error}>
        <DataGrid<Result>
          rowData={results.data ?? []}
          columnDefs={columns}
          rowId={(r) => r.scheduleId}
          height={460}
          overlayNoRowsTemplate={t('common.empty')}
        />
      </QueryState>
    </Stack>
  );
}
