import { Group, Paper, Select, Stack, Text, TextInput } from '@mantine/core';
import { IconSearch } from '@tabler/icons-react';
import type { ColDef, ICellRendererParams } from 'ag-grid-community';
import { useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useNavigate } from 'react-router';
import { $api } from '@/shared/api/client';
import type { Schema } from '@/shared/api/types';
import { EmptyState } from '@/shared/components/EmptyState';
import { Side as SideLabel } from '@/shared/components/Figures';
import { QueryState } from '@/shared/components/QueryState';
import { TableSkeleton } from '@/shared/components/Skeletons';
import { StatusBadge } from '@/shared/components/StatusBadge';
import { formatDecimal, formatMoney } from '@/shared/format/format';
import { DataGrid } from '@/shared/grid/DataGrid';
import { useCounterparties } from './hooks';
import { unitOf } from './model/tradeForm';

type Row = Schema<'DeliveryObligationView'>;
type Side = Row['side'];
type Family = Row['productFamily'];

/** Open delivery obligations of the entity, earliest first: what still has to be delivered or received. */
export function ObligationsPanel({ entityId }: { entityId: string }) {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const counterparties = useCounterparties();
  const [side, setSide] = useState<Side | null>(null);
  const [family, setFamily] = useState<Family | null>(null);
  const [text, setText] = useState('');
  const obligations = $api.useQuery('get', '/api/v1/trades/obligations', {
    params: { query: { legalEntityId: entityId, side: side ?? undefined, productFamily: family ?? undefined } },
  });
  const rows = useMemo(
    () => [...(obligations.data ?? [])].sort((a, b) => a.deliveryDate.localeCompare(b.deliveryDate)),
    [obligations.data],
  );
  const columns = useMemo<ColDef<Row>[]>(
    () => [
      { field: 'deliveryDate', headerName: t('trades.form.deliveryDate'), width: 130, sort: 'asc' },
      { field: 'tradeNumber', headerName: t('trades.columns.number'), width: 150, cellStyle: { fontWeight: 600 } },
      {
        field: 'side',
        headerName: t('trades.columns.side'),
        width: 90,
        cellRenderer: (p: ICellRendererParams<Row>) => (p.data ? <SideLabel side={p.data.side} /> : null),
      },
      {
        colId: 'product',
        headerName: t('trades.columns.product'),
        valueGetter: (p) => p.data?.allowanceProductCode ?? t(`trades.familiesShort.${p.data?.productFamily ?? ''}`),
        width: 110,
      },
      {
        colId: 'counterparty',
        headerName: t('trades.columns.counterparty'),
        valueGetter: (p) =>
          p.data ? (counterparties.byId.get(p.data.counterpartyId)?.name ?? p.data.counterpartyId) : '',
        flex: 1,
        minWidth: 200,
      },
      {
        colId: 'quantity',
        headerName: t('trades.scheduled'),
        valueGetter: (p) => (p.data ? `${formatDecimal(p.data.quantity, 0, 3)} ${unitOf(p.data.productFamily)}` : ''),
        width: 140,
        type: 'rightAligned',
      },
      {
        colId: 'remaining',
        headerName: t('trades.ops.remaining'),
        valueGetter: (p) => (p.data ? formatDecimal(p.data.remainingQuantity, 0, 3) : ''),
        width: 130,
        type: 'rightAligned',
      },
      {
        colId: 'price',
        headerName: t('trades.columns.price'),
        valueGetter: (p) =>
          p.data
            ? p.data.priceType === 'INDEX'
              ? `${p.data.priceIndex ?? ''} + ${formatDecimal(p.data.price, 2, 6)}`
              : formatMoney(p.data.price, p.data.priceCurrency)
            : '',
        width: 190,
        type: 'rightAligned',
      },
      { field: 'counterpartyAccountRef', headerName: t('trades.form.counterpartyAccount'), width: 200 },
      {
        field: 'status',
        headerName: t('trades.columns.status'),
        width: 170,
        cellRenderer: (p: ICellRendererParams<Row>) => (p.data ? <StatusBadge status={p.data.status} /> : null),
      },
    ],
    [t, counterparties.byId],
  );
  return (
    <Stack gap="sm">
      <Group align="flex-end" gap="sm">
        <TextInput
          label={t('trades.search')}
          placeholder={t('trades.ops.searchDeliveries')}
          leftSection={<IconSearch size={14} />}
          value={text}
          onChange={(event) => setText(event.currentTarget.value)}
          w={260}
          data-testid="obligation-search"
        />
        <Select
          label={t('trades.columns.side')}
          data={['BUY', 'SELL']}
          value={side}
          onChange={setSide}
          placeholder={t('trades.ops.bothSides')}
          clearable
          w={160}
        />
        <Select
          label={t('trades.family')}
          data={[
            { value: 'GUARANTEE_OF_ORIGIN', label: t('trades.families.GUARANTEE_OF_ORIGIN') },
            { value: 'EMISSION_ALLOWANCE', label: t('trades.families.EMISSION_ALLOWANCE') },
          ]}
          value={family}
          onChange={setFamily}
          placeholder={t('trades.allFamilies')}
          clearable
          w={220}
        />
      </Group>
      <QueryState isPending={false} error={obligations.error}>
        {obligations.isPending ? (
          <Paper withBorder radius="lg">
            <TableSkeleton rows={10} />
          </Paper>
        ) : rows.length === 0 && !side && !family ? (
          <Paper withBorder radius="lg">
            <EmptyState
              title={t('trades.ops.noObligations')}
              description={t('trades.ops.noObligationsHint')}
              testId="obligations-empty"
            />
          </Paper>
        ) : (
          <div data-testid="obligations-grid">
            <DataGrid<Row>
              rowData={rows}
              columnDefs={columns}
              rowId={(row) => row.scheduleId}
              quickFilterText={text}
              onRowClicked={(event) => {
                if (event.data) void navigate(`/trades/${event.data.tradeId}`);
              }}
              rowStyle={{ cursor: 'pointer' }}
              overlayNoRowsTemplate={t('trades.ops.noObligations')}
              height={560}
            />
          </div>
        )}
        <Text size="sm" c="dimmed">
          {t('trades.ops.obligationsShown', { count: rows.length })}
        </Text>
      </QueryState>
    </Stack>
  );
}
