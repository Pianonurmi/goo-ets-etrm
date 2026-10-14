import {
  Alert,
  Badge,
  Button,
  Group,
  MultiSelect,
  Paper,
  SimpleGrid,
  Stack,
  Tabs,
  Text,
  TextInput,
  Title,
} from '@mantine/core';
import { IconArrowsTransferUp, IconCalendarDue, IconPlus, IconSearch } from '@tabler/icons-react';
import type { ColDef } from 'ag-grid-community';
import dayjs from 'dayjs';
import { useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useNavigate, useSearchParams } from 'react-router';
import { $api } from '@/shared/api/client';
import type { Schema } from '@/shared/api/types';
import { Can } from '@/shared/auth/Can';
import { EmptyState } from '@/shared/components/EmptyState';
import { KpiCard } from '@/shared/components/KpiCard';
import { PageHeader } from '@/shared/components/PageHeader';
import { QueryState } from '@/shared/components/QueryState';
import { TableSkeleton } from '@/shared/components/Skeletons';
import { statusLabel } from '@/shared/components/StatusBadge';
import { useEntity } from '@/shared/context/EntityContext';
import { formatDecimal } from '@/shared/format/format';
import { DataGrid } from '@/shared/grid/DataGrid';
import { CriteriaBuilder } from '@/features/criteria/CriteriaBuilder';
import { EXAMPLE, incomplete, type Criterion } from '@/features/criteria/model/tree';
import { AllowanceTransferModal } from '@/features/registry/AllowanceTransferModal';
import { allOf, oneOf } from '@/shared/urlParams';
import { ExpiryLadder } from './ExpiryLadder';
import { JournalCheckButton } from './JournalCheck';
import { lotColumns, totals, type Lot } from './lotColumns';
import { ReceiveLotsModal } from './ReceiveLotsModal';
import { RecordMovementModal } from './RecordMovementModal';

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
      <Badge variant="default" radius="xl" tt="none">
        {t('inventory.lotCount', { count })}
      </Badge>
      <Badge variant="light" radius="xl" tt="none">
        {formatDecimal(mwh)} MWh
      </Badge>
    </Group>
  );
}

const inDays = (days: number) => dayjs().add(days, 'day').format('YYYY-MM-DD');

/** The figures that matter for the book of certificates and allowances, from the home summary (server totals). */
function InventoryKpis({ entityId }: { entityId: string }) {
  const { t } = useTranslation();
  const summary = $api.useQuery(
    'get',
    '/api/v1/home/summary',
    { params: { query: { legalEntityId: entityId } } },
    { refetchInterval: 60_000 },
  );
  const goo = summary.data?.goo;
  const eua = summary.data?.eua;
  const none = '—';
  return (
    <SimpleGrid cols={{ base: 1, xs: 2, lg: 4 }} spacing="md" data-testid="inventory-kpis">
      <KpiCard
        label={t('inventory.kpi.live')}
        value={goo ? formatDecimal(goo.liveMwh) : none}
        unit={goo ? 'MWh' : undefined}
        loading={summary.isPending}
        trend={t('inventory.kpi.liveHint')}
        to="/inventory"
      />
      <KpiCard
        label={t('inventory.kpi.expiring30')}
        value={goo ? formatDecimal(goo.expiring30Mwh) : none}
        unit={goo ? 'MWh' : undefined}
        loading={summary.isPending}
        trend={goo && goo.expiring30Mwh > 0 ? t('inventory.kpi.expiring30Hint') : t('inventory.kpi.nothingExpiring')}
        trendTone={goo && goo.expiring30Mwh > 0 ? 'warning' : 'neutral'}
        to={`/inventory?expiringBefore=${inDays(30)}`}
      />
      <KpiCard
        label={t('inventory.kpi.expiring90')}
        value={goo ? formatDecimal(goo.expiring90Mwh) : none}
        unit={goo ? 'MWh' : undefined}
        loading={summary.isPending}
        trend={t('inventory.kpi.expiring90Hint')}
        to={`/inventory?expiringBefore=${inDays(90)}`}
      />
      <KpiCard
        label={t('inventory.kpi.allowances')}
        value={eua ? formatDecimal(eua.heldAllowances) : none}
        unit={eua ? 't' : undefined}
        loading={summary.isPending}
        trend={eua ? t('inventory.kpi.allowancesHint', { year: eua.complianceYear }) : undefined}
        to="/inventory?tab=allowances"
      />
    </SimpleGrid>
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
  const statuses = allOf(params.getAll('status'), STATUSES);
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
    <Stack gap="md">
      <ExpiryLadder entityId={entityId} />
      <Group align="flex-end" gap="sm">
        <MultiSelect
          label={t('inventory.status')}
          data={STATUSES.map((status) => ({ value: status, label: statusLabel(status) }))}
          value={statuses}
          placeholder={statuses.length === 0 ? t('inventory.liveOnly') : undefined}
          onChange={(value) => update({ status: value })}
          clearable
          w={320}
        />
        <TextInput
          type="date"
          label={t('inventory.expiringBefore')}
          value={expiringBefore}
          onChange={(event) => update({ expiringBefore: event.currentTarget.value })}
          w={170}
        />
        <Button
          variant="default"
          leftSection={<IconCalendarDue size={16} />}
          onClick={() => update({ expiringBefore: inDays(90) })}
        >
          {t('inventory.next90Days')}
        </Button>
        {lots.data && (
          <Group ml="auto" mb={6}>
            <Totals lots={lots.data} />
          </Group>
        )}
      </Group>
      <QueryState isPending={false} error={lots.error}>
        {lots.isPending ? (
          <Paper withBorder radius="lg">
            <TableSkeleton rows={10} />
          </Paper>
        ) : (lots.data ?? []).length === 0 ? (
          <Paper withBorder radius="lg">
            <EmptyState
              title={t('inventory.emptyLots')}
              description={t('inventory.emptyLotsHint')}
              testId="lots-empty"
              action={
                <Can op="inventory_receive">
                  <Text size="sm" c="dimmed">
                    {t('inventory.emptyLotsAction')}
                  </Text>
                </Can>
              }
            />
          </Paper>
        ) : (
          <DataGrid<Lot>
            rowData={lots.data ?? []}
            columnDefs={columns}
            rowId={(lot) => lot.id}
            onRowClicked={(event) => open(event.data)}
            rowStyle={{ cursor: 'pointer' }}
            overlayNoRowsTemplate={t('common.empty')}
          />
        )}
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
      <Paper withBorder radius="lg" p="lg">
        <Stack>
          <div>
            <Title order={3} size="h5">
              {t('inventory.criteria')}
            </Title>
            <Text size="sm" c="dimmed">
              {t('inventory.criteriaHint')}
            </Text>
          </div>
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
              data={STATUSES.map((status) => ({ value: status, label: statusLabel(status) }))}
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
            rowStyle={{ cursor: 'pointer' }}
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
  const [dialog, setDialog] = useState<'movement' | 'transfer' | null>(null);
  const accounts = $api.useQuery(
    'get',
    '/api/v1/reference/registry-accounts',
    { params: { query: { holderType: 'LEGAL_ENTITY', holderId: entityId } } },
    { staleTime: 60_000 },
  );
  const accountNumbers = useMemo(
    () => new Map((accounts.data ?? []).map((account) => [account.id, account.accountNumber])),
    [accounts.data],
  );
  const movements = $api.useQuery(
    'get',
    '/api/v1/inventory/holdings/{id}/movements',
    { params: { path: { id: selected?.id ?? '' } } },
    { enabled: Boolean(selected) },
  );
  const columns = useMemo<ColDef<Holding>[]>(
    () => [
      { field: 'productCode', headerName: t('inventory.columns.product'), width: 140 },
      {
        field: 'quantity',
        headerName: t('inventory.columns.tonnes'),
        width: 140,
        type: 'rightAligned',
        valueFormatter: (p) => formatDecimal(p.value as number),
      },
      {
        field: 'reservedQuantity',
        headerName: t('inventory.columns.reserved'),
        width: 140,
        type: 'rightAligned',
        valueFormatter: (p) => formatDecimal(p.value as number),
      },
      {
        field: 'registryAccountId',
        headerName: t('inventory.columns.account'),
        flex: 1,
        valueFormatter: (p) => accountNumbers.get(String(p.value)) ?? String(p.value),
      },
    ],
    [t, accountNumbers],
  );
  const movementColumns = useMemo<ColDef<Schema<'AllowanceMovementView'>>[]>(
    () => [
      { field: 'effectiveDate', headerName: t('inventory.columns.date'), width: 130 },
      {
        field: 'type',
        headerName: t('inventory.columns.type'),
        width: 170,
        valueFormatter: (p) => statusLabel(String(p.value)),
      },
      {
        field: 'quantity',
        headerName: t('inventory.columns.tonnes'),
        width: 120,
        type: 'rightAligned',
        valueFormatter: (p) => formatDecimal(p.value as number),
      },
      { field: 'complianceYear', headerName: t('inventory.columns.complianceYear'), width: 150 },
      { field: 'reference', headerName: t('inventory.columns.reference'), flex: 1 },
      { field: 'actor', headerName: t('inventory.columns.actor'), width: 130 },
    ],
    [t],
  );
  return (
    <Stack>
      <Group justify="space-between">
        <Text size="sm" c="dimmed">
          {t('inventory.allowancesHint')}
        </Text>
        <Group gap="xs">
          <Can op="registry_allowanceTransfer">
            <Button
              variant="light"
              leftSection={<IconArrowsTransferUp size={16} />}
              onClick={() => setDialog('transfer')}
              data-testid="allowance-transfer"
            >
              {t('registry.allowanceTransfer')}
            </Button>
          </Can>
          <Can op="inventory_recordMovement">
            <Button
              leftSection={<IconPlus size={16} />}
              onClick={() => setDialog('movement')}
              data-testid="record-movement"
            >
              {t('inventory.movement.title')}
            </Button>
          </Can>
        </Group>
      </Group>
      <QueryState isPending={holdings.isPending} error={holdings.error}>
        <DataGrid<Holding>
          rowData={holdings.data ?? []}
          columnDefs={columns}
          rowId={(holding) => holding.id}
          onRowClicked={(event) => setSelected(event.data ?? null)}
          rowStyle={{ cursor: 'pointer' }}
          height={240}
          overlayNoRowsTemplate={t('common.empty')}
        />
      </QueryState>
      {selected && (
        <Stack gap="xs">
          <Title order={3} size="h5">
            {t('inventory.movementsOf', { product: selected.productCode })}
          </Title>
          <QueryState isPending={movements.isPending} error={movements.error}>
            <DataGrid rowData={movements.data ?? []} columnDefs={movementColumns} height={320} />
          </QueryState>
        </Stack>
      )}
      {dialog === 'movement' && (
        <RecordMovementModal
          entityId={entityId}
          registryAccountId={selected?.registryAccountId}
          productCode={selected?.productCode}
          onClose={() => setDialog(null)}
        />
      )}
      {dialog === 'transfer' && (
        <AllowanceTransferModal
          entityId={entityId}
          fromAccountId={selected?.registryAccountId}
          productCode={selected?.productCode}
          onClose={() => setDialog(null)}
        />
      )}
    </Stack>
  );
}

export function InventoryPage() {
  const { t } = useTranslation();
  const { entity } = useEntity();
  const [params, setParams] = useSearchParams();
  const tab = oneOf(params.get('tab'), ['lots', 'search', 'allowances'] as const, 'lots');
  const [receiving, setReceiving] = useState(false);
  return (
    <Stack gap="lg">
      <PageHeader
        title={t('inventory.title')}
        description={t('inventory.description', { entity: entity?.code ?? '' })}
        actions={
          entity && (
            <Group gap="xs">
              <Can op="inventory_verifyJournal">
                <JournalCheckButton />
              </Can>
              <Can op="inventory_receive">
                <Button
                  leftSection={<IconPlus size={16} />}
                  onClick={() => setReceiving(true)}
                  data-testid="receive-lots"
                >
                  {t('inventory.receive.title')}
                </Button>
              </Can>
            </Group>
          )
        }
      />
      {!entity ? (
        <Alert color="gray">{t('header.noEntity')}</Alert>
      ) : (
        <>
          <InventoryKpis entityId={entity.id} />
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
        </>
      )}
      {receiving && entity && <ReceiveLotsModal entityId={entity.id} onClose={() => setReceiving(false)} />}
    </Stack>
  );
}
