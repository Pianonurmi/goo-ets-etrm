import { useUrlState } from '@/shared/useUrlState';
import {
  Anchor,
  Button,
  Group,
  MultiSelect,
  Paper,
  Select,
  SimpleGrid,
  Stack,
  Tabs,
  Text,
  TextInput,
} from '@mantine/core';
import { useDebouncedValue } from '@mantine/hooks';
import { IconFilterOff, IconPlus, IconSearch } from '@tabler/icons-react';
import type { ColDef, ICellRendererParams } from 'ag-grid-community';
import dayjs from 'dayjs';
import { useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link, useNavigate } from 'react-router';
import { $api } from '@/shared/api/client';
import type { Schema } from '@/shared/api/types';
import { Can } from '@/shared/auth/Can';
import { EmptyState } from '@/shared/components/EmptyState';
import { Side } from '@/shared/components/Figures';
import { KpiCard } from '@/shared/components/KpiCard';
import { PageHeader } from '@/shared/components/PageHeader';
import { QueryState } from '@/shared/components/QueryState';
import { TableSkeleton } from '@/shared/components/Skeletons';
import { statusLabel } from '@/shared/components/StatusBadge';
import { useEntity } from '@/shared/context/EntityContext';
import { formatDecimal, formatMoney } from '@/shared/format/format';
import { DataGrid } from '@/shared/grid/DataGrid';
import { allOf, oneOfOrNull } from '@/shared/urlParams';
import { useBooks, useCounterparties } from './hooks';
import { dueWithin } from './model/lifecycle';
import { ObligationsPanel } from './ObligationsPanel';
import { TradeStatusBadge } from './TradeStatusBadge';

type Row = Schema<'TradeSummaryView'>;
type Status = Row['status'];
type Family = Row['productFamily'];
const FAMILIES: Family[] = ['GUARANTEE_OF_ORIGIN', 'EMISSION_ALLOWANCE'];
const STATUSES: Status[] = [
  'DRAFT',
  'PENDING_APPROVAL',
  'APPROVED',
  'CONFIRMED',
  'PARTIALLY_DELIVERED',
  'DELIVERED',
  'SETTLED',
  'CLOSED',
  'CANCELLED',
  'DISPUTED',
];
const FILTERS = ['q', 'status', 'family', 'from', 'to'];

/** The desk's headline figures: approvals waiting, recent activity and the deliveries coming due (counts only). */
function TradeKpis({ entityId }: { entityId: string }) {
  const { t } = useTranslation();
  const summary = $api.useQuery(
    'get',
    '/api/v1/home/summary',
    { params: { query: { legalEntityId: entityId } } },
    { refetchInterval: 60_000 },
  );
  const obligations = $api.useQuery('get', '/api/v1/trades/obligations', {
    params: { query: { legalEntityId: entityId } },
  });
  const today = dayjs().format('YYYY-MM-DD');
  const open = obligations.data ?? [];
  const overdue = dueWithin(open, today, -1);
  const due = dueWithin(open, today, 30);
  const trades = summary.data?.trades;
  const none = '—';
  return (
    <SimpleGrid cols={{ base: 1, xs: 2, lg: 4 }} spacing="md" data-testid="trade-kpis">
      <KpiCard
        label={t('trades.kpi.pending')}
        value={trades ? trades.pendingApproval : none}
        loading={summary.isPending}
        trend={trades && trades.pendingApproval > 0 ? t('trades.kpi.pendingHint') : t('trades.kpi.nothingPending')}
        trendTone={trades && trades.pendingApproval > 0 ? 'warning' : 'neutral'}
        to="/trades?status=PENDING_APPROVAL"
        testId="kpi-pending"
      />
      <KpiCard
        label={t('trades.kpi.lastWeek')}
        value={trades ? trades.lastSevenDays : none}
        loading={summary.isPending}
        trend={t('trades.kpi.lastWeekHint')}
        testId="kpi-last-week"
      />
      <KpiCard
        label={t('trades.kpi.openDeliveries')}
        value={obligations.data ? open.length : none}
        loading={obligations.isPending}
        trend={t('trades.kpi.openDeliveriesHint')}
        to="/trades?view=deliveries"
        testId="kpi-open-deliveries"
      />
      <KpiCard
        label={t('trades.kpi.due30')}
        value={obligations.data ? due : none}
        loading={obligations.isPending}
        trend={overdue > 0 ? t('trades.kpi.overdue', { count: overdue }) : t('trades.kpi.nothingOverdue')}
        trendTone={overdue > 0 ? 'down' : due > 0 ? 'warning' : 'neutral'}
        to="/trades?view=deliveries"
        testId="kpi-due"
      />
    </SimpleGrid>
  );
}

/** Trade blotter (one query per page, `trade_search`, filters in the URL, newest first) and open deliveries. */
export function TradesPage() {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const { entity } = useEntity();
  const counterparties = useCounterparties();
  const books = useBooks(entity?.id);
  const { params, set: patchUrl } = useUrlState();
  const [text, setText] = useState(params.get('q') ?? '');
  const [q] = useDebouncedValue(text.trim(), 300);
  const statuses = allOf(params.getAll('status'), STATUSES);
  const family = oneOfOrNull(params.get('family'), FAMILIES);
  const view = params.get('view') === 'deliveries' ? 'deliveries' : 'blotter';
  const from = params.get('from') ?? '';
  const to = params.get('to') ?? '';
  const filtered = FILTERS.some((key) => params.has(key));

  const set = (key: string, values: string[]) => patchUrl({ [key]: values });
  const clearFilters = () => {
    setText('');
    patchUrl(Object.fromEntries(FILTERS.map((key) => [key, null])));
  };

  const search = $api.useInfiniteQuery(
    'get',
    '/api/v1/trades/search',
    {
      params: {
        query: {
          legalEntityId: entity?.id,
          status: statuses.length > 0 ? statuses : undefined,
          productFamily: family ?? undefined,
          from: from || undefined,
          to: to || undefined,
          q: q || undefined,
          limit: 200,
        },
      },
    },
    {
      enabled: Boolean(entity) && view === 'blotter',
      initialPageParam: null as string | null,
      getNextPageParam: (page) => page.nextCursor ?? undefined,
      pageParamName: 'cursor',
    },
  );
  const rows = useMemo(() => search.data?.pages.flatMap((page) => page.items) ?? [], [search.data]);

  const columns = useMemo<ColDef<Row>[]>(
    () => [
      {
        field: 'tradeNumber',
        headerName: t('trades.columns.number'),
        width: 150,
        pinned: 'left',
        cellStyle: { fontWeight: 600 },
      },
      { field: 'tradeDate', headerName: t('trades.columns.date'), width: 120 },
      {
        field: 'status',
        headerName: t('trades.columns.status'),
        width: 170,
        cellRenderer: (p: ICellRendererParams<Row>) => (p.data ? <TradeStatusBadge status={p.data.status} /> : null),
      },
      {
        field: 'side',
        headerName: t('trades.columns.side'),
        width: 90,
        cellRenderer: (p: ICellRendererParams<Row>) => (p.data ? <Side side={p.data.side} /> : null),
      },
      { field: 'product', headerName: t('trades.columns.product'), flex: 1, minWidth: 220, tooltipField: 'product' },
      {
        colId: 'quantity',
        headerName: t('trades.columns.quantity'),
        valueGetter: (p) => (p.data ? `${formatDecimal(p.data.quantity, 0, 3)} ${p.data.unit}` : ''),
        width: 140,
        type: 'rightAligned',
      },
      {
        colId: 'price',
        headerName: t('trades.columns.price'),
        valueGetter: (p) => (p.data ? `${formatDecimal(p.data.price, 2, 6)} ${p.data.priceCurrency}` : ''),
        width: 130,
        type: 'rightAligned',
      },
      {
        colId: 'notional',
        headerName: t('trades.columns.notional'),
        valueGetter: (p) => (p.data ? formatMoney(p.data.notional, p.data.priceCurrency) : ''),
        width: 160,
        type: 'rightAligned',
      },
      {
        colId: 'counterparty',
        headerName: t('trades.columns.counterparty'),
        valueGetter: (p) =>
          p.data ? (counterparties.byId.get(p.data.counterpartyId)?.name ?? p.data.counterpartyId) : '',
        width: 230,
      },
      {
        colId: 'book',
        headerName: t('trades.columns.book'),
        valueGetter: (p) => (p.data ? (books.byId.get(p.data.bookId)?.code ?? '') : ''),
        width: 140,
      },
      {
        field: 'instrumentType',
        headerName: t('trades.columns.instrument'),
        width: 120,
        valueFormatter: (p) => (p.value ? t(`trades.instruments.${String(p.value)}`) : ''),
      },
      { field: 'trader', headerName: t('trades.columns.trader'), width: 120 },
      { field: 'externalRef', headerName: t('trades.columns.externalRef'), width: 150 },
      { field: 'uti', headerName: 'UTI', width: 220 },
    ],
    [t, counterparties.byId, books.byId],
  );

  return (
    <Stack gap="lg">
      <PageHeader
        title={t('trades.title')}
        description={t('trades.description', { entity: entity?.code ?? '' })}
        actions={
          <Can op="trade_capture">
            <Button component={Link} to="/trades/new" leftSection={<IconPlus size={16} />} data-testid="new-trade">
              {t('trades.new')}
            </Button>
          </Can>
        }
      />
      {entity && <TradeKpis entityId={entity.id} />}
      <Tabs value={view} onChange={(value) => set('view', value === 'deliveries' ? ['deliveries'] : [])}>
        <Tabs.List>
          <Tabs.Tab value="blotter">{t('trades.ops.blotter')}</Tabs.Tab>
          <Tabs.Tab value="deliveries" data-testid="tab-deliveries">
            {t('trades.ops.openDeliveries')}
          </Tabs.Tab>
        </Tabs.List>
      </Tabs>
      {view === 'deliveries' && entity && <ObligationsPanel entityId={entity.id} />}
      {view === 'blotter' && (
        <Stack gap="sm">
          <Group align="flex-end" gap="sm" wrap="wrap">
            <TextInput
              label={t('trades.search')}
              placeholder={t('trades.searchHint')}
              leftSection={<IconSearch size={14} />}
              value={text}
              onChange={(event) => {
                setText(event.currentTarget.value);
                set('q', [event.currentTarget.value.trim()]);
              }}
              w={260}
              data-testid="trade-search"
            />
            <MultiSelect
              label={t('trades.columns.status')}
              data={STATUSES.map((status) => ({ value: status, label: statusLabel(status) }))}
              value={statuses}
              onChange={(value) => set('status', value)}
              placeholder={statuses.length === 0 ? t('trades.allStatuses') : undefined}
              clearable
              w={260}
            />
            <Select
              label={t('trades.family')}
              data={[
                { value: 'GUARANTEE_OF_ORIGIN', label: t('trades.families.GUARANTEE_OF_ORIGIN') },
                { value: 'EMISSION_ALLOWANCE', label: t('trades.families.EMISSION_ALLOWANCE') },
              ]}
              value={family}
              onChange={(value) => set('family', value ? [value] : [])}
              placeholder={t('trades.allFamilies')}
              clearable
              w={200}
            />
            <TextInput
              type="date"
              label={t('trades.from')}
              value={from}
              onChange={(e) => set('from', [e.currentTarget.value])}
              w={150}
            />
            <TextInput
              type="date"
              label={t('trades.to')}
              value={to}
              onChange={(e) => set('to', [e.currentTarget.value])}
              w={150}
            />
            {filtered && (
              <Button variant="subtle" color="gray" leftSection={<IconFilterOff size={14} />} onClick={clearFilters}>
                {t('trades.clearFilters')}
              </Button>
            )}
          </Group>
          <QueryState isPending={false} error={search.error}>
            {search.isPending && entity ? (
              <Paper withBorder radius="lg">
                <TableSkeleton rows={10} />
              </Paper>
            ) : rows.length === 0 ? (
              <Paper withBorder radius="lg">
                <EmptyState
                  title={filtered ? t('trades.emptyFiltered') : t('trades.empty')}
                  description={filtered ? t('trades.emptyFilteredHint') : t('trades.emptyHint')}
                  testId="trades-empty"
                  action={
                    filtered ? (
                      <Button variant="light" onClick={clearFilters}>
                        {t('trades.clearFilters')}
                      </Button>
                    ) : (
                      <Can op="trade_capture">
                        <Button component={Link} to="/trades/new" leftSection={<IconPlus size={16} />}>
                          {t('trades.new')}
                        </Button>
                      </Can>
                    )
                  }
                />
              </Paper>
            ) : (
              <DataGrid<Row>
                rowData={rows}
                columnDefs={columns}
                rowId={(row) => row.id}
                onRowClicked={(event) => {
                  if (event.data) void navigate(`/trades/${event.data.id}`);
                }}
                rowStyle={{ cursor: 'pointer' }}
                overlayNoRowsTemplate={t('common.empty')}
                height={560}
              />
            )}
            <Group justify="space-between">
              <Text size="sm" c="dimmed">
                {t('trades.shown', { count: rows.length })}
              </Text>
              {search.hasNextPage && (
                <Anchor component="button" size="sm" onClick={() => void search.fetchNextPage()}>
                  {search.isFetchingNextPage ? t('app.loading') : t('inbox.loadMore')}
                </Anchor>
              )}
            </Group>
          </QueryState>
        </Stack>
      )}
    </Stack>
  );
}
