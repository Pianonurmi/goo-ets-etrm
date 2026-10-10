import { Alert, Badge, Button, Group, MultiSelect, Paper, Stack, Tabs, Text, TextInput, Title } from '@mantine/core';
import { IconSearch } from '@tabler/icons-react';
import type { ColDef } from 'ag-grid-community';
import dayjs from 'dayjs';
import { useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useNavigate, useSearchParams } from 'react-router';
import { $api } from '@/shared/api/client';
import type { Schema } from '@/shared/api/types';
import { PageHeader } from '@/shared/components/PageHeader';
import { QueryState } from '@/shared/components/QueryState';
import { useEntity } from '@/shared/context/EntityContext';
import { formatDecimal } from '@/shared/format/format';
import { DataGrid } from '@/shared/grid/DataGrid';
import { CriteriaBuilder } from '@/features/criteria/CriteriaBuilder';
import { EXAMPLE, incomplete, type Criterion } from '@/features/criteria/model/tree';
import { lotColumns, totals, type Lot } from './lotColumns';

type LotStatus = NonNullable<Schema<'LotSearchRequest'>['statuses']>[number];
const STATUSES: LotStatus[] = [
  'HELD',
  'RESERVED',
  'IN_TRANSFER',
  'TRANSFERRED_OUT',
  'CANCELLED',
  'EXPIRED',
  'WITHDRAWN',
  'SPLIT',
];
const LIVE: LotStatus[] = ['HELD', 'RESERVED', 'IN_TRANSFER'];

function Totals({ lots }: { lots: Lot[] }) {
  const { t } = useTranslation();
  const { count, mwh } = totals(lots);
  return (
    <Group gap="xs" data-testid="lot-totals">
      <Badge variant="light">{t('inventory.lotCount', { count })}</Badge>
      <Badge variant="light" color="teal">
        {formatDecimal(mwh)} MWh
      </Badge>
    </Group>
  );
}

function useOpenLot() {
  const navigate = useNavigate();
  return (lot: Lot | undefined) => {
    if (lot) void navigate(`/inventory/lots/${lot.id}`);
  };
}

function LotsTab({ entityId }: { entityId: string }) {
  const { t } = useTranslation();
  const open = useOpenLot();
  const [params, setParams] = useSearchParams();
  const statuses = params.getAll('status') as LotStatus[];
  const expiringBefore = params.get('expiringBefore') ?? '';
  const lots = $api.useQuery('get', '/api/v1/inventory/lots', {
    params: {
      query: {
        holderEntityId: entityId,
        status: statuses.length > 0 ? statuses : LIVE,
        expiringBefore: expiringBefore || undefined,
        limit: 2000,
      },
    },
  });
  const columns = useMemo(() => lotColumns(t), [t]);
  const update = (changes: { status?: string[]; expiringBefore?: string }) => {
    const next = new URLSearchParams(params);
    if (changes.status) {
      next.delete('status');
      changes.status.forEach((status) => next.append('status', status));
    }
    if (changes.expiringBefore !== undefined) {
      if (changes.expiringBefore) next.set('expiringBefore', changes.expiringBefore);
      else next.delete('expiringBefore');
    }
    setParams(next, { replace: true });
  };
  return (
    <Stack>
      <Group align="flex-end">
        <MultiSelect
          label={t('inventory.status')}
          data={STATUSES}
          value={statuses}
          placeholder={t('inventory.liveOnly')}
          onChange={(value) => update({ status: value })}
          clearable
          w={360}
        />
        <TextInput
          type="date"
          label={t('inventory.expiringBefore')}
          value={expiringBefore}
          onChange={(event) => update({ expiringBefore: event.currentTarget.value })}
          w={180}
        />
        <Button
          variant="subtle"
          onClick={() => update({ expiringBefore: dayjs().add(90, 'day').format('YYYY-MM-DD') })}
        >
          {t('inventory.next90Days')}
        </Button>
        {lots.data && <Totals lots={lots.data} />}
      </Group>
      <QueryState isPending={lots.isPending} error={lots.error}>
        <DataGrid<Lot>
          rowData={lots.data ?? []}
          columnDefs={columns}
          rowId={(lot) => lot.id}
          onRowClicked={(event) => open(event.data)}
          overlayNoRowsTemplate={t('common.empty')}
        />
      </QueryState>
    </Stack>
  );
}

function SearchTab({ entityId }: { entityId: string }) {
  const { t } = useTranslation();
  const open = useOpenLot();
  const [criterion, setCriterion] = useState<Criterion>(EXAMPLE);
  const [deliveryDate, setDeliveryDate] = useState(dayjs().format('YYYY-MM-DD'));
  const [statuses, setStatuses] = useState<LotStatus[]>(['HELD']);
  const search = $api.useMutation('post', '/api/v1/inventory/lots/search', { meta: { notify: false } });
  const columns = useMemo(() => lotColumns(t), [t]);
  const run = () =>
    search.mutate({ body: { holderEntityId: entityId, criterion, deliveryDate, statuses, limit: 2000 } });
  return (
    <Stack>
      <Paper withBorder p="md">
        <Stack>
          <Title order={5}>{t('inventory.criteria')}</Title>
          <CriteriaBuilder value={criterion} onChange={setCriterion} />
          <Group align="flex-end">
            <TextInput
              type="date"
              label={t('inventory.deliveryDate')}
              description={t('inventory.deliveryDateHint')}
              value={deliveryDate}
              onChange={(event) => setDeliveryDate(event.currentTarget.value)}
              w={200}
            />
            <MultiSelect
              label={t('inventory.status')}
              data={STATUSES}
              value={statuses}
              onChange={(value) => setStatuses(value)}
              w={320}
            />
            <Button
              leftSection={<IconSearch size={16} />}
              onClick={run}
              loading={search.isPending}
              disabled={incomplete(criterion).length > 0 || statuses.length === 0}
              data-testid="run-search"
            >
              {t('inventory.search')}
            </Button>
          </Group>
        </Stack>
      </Paper>
      {search.error && (
        <QueryState isPending={false} error={search.error}>
          {null}
        </QueryState>
      )}
      {search.data && (
        <Stack>
          <Totals lots={search.data} />
          <DataGrid<Lot>
            rowData={search.data}
            columnDefs={columns}
            rowId={(lot) => lot.id}
            onRowClicked={(event) => open(event.data)}
            overlayNoRowsTemplate={t('inventory.noMatch')}
          />
        </Stack>
      )}
    </Stack>
  );
}

type Holding = Schema<'AllowanceHoldingView'>;

function AllowancesTab({ entityId }: { entityId: string }) {
  const { t } = useTranslation();
  const holdings = $api.useQuery('get', '/api/v1/inventory/holdings', {
    params: { query: { holderEntityId: entityId } },
  });
  const [selected, setSelected] = useState<Holding | null>(null);
  const movements = $api.useQuery(
    'get',
    '/api/v1/inventory/holdings/{id}/movements',
    { params: { path: { id: selected?.id ?? '' } } },
    { enabled: Boolean(selected) },
  );
  const columns = useMemo<ColDef<Holding>[]>(
    () => [
      { field: 'productCode', headerName: t('inventory.columns.product'), width: 140 },
      { field: 'quantity', headerName: t('inventory.columns.quantity'), width: 140, type: 'rightAligned' },
      { field: 'reservedQuantity', headerName: t('inventory.columns.reserved'), width: 140, type: 'rightAligned' },
      { field: 'registryAccountId', headerName: t('inventory.columns.account'), flex: 1 },
    ],
    [t],
  );
  const movementColumns = useMemo<ColDef<Schema<'AllowanceMovementView'>>[]>(
    () => [
      { field: 'effectiveDate', headerName: t('inventory.columns.date'), width: 130 },
      { field: 'type', headerName: t('inventory.columns.type'), width: 160 },
      { field: 'quantity', headerName: t('inventory.columns.quantity'), width: 120, type: 'rightAligned' },
      { field: 'complianceYear', headerName: t('inventory.columns.complianceYear'), width: 150 },
      { field: 'reference', headerName: t('inventory.columns.reference'), flex: 1 },
      { field: 'actor', headerName: t('inventory.columns.actor'), width: 130 },
    ],
    [t],
  );
  return (
    <Stack>
      <QueryState isPending={holdings.isPending} error={holdings.error}>
        <DataGrid<Holding>
          rowData={holdings.data ?? []}
          columnDefs={columns}
          rowId={(holding) => holding.id}
          onRowClicked={(event) => setSelected(event.data ?? null)}
          height={240}
          overlayNoRowsTemplate={t('common.empty')}
        />
      </QueryState>
      {selected && (
        <Stack gap="xs">
          <Text fw={600}>{t('inventory.movementsOf', { product: selected.productCode })}</Text>
          <QueryState isPending={movements.isPending} error={movements.error}>
            <DataGrid rowData={movements.data ?? []} columnDefs={movementColumns} height={320} />
          </QueryState>
        </Stack>
      )}
    </Stack>
  );
}

export function InventoryPage() {
  const { t } = useTranslation();
  const { entity } = useEntity();
  const [params, setParams] = useSearchParams();
  const tab = params.get('tab') ?? 'lots';
  return (
    <Stack>
      <PageHeader
        title={t('inventory.title')}
        description={t('inventory.description', { entity: entity?.code ?? '' })}
      />
      {!entity ? (
        <Alert color="gray">{t('header.noEntity')}</Alert>
      ) : (
        <Tabs
          value={tab}
          onChange={(value) => setParams(value && value !== 'lots' ? { tab: value } : {}, { replace: true })}
          keepMounted={false}
        >
          <Tabs.List>
            <Tabs.Tab value="lots">{t('inventory.tabs.lots')}</Tabs.Tab>
            <Tabs.Tab value="search" data-testid="tab-search">
              {t('inventory.tabs.search')}
            </Tabs.Tab>
            <Tabs.Tab value="allowances">{t('inventory.tabs.allowances')}</Tabs.Tab>
          </Tabs.List>
          <Tabs.Panel value="lots" pt="md">
            <LotsTab entityId={entity.id} />
          </Tabs.Panel>
          <Tabs.Panel value="search" pt="md">
            <SearchTab entityId={entity.id} />
          </Tabs.Panel>
          <Tabs.Panel value="allowances" pt="md">
            <AllowancesTab entityId={entity.id} />
          </Tabs.Panel>
        </Tabs>
      )}
    </Stack>
  );
}
