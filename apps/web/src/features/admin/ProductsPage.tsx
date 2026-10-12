import {
  Button,
  Group,
  Modal,
  NumberInput,
  Paper,
  Select,
  Stack,
  Switch,
  Table,
  Tabs,
  Text,
  TextInput,
} from '@mantine/core';
import { IconPlus } from '@tabler/icons-react';
import type { ColDef, ICellRendererParams } from 'ag-grid-community';
import { useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useSearchParams } from 'react-router';
import { $api } from '@/shared/api/client';
import { decimal, type Body, type Schema } from '@/shared/api/types';
import { Can } from '@/shared/auth/Can';
import { notifySuccess } from '@/shared/components/notify';
import { PageHeader } from '@/shared/components/PageHeader';
import { ProblemAlert } from '@/shared/components/ProblemAlert';
import { QueryState } from '@/shared/components/QueryState';
import { StatusBadge } from '@/shared/components/StatusBadge';
import { formatDate, formatDecimal } from '@/shared/format/format';
import { DataGrid } from '@/shared/grid/DataGrid';
import { CriteriaBuilder } from '@/features/criteria/CriteriaBuilder';
import type { Criterion } from '@/features/criteria/model/tree';
import { containment, containmentText } from './model/containment';
import { useInvalidateReference } from './PartyDetails';

type Bucket = Schema<'ProductBucketView'>;
type Carrier = NonNullable<Body<'CreateProductBucket'>['energyCarrier']>;
type Futures = Schema<'FuturesContractView'>;
const CARRIERS: Carrier[] = ['ELECTRICITY', 'GAS', 'HYDROGEN', 'HEAT_COOLING'];

function Active({ active }: { active: boolean }) {
  const { t } = useTranslation();
  return (
    <StatusBadge status={active ? 'ACTIVE' : 'INACTIVE'} label={active ? t('admin.active') : t('admin.inactive')} />
  );
}

function BucketModal({ bucket, onClose }: { bucket?: Bucket; onClose: () => void }) {
  const { t } = useTranslation();
  const invalidate = useInvalidateReference();
  const [code, setCode] = useState('');
  const [name, setName] = useState(bucket?.name ?? '');
  const [carrier, setCarrier] = useState<Carrier>(bucket?.energyCarrier ?? 'ELECTRICITY');
  const [markCurveRef, setMarkCurveRef] = useState(bucket?.markCurveRef ?? '');
  const [sortOrder, setSortOrder] = useState<number>(bucket?.sortOrder ?? 100);
  const [active, setActive] = useState(bucket?.active ?? true);
  const [criterion, setCriterion] = useState<Criterion>(bucket?.criterion ?? { type: 'all', of: [] });
  const done = () => {
    notifySuccess(t('admin.saved'));
    void invalidate();
    onClose();
  };
  const create = $api.useMutation('post', '/api/v1/products/buckets', { meta: { notify: false }, onSuccess: done });
  const update = $api.useMutation('patch', '/api/v1/products/buckets/{code}', {
    meta: { notify: false },
    onSuccess: done,
  });
  return (
    <Modal
      opened
      onClose={onClose}
      size="xl"
      title={bucket ? t('admin.editBucket', { code: bucket.code }) : t('admin.newBucket')}
    >
      <Stack>
        <ProblemAlert error={create.error ?? update.error} />
        <Group grow>
          {!bucket && (
            <TextInput
              label={t('marketData.code')}
              value={code}
              onChange={(e) => setCode(e.currentTarget.value.toUpperCase())}
            />
          )}
          <TextInput label={t('ets.name')} value={name} onChange={(e) => setName(e.currentTarget.value)} />
        </Group>
        <Group grow>
          {!bucket && (
            <Select
              label={t('admin.carrier')}
              data={CARRIERS}
              value={carrier}
              allowDeselect={false}
              onChange={(value) => value && setCarrier(value)}
            />
          )}
          <TextInput
            label={t('admin.markCurve')}
            value={markCurveRef}
            onChange={(e) => setMarkCurveRef(e.currentTarget.value)}
          />
          <NumberInput
            label={t('admin.sortOrder')}
            value={sortOrder}
            onChange={(v) => setSortOrder(Number(v))}
            allowDecimal={false}
          />
        </Group>
        {bucket ? (
          <>
            <Switch
              label={t('marketData.active')}
              checked={active}
              onChange={(e) => setActive(e.currentTarget.checked)}
            />
            <Text size="sm" c="dimmed">
              {t('admin.bucketCriterionFixed', { summary: bucket.summary })}
            </Text>
          </>
        ) : (
          <CriteriaBuilder value={criterion} onChange={setCriterion} />
        )}
        <Button
          disabled={!name.trim() || (!bucket && !code.trim())}
          loading={create.isPending || update.isPending}
          onClick={() =>
            bucket
              ? update.mutate({
                  params: { path: { code: bucket.code } },
                  body: {
                    name,
                    markCurveRef: markCurveRef || undefined,
                    sortOrder,
                    active,
                    expectedVersion: bucket.version,
                  },
                })
              : create.mutate({
                  body: {
                    code,
                    name,
                    energyCarrier: carrier,
                    markCurveRef: markCurveRef || undefined,
                    sortOrder,
                    criterion,
                  },
                })
          }
        >
          {t('ets.save')}
        </Button>
      </Stack>
    </Modal>
  );
}

function BucketsTab() {
  const { t } = useTranslation();
  const [all, setAll] = useState(false);
  const [editing, setEditing] = useState<{ bucket?: Bucket } | null>(null);
  const buckets = $api.useQuery('get', '/api/v1/products/buckets', { params: { query: { activeOnly: !all } } });
  const hierarchy = $api.useQuery('get', '/api/v1/products/buckets/hierarchy');
  const hierarchyNodes = hierarchy.data?.nodes;
  const columns = useMemo<ColDef<Bucket>[]>(
    () => [
      { field: 'sortOrder', headerName: '#', width: 80, sort: 'asc' },
      { field: 'code', headerName: t('marketData.code'), width: 200 },
      { field: 'name', headerName: t('ets.name'), width: 220 },
      { field: 'summary', headerName: t('admin.criterion'), flex: 1, minWidth: 260, tooltipField: 'summary' },
      {
        field: 'specificity',
        headerName: t('admin.specificity'),
        width: 120,
        type: 'rightAligned',
        valueFormatter: (p) => formatDecimal(p.value as number, 0, 3),
      },
      {
        colId: 'containedIn',
        headerName: t('admin.containedIn'),
        width: 260,
        valueGetter: (p) =>
          p.data && hierarchyNodes
            ? containmentText(containment(p.data.code, hierarchyNodes), {
                topLevel: t('admin.containedInRoot'),
                sameAs: (codes) => t('admin.sameAs', { name: codes }),
              })
            : '',
        tooltipValueGetter: (p) => String(p.value ?? ''),
      },
      { field: 'markCurveRef', headerName: t('admin.markCurve'), width: 170 },
      {
        field: 'active',
        headerName: t('admin.active'),
        width: 110,
        cellRenderer: (p: ICellRendererParams<Bucket>) => p.data && <Active active={p.data.active} />,
      },
    ],
    [t, hierarchyNodes],
  );
  return (
    <Stack>
      <Group justify="space-between">
        <Switch label={t('admin.showInactive')} checked={all} onChange={(e) => setAll(e.currentTarget.checked)} />
        <Can op="product_createBucket">
          <Button leftSection={<IconPlus size={16} />} onClick={() => setEditing({})}>
            {t('admin.newBucket')}
          </Button>
        </Can>
      </Group>
      <QueryState isPending={buckets.isPending} error={buckets.error}>
        <DataGrid<Bucket>
          rowData={buckets.data ?? []}
          columnDefs={columns}
          rowId={(row) => row.id}
          fitRows={12}
          onRowClicked={(event) => {
            if (event.data) setEditing({ bucket: event.data });
          }}
        />
      </QueryState>
      {editing && (
        <Can op="product_updateBucket" fallback={null}>
          <BucketModal bucket={editing.bucket} onClose={() => setEditing(null)} />
        </Can>
      )}
    </Stack>
  );
}

function AllowancesTab() {
  const { t } = useTranslation();
  const products = $api.useQuery('get', '/api/v1/products/allowances', { params: { query: { activeOnly: false } } });
  return (
    <QueryState isPending={products.isPending} error={products.error}>
      <Paper withBorder>
        <Table highlightOnHover verticalSpacing="sm">
          <Table.Thead>
            <Table.Tr>
              <Table.Th>{t('marketData.code')}</Table.Th>
              <Table.Th>{t('ets.name')}</Table.Th>
              <Table.Th>{t('admin.jurisdiction')}</Table.Th>
              <Table.Th>{t('admin.phase')}</Table.Th>
              <Table.Th>{t('admin.registry')}</Table.Th>
              <Table.Th>{t('admin.unit')}</Table.Th>
              <Table.Th>{t('admin.active')}</Table.Th>
            </Table.Tr>
          </Table.Thead>
          <Table.Tbody>
            {(products.data ?? []).map((product) => (
              <Table.Tr key={product.code}>
                <Table.Td ff="monospace">{product.code}</Table.Td>
                <Table.Td>{product.name}</Table.Td>
                <Table.Td>{product.jurisdiction}</Table.Td>
                <Table.Td>{product.phase ?? '–'}</Table.Td>
                <Table.Td>{product.registryCode}</Table.Td>
                <Table.Td>{product.unit}</Table.Td>
                <Table.Td>
                  <Active active={product.active} />
                </Table.Td>
              </Table.Tr>
            ))}
          </Table.Tbody>
        </Table>
      </Paper>
    </QueryState>
  );
}

function FuturesModal({ onClose }: { onClose: () => void }) {
  const { t } = useTranslation();
  const invalidate = useInvalidateReference();
  const [values, setValues] = useState({
    code: '',
    allowanceProductCode: 'EUA',
    venueMic: 'IFED',
    deliveryMonth: '',
    deliveryDate: '',
    lastTradingDay: '',
    contractSize: '1000',
    currency: 'EUR',
  });
  const create = $api.useMutation('post', '/api/v1/products/futures-contracts', {
    meta: { notify: false },
    onSuccess: () => {
      notifySuccess(t('admin.saved'));
      void invalidate();
      onClose();
    },
  });
  const set = (patch: Partial<typeof values>) => setValues({ ...values, ...patch });
  return (
    <Modal opened onClose={onClose} title={t('admin.newFutures')}>
      <Stack>
        <ProblemAlert error={create.error} />
        <Group grow>
          <TextInput
            label={t('marketData.code')}
            value={values.code}
            onChange={(e) => set({ code: e.currentTarget.value.toUpperCase() })}
          />
          <TextInput
            label={t('trades.form.allowanceProduct')}
            value={values.allowanceProductCode}
            onChange={(e) => set({ allowanceProductCode: e.currentTarget.value.toUpperCase() })}
          />
          <TextInput
            label="MIC"
            value={values.venueMic}
            onChange={(e) => set({ venueMic: e.currentTarget.value.toUpperCase() })}
          />
        </Group>
        <Group grow>
          <TextInput
            type="month"
            label={t('admin.deliveryMonth')}
            value={values.deliveryMonth}
            onChange={(e) => set({ deliveryMonth: e.currentTarget.value })}
          />
          <TextInput
            type="date"
            label={t('trades.form.deliveryDate')}
            value={values.deliveryDate}
            onChange={(e) => set({ deliveryDate: e.currentTarget.value })}
          />
          <TextInput
            type="date"
            label={t('admin.lastTradingDay')}
            value={values.lastTradingDay}
            onChange={(e) => set({ lastTradingDay: e.currentTarget.value })}
          />
        </Group>
        <Group grow>
          <TextInput
            label={t('admin.contractSize')}
            inputMode="decimal"
            value={values.contractSize}
            onChange={(e) => set({ contractSize: e.currentTarget.value })}
          />
          <TextInput
            label={t('trades.form.currency')}
            value={values.currency}
            maxLength={3}
            onChange={(e) => set({ currency: e.currentTarget.value.toUpperCase() })}
          />
        </Group>
        <Button
          disabled={!values.code.trim() || !values.deliveryMonth || !values.deliveryDate || !values.lastTradingDay}
          loading={create.isPending}
          onClick={() => create.mutate({ body: { ...values, contractSize: decimal(values.contractSize) } })}
        >
          {t('ets.save')}
        </Button>
      </Stack>
    </Modal>
  );
}

function FuturesTab() {
  const { t } = useTranslation();
  const invalidate = useInvalidateReference();
  const [creating, setCreating] = useState(false);
  const futures = $api.useQuery('get', '/api/v1/products/futures-contracts', {
    params: { query: { activeOnly: false } },
  });
  const deactivate = $api.useMutation('post', '/api/v1/products/futures-contracts/{code}/deactivate', {
    onSuccess: () => void invalidate(),
  });
  return (
    <Stack>
      <Group justify="flex-end">
        <Can op="product_createFuturesContract">
          <Button leftSection={<IconPlus size={16} />} onClick={() => setCreating(true)}>
            {t('admin.newFutures')}
          </Button>
        </Can>
      </Group>
      <QueryState isPending={futures.isPending} error={futures.error}>
        {(futures.data ?? []).length === 0 ? (
          <Text size="sm" c="dimmed">
            {t('common.empty')}
          </Text>
        ) : (
          <Paper withBorder>
            <Table highlightOnHover verticalSpacing="sm">
              <Table.Thead>
                <Table.Tr>
                  <Table.Th>{t('marketData.code')}</Table.Th>
                  <Table.Th>{t('trades.form.allowanceProduct')}</Table.Th>
                  <Table.Th>MIC</Table.Th>
                  <Table.Th>{t('admin.deliveryMonth')}</Table.Th>
                  <Table.Th>{t('admin.lastTradingDay')}</Table.Th>
                  <Table.Th ta="right">{t('admin.contractSize')}</Table.Th>
                  <Table.Th>{t('admin.active')}</Table.Th>
                  <Table.Th />
                </Table.Tr>
              </Table.Thead>
              <Table.Tbody>
                {(futures.data ?? []).map((row: Futures) => (
                  <Table.Tr key={row.code}>
                    <Table.Td ff="monospace">{row.code}</Table.Td>
                    <Table.Td>{row.allowanceProductCode}</Table.Td>
                    <Table.Td>{row.venueMic}</Table.Td>
                    <Table.Td>{row.deliveryMonth}</Table.Td>
                    <Table.Td>{formatDate(row.lastTradingDay)}</Table.Td>
                    <Table.Td ta="right">{formatDecimal(row.contractSize)}</Table.Td>
                    <Table.Td>
                      <Active active={row.active} />
                    </Table.Td>
                    <Table.Td>
                      {row.active && (
                        <Can op="product_deactivateFuturesContract">
                          <Button
                            size="compact-xs"
                            variant="subtle"
                            color="red"
                            onClick={() => deactivate.mutate({ params: { path: { code: row.code } } })}
                          >
                            {t('admin.deactivate')}
                          </Button>
                        </Can>
                      )}
                    </Table.Td>
                  </Table.Tr>
                ))}
              </Table.Tbody>
            </Table>
          </Paper>
        )}
      </QueryState>
      {creating && <FuturesModal onClose={() => setCreating(false)} />}
    </Stack>
  );
}

const TABS = ['buckets', 'allowances', 'futures'] as const;

/** Product buckets (criteria that decide positions), allowance products and exchange futures contracts. */
export function ProductsPage() {
  const { t } = useTranslation();
  const [params, setParams] = useSearchParams();
  const requested = params.get('tab');
  const tab = TABS.find((value) => value === requested) ?? 'buckets';
  return (
    <Stack>
      <PageHeader title={t('admin.productsTitle')} description={t('admin.productsDescription')} />
      <Tabs
        value={tab}
        onChange={(value) => setParams(value && value !== 'buckets' ? { tab: value } : {}, { replace: true })}
        keepMounted={false}
      >
        <Tabs.List>
          <Tabs.Tab value="buckets">{t('admin.tabs.buckets')}</Tabs.Tab>
          <Tabs.Tab value="allowances">{t('admin.tabs.allowances')}</Tabs.Tab>
          <Tabs.Tab value="futures">{t('admin.tabs.futures')}</Tabs.Tab>
        </Tabs.List>
        <Tabs.Panel value="buckets" pt="md">
          <BucketsTab />
        </Tabs.Panel>
        <Tabs.Panel value="allowances" pt="md">
          <AllowancesTab />
        </Tabs.Panel>
        <Tabs.Panel value="futures" pt="md">
          <FuturesTab />
        </Tabs.Panel>
      </Tabs>
    </Stack>
  );
}
