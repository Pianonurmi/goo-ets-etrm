import { Alert, Anchor, Badge, Button, Group, SegmentedControl, Select, Stack, Tabs, Text } from '@mantine/core';
import { IconDownload, IconFileInvoice, IconReceipt, IconRefresh, IconTag } from '@tabler/icons-react';
import type { ColDef, ICellRendererParams } from 'ag-grid-community';
import { useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link, useNavigate, useSearchParams } from 'react-router';
import { $api } from '@/shared/api/client';
import { download } from '@/shared/api/download';
import { Can } from '@/shared/auth/Can';
import { notifySuccess } from '@/shared/components/notify';
import { PageHeader } from '@/shared/components/PageHeader';
import { QueryState } from '@/shared/components/QueryState';
import { useEntity } from '@/shared/context/EntityContext';
import { formatDecimal, formatMoney, formatQuantity } from '@/shared/format/format';
import { DataGrid } from '@/shared/grid/DataGrid';
import { useCounterparties } from '@/features/trades/hooks';
import { useInvalidateSettlement } from './hooks';
import {
  INVOICE_KINDS,
  INVOICE_STATUSES,
  INVOICE_STATUS_COLORS,
  ITEM_STATUS_COLORS,
  ITEM_VIEWS,
  PAYMENT_STATUS_COLORS,
  asItemView,
  canReevaluate,
  itemStatuses,
  needsPrice,
  paymentStatus,
  type Invoice,
  type Item,
} from './model/settlement';
import { NettingTab } from './NettingTab';
import { PaymentsTab } from './PaymentsTab';
import { SettlementReportsTab } from './SettlementReportsTab';
import { FixPriceModal, InvoiceRunModal, PurchaseInvoiceModal } from './SettlementModals';
import { VatRulesTab } from './VatRulesTab';

const TABS = ['items', 'invoices', 'payments', 'netting', 'vat-rules', 'reports'] as const;

export function StatusBadge({ label, color, testId }: { label: string; color: string; testId?: string }) {
  return (
    <Badge color={color} variant="light" data-testid={testId}>
      {label.replace(/_/g, ' ')}
    </Badge>
  );
}

function useSetFilter() {
  const [params, setParams] = useSearchParams();
  return (key: string, value: string | null) => {
    const next = new URLSearchParams(params);
    if (value) next.set(key, value);
    else next.delete(key);
    setParams(next, { replace: true });
  };
}

function ItemActions({ item, onFix }: { item: Item; onFix: (item: Item) => void }) {
  const { t } = useTranslation();
  const invalidate = useInvalidateSettlement();
  const reevaluate = $api.useMutation('post', '/api/v1/settlement/items/{id}/reevaluate', {
    onSuccess: (updated) => {
      notifySuccess(
        updated.status === 'NEEDS_ATTENTION' ? t('settlement.stillNeedsAttention') : t('settlement.itemResolved'),
      );
      void invalidate();
    },
  });
  return (
    <Group gap={4} wrap="nowrap">
      {needsPrice(item) && (
        <Can op="settlement_fixPrice">
          <Button size="compact-xs" variant="light" leftSection={<IconTag size={12} />} onClick={() => onFix(item)}>
            {t('settlement.fixPrice')}
          </Button>
        </Can>
      )}
      {canReevaluate(item) && (
        <Can op="settlement_reevaluate">
          <Button
            size="compact-xs"
            variant="subtle"
            leftSection={<IconRefresh size={12} />}
            loading={reevaluate.isPending}
            onClick={() => reevaluate.mutate({ params: { path: { id: item.id } } })}
          >
            {t('settlement.reevaluate')}
          </Button>
        </Can>
      )}
    </Group>
  );
}

function ItemsTab({ entityId }: { entityId: string }) {
  const { t } = useTranslation();
  const [params] = useSearchParams();
  const setFilter = useSetFilter();
  const view = asItemView(params.get('view'));
  const counterparties = useCounterparties();
  const [fixing, setFixing] = useState<Item | null>(null);
  const items = $api.useQuery('get', '/api/v1/settlement/items', {
    params: { query: { legalEntityId: entityId, status: itemStatuses(view) } },
  });
  const columns = useMemo<ColDef<Item>[]>(
    () => [
      {
        field: 'tradeNumber',
        headerName: t('settlement.trade'),
        width: 150,
        cellRenderer: (p: ICellRendererParams<Item>) =>
          p.data && (
            <Anchor component={Link} to={`/trades/${p.data.tradeId}`} size="sm">
              {p.data.tradeNumber}
            </Anchor>
          ),
      },
      {
        field: 'status',
        headerName: t('allocation.status'),
        width: 170,
        cellRenderer: (p: ICellRendererParams<Item>) =>
          p.data && <StatusBadge label={p.data.status} color={ITEM_STATUS_COLORS[p.data.status]} />,
      },
      {
        colId: 'counterparty',
        headerName: t('trades.columns.counterparty'),
        width: 210,
        valueGetter: (p) => (p.data ? (counterparties.byId.get(p.data.counterpartyId)?.name ?? '') : ''),
      },
      {
        field: 'problem',
        headerName: t('settlement.problem'),
        flex: 1,
        minWidth: 240,
        tooltipField: 'problem',
        valueFormatter: (p) => (p.value as string | null) ?? '',
      },
      {
        field: 'netAmount',
        headerName: t('settlement.netAmount'),
        width: 140,
        type: 'rightAligned',
        valueFormatter: (p) => (p.data ? formatMoney(p.data.netAmount, p.data.currency) : ''),
      },
      { field: 'deliveredOn', headerName: t('settlement.deliveredOn'), width: 120 },
      {
        field: 'direction',
        headerName: t('settlement.direction'),
        width: 120,
        valueFormatter: (p) => t(`settlement.directions.${String(p.value)}`),
      },
      {
        field: 'quantity',
        headerName: t('settlement.quantity'),
        width: 120,
        type: 'rightAligned',
        valueFormatter: (p) => (p.data ? formatQuantity(p.data.quantity, p.data.unit) : ''),
      },
      {
        field: 'unitPrice',
        headerName: t('settlement.price'),
        width: 110,
        type: 'rightAligned',
        valueFormatter: (p) => formatDecimal(p.value as number | null, 2, 6),
      },
      {
        field: 'vatTreatment',
        headerName: t('settlement.vatTreatment'),
        width: 210,
        valueFormatter: (p) => (p.value ? t(`settlement.treatments.${String(p.value)}`) : ''),
      },
      {
        field: 'vatAmount',
        headerName: t('settlement.vatAmount'),
        width: 120,
        type: 'rightAligned',
        valueFormatter: (p) => (p.data ? formatMoney(p.data.vatAmount, p.data.currency) : ''),
      },
      {
        colId: 'actions',
        headerName: '',
        width: 200,
        pinned: 'right',
        sortable: false,
        filter: false,
        cellRenderer: (p: ICellRendererParams<Item>) => p.data && <ItemActions item={p.data} onFix={setFixing} />,
      },
    ],
    [t, counterparties.byId, setFixing],
  );
  return (
    <Stack>
      <SegmentedControl
        w="fit-content"
        value={view}
        onChange={(value) => setFilter('view', value === 'attention' ? null : value)}
        data={ITEM_VIEWS.map((value) => ({ value, label: t(`settlement.itemViews.${value}`) }))}
        data-testid="item-view"
      />
      <QueryState isPending={items.isPending} error={items.error}>
        <DataGrid<Item> rowData={items.data ?? []} columnDefs={columns} rowId={(row) => row.id} height={480} />
      </QueryState>
      {fixing && <FixPriceModal item={fixing} onClose={() => setFixing(null)} />}
    </Stack>
  );
}

function InvoicesTab({ entityId }: { entityId: string }) {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const [params] = useSearchParams();
  const setFilter = useSetFilter();
  const counterparties = useCounterparties();
  const status = INVOICE_STATUSES.find((value) => value === params.get('status')) ?? null;
  const kind = INVOICE_KINDS.find((value) => value === params.get('kind')) ?? null;
  const counterpartyId = params.get('counterparty');
  const invoices = $api.useQuery('get', '/api/v1/settlement/invoices', {
    params: {
      query: {
        legalEntityId: entityId,
        status: status ? [status] : undefined,
        kind: kind ? [kind] : undefined,
        counterpartyId: counterpartyId ?? undefined,
      },
    },
  });
  const columns = useMemo<ColDef<Invoice>[]>(
    () => [
      {
        field: 'number',
        headerName: t('settlement.number'),
        width: 170,
        valueFormatter: (p) => (p.value as string | null) ?? t('settlement.draft'),
      },
      {
        field: 'status',
        headerName: t('allocation.status'),
        width: 170,
        cellRenderer: (p: ICellRendererParams<Invoice>) =>
          p.data && <StatusBadge label={p.data.status} color={INVOICE_STATUS_COLORS[p.data.status]} />,
      },
      {
        field: 'paymentStatus',
        headerName: t('settlement.payment'),
        width: 150,
        cellRenderer: (p: ICellRendererParams<Invoice>) =>
          p.data?.status === 'ISSUED' && (
            <StatusBadge label={paymentStatus(p.data)} color={PAYMENT_STATUS_COLORS[paymentStatus(p.data)]} />
          ),
      },
      {
        colId: 'counterparty',
        headerName: t('trades.columns.counterparty'),
        flex: 1,
        minWidth: 220,
        valueGetter: (p) => (p.data ? (counterparties.byId.get(p.data.counterpartyId)?.name ?? '') : ''),
      },
      {
        field: 'grossTotal',
        headerName: t('settlement.grossAmount'),
        width: 140,
        type: 'rightAligned',
        valueFormatter: (p) => (p.data ? formatMoney(p.data.grossTotal, p.data.currency) : ''),
      },
      { field: 'dueDate', headerName: t('settlement.dueDate'), width: 120 },
      {
        field: 'kind',
        headerName: t('settlement.kind'),
        width: 170,
        valueFormatter: (p) => t(`settlement.kinds.${String(p.value)}`),
      },
      { field: 'invoiceDate', headerName: t('settlement.invoiceDate'), width: 130 },
      { field: 'counterpartyInvoiceRef', headerName: t('settlement.supplierRef'), width: 170 },
      {
        field: 'netTotal',
        headerName: t('settlement.netAmount'),
        width: 140,
        type: 'rightAligned',
        valueFormatter: (p) => (p.data ? formatMoney(p.data.netTotal, p.data.currency) : ''),
      },
    ],
    [t, counterparties.byId],
  );
  return (
    <Stack>
      <Group align="flex-end">
        <Select
          label={t('allocation.status')}
          placeholder={t('settlement.anyStatus')}
          data={INVOICE_STATUSES.map((value) => ({ value, label: t(`settlement.invoiceStatuses.${value}`) }))}
          value={status}
          onChange={(value) => setFilter('status', value)}
          clearable
          w={200}
          data-testid="invoice-status-filter"
        />
        <Select
          label={t('settlement.kind')}
          placeholder={t('settlement.anyKind')}
          data={INVOICE_KINDS.map((value) => ({ value, label: t(`settlement.kinds.${value}`) }))}
          value={kind}
          onChange={(value) => setFilter('kind', value)}
          clearable
          w={220}
        />
        <Select
          label={t('trades.columns.counterparty')}
          placeholder={t('settlement.allCounterparties')}
          data={counterparties.list.map((c) => ({ value: c.id, label: `${c.code} · ${c.name}` }))}
          value={counterpartyId}
          onChange={(value) => setFilter('counterparty', value)}
          searchable
          clearable
          w={280}
        />
        <Can op="settlement_export">
          <Button
            variant="subtle"
            leftSection={<IconDownload size={16} />}
            onClick={() =>
              void download(`/api/v1/settlement/invoices/export?legalEntityId=${entityId}&format=csv`, 'invoices.csv')
            }
          >
            {t('settlement.exportIssued')}
          </Button>
        </Can>
      </Group>
      <QueryState isPending={invoices.isPending} error={invoices.error}>
        <DataGrid<Invoice>
          rowData={invoices.data ?? []}
          columnDefs={columns}
          rowId={(row) => row.id}
          height={480}
          onRowClicked={(event) => event.data && void navigate(`/settlement/invoices/${event.data.id}`)}
          rowStyle={{ cursor: 'pointer' }}
        />
      </QueryState>
    </Stack>
  );
}

/** Settlement items from deliveries, and the invoices drafted, approved and issued from them. */
export function SettlementPage() {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const { entity } = useEntity();
  const [params, setParams] = useSearchParams();
  const tab = TABS.find((value) => value === params.get('tab')) ?? 'items';
  const [running, setRunning] = useState(false);
  const [registering, setRegistering] = useState(false);
  return (
    <Stack>
      <PageHeader
        title={t('settlement.title')}
        description={t('settlement.description', { entity: entity?.code ?? '' })}
        actions={
          entity && (
            <>
              <Can op="settlement_registerPurchaseInvoice">
                <Button
                  variant="light"
                  leftSection={<IconReceipt size={16} />}
                  onClick={() => setRegistering(true)}
                  data-testid="register-purchase"
                >
                  {t('settlement.registerPurchase')}
                </Button>
              </Can>
              <Can op="settlement_run">
                <Button
                  leftSection={<IconFileInvoice size={16} />}
                  onClick={() => setRunning(true)}
                  data-testid="run-invoices"
                >
                  {t('settlement.runInvoices')}
                </Button>
              </Can>
            </>
          )
        }
      />
      {!entity ? (
        <Alert color="gray">{t('header.noEntity')}</Alert>
      ) : (
        <>
          <Tabs
            value={tab}
            onChange={(value) => setParams(value && value !== 'items' ? { tab: value } : {}, { replace: true })}
            keepMounted={false}
          >
            <Tabs.List>
              <Tabs.Tab value="items">{t('settlement.tabs.items')}</Tabs.Tab>
              <Tabs.Tab value="invoices">{t('settlement.tabs.invoices')}</Tabs.Tab>
              <Tabs.Tab value="payments">{t('settlement.tabs.payments')}</Tabs.Tab>
              <Tabs.Tab value="netting">{t('settlement.tabs.netting')}</Tabs.Tab>
              <Tabs.Tab value="vat-rules">{t('settlement.tabs.vatRules')}</Tabs.Tab>
              <Tabs.Tab value="reports">{t('settlement.tabs.reports')}</Tabs.Tab>
            </Tabs.List>
            <Tabs.Panel value="items" pt="md">
              <ItemsTab entityId={entity.id} />
            </Tabs.Panel>
            <Tabs.Panel value="invoices" pt="md">
              <InvoicesTab entityId={entity.id} />
            </Tabs.Panel>
            <Tabs.Panel value="payments" pt="md">
              <PaymentsTab entityId={entity.id} />
            </Tabs.Panel>
            <Tabs.Panel value="netting" pt="md">
              <NettingTab entityId={entity.id} />
            </Tabs.Panel>
            <Tabs.Panel value="vat-rules" pt="md">
              <VatRulesTab />
            </Tabs.Panel>
            <Tabs.Panel value="reports" pt="md">
              <SettlementReportsTab entityId={entity.id} />
            </Tabs.Panel>
          </Tabs>
          {running && <InvoiceRunModal entityId={entity.id} onClose={() => setRunning(false)} />}
          {registering && (
            <PurchaseInvoiceModal
              entityId={entity.id}
              onClose={() => setRegistering(false)}
              onRegistered={(invoice) => void navigate(`/settlement/invoices/${invoice.id}`)}
            />
          )}
          <Text size="xs" c="dimmed">
            {t('settlement.amountsHint')}
          </Text>
        </>
      )}
    </Stack>
  );
}
