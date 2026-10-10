import { Button, Group, MultiSelect, Select, Stack, Text, TextInput } from '@mantine/core';
import { useDebouncedValue } from '@mantine/hooks';
import { IconPlus, IconSearch } from '@tabler/icons-react';
import type { ColDef, ICellRendererParams } from 'ag-grid-community';
import { useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link, useNavigate, useSearchParams } from 'react-router';
import { $api } from '@/shared/api/client';
import type { Schema } from '@/shared/api/types';
import { Can } from '@/shared/auth/Can';
import { PageHeader } from '@/shared/components/PageHeader';
import { QueryState } from '@/shared/components/QueryState';
import { useEntity } from '@/shared/context/EntityContext';
import { formatDecimal, formatMoney } from '@/shared/format/format';
import { DataGrid } from '@/shared/grid/DataGrid';
import { useBooks, useCounterparties } from './hooks';
import { TradeStatusBadge } from './TradeStatusBadge';

type Row = Schema<'TradeSummaryView'>;
type Status = Row['status'];
type Family = Row['productFamily'];
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

/** Trade blotter: one query per page (`trade_search`), filters in the URL, newest first. */
export function TradesPage() {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const { entity } = useEntity();
  const counterparties = useCounterparties();
  const books = useBooks(entity?.id);
  const [params, setParams] = useSearchParams();
  const [text, setText] = useState(params.get('q') ?? '');
  const [q] = useDebouncedValue(text.trim(), 300);
  const statuses = params.getAll('status') as Status[];
  const family = (params.get('family') ?? null) as Family | null;
  const from = params.get('from') ?? '';
  const to = params.get('to') ?? '';

  const set = (key: string, values: string[]) => {
    const next = new URLSearchParams(params);
    next.delete(key);
    values.filter(Boolean).forEach((value) => next.append(key, value));
    setParams(next, { replace: true });
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
      enabled: Boolean(entity),
      initialPageParam: null as string | null,
      getNextPageParam: (page) => page.nextCursor ?? undefined,
      pageParamName: 'cursor',
    },
  );
  const rows = useMemo(() => search.data?.pages.flatMap((page) => page.items) ?? [], [search.data]);

  const columns = useMemo<ColDef<Row>[]>(
    () => [
      { field: 'tradeNumber', headerName: t('trades.columns.number'), width: 150, pinned: 'left' },
      { field: 'tradeDate', headerName: t('trades.columns.date'), width: 120 },
      {
        field: 'status',
        headerName: t('trades.columns.status'),
        width: 170,
        cellRenderer: (p: ICellRendererParams<Row>) => (p.data ? <TradeStatusBadge status={p.data.status} /> : null),
      },
      { field: 'side', headerName: t('trades.columns.side'), width: 90 },
      { field: 'product', headerName: t('trades.columns.product'), flex: 1, minWidth: 220 },
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
        width: 140,
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
      { field: 'instrumentType', headerName: t('trades.columns.instrument'), width: 120 },
      { field: 'trader', headerName: t('trades.columns.trader'), width: 120 },
      { field: 'externalRef', headerName: t('trades.columns.externalRef'), width: 150 },
      { field: 'uti', headerName: 'UTI', width: 220 },
    ],
    [t, counterparties.byId, books.byId],
  );

  return (
    <Stack>
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
      <Group align="flex-end">
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
          data={STATUSES.map((status) => ({ value: status, label: status.replace(/_/g, ' ') }))}
          value={statuses}
          onChange={(value) => set('status', value)}
          placeholder={t('trades.allStatuses')}
          clearable
          w={320}
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
          w={160}
        />
        <TextInput
          type="date"
          label={t('trades.to')}
          value={to}
          onChange={(e) => set('to', [e.currentTarget.value])}
          w={160}
        />
      </Group>
      <QueryState isPending={search.isPending && Boolean(entity)} error={search.error}>
        <DataGrid<Row>
          rowData={rows}
          columnDefs={columns}
          rowId={(row) => row.id}
          onRowClicked={(event) => {
            if (event.data) void navigate(`/trades/${event.data.id}`);
          }}
          overlayNoRowsTemplate={t('common.empty')}
          height={600}
        />
        <Group justify="space-between">
          <Text size="sm" c="dimmed">
            {t('trades.shown', { count: rows.length })}
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
