import { Alert, Anchor, Button, Group, Paper, SegmentedControl, Select, SimpleGrid, Stack, Text } from '@mantine/core';
import { IconFileUpload } from '@tabler/icons-react';
import type { ColDef, ICellRendererParams } from 'ag-grid-community';
import { useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link, useNavigate, useSearchParams } from 'react-router';
import { $api } from '@/shared/api/client';
import { Can } from '@/shared/auth/Can';
import { EmptyState } from '@/shared/components/EmptyState';
import { KpiCard } from '@/shared/components/KpiCard';
import { PageHeader } from '@/shared/components/PageHeader';
import { QueryState } from '@/shared/components/QueryState';
import { TableSkeleton } from '@/shared/components/Skeletons';
import { StatusBadge } from '@/shared/components/StatusBadge';
import { useEntity } from '@/shared/context/EntityContext';
import { formatDateTime } from '@/shared/format/format';
import { DataGrid } from '@/shared/grid/DataGrid';
import { useCounterparties } from '@/features/trades/hooks';
import { LimitNotice } from '@/shared/components/LimitNotice';
import { UploadConfirmationsModal } from './ConfirmationModals';
import {
  QUEUE_VIEWS,
  asQueueView,
  differenceFields,
  queueStatuses,
  type Confirmation,
  type ConfirmationStatus,
} from './model/confirmations';

export function ConfirmationStatusBadge({ status, testId }: { status: ConfirmationStatus; testId?: string }) {
  return <StatusBadge status={status} data-testid={testId} />;
}

const ALL = queueStatuses('all');
/** The queue is read in one call (the API's default size); a full answer is probably cut off. */
const RECEIVED_LIMIT = 200;

/** Confirmations received from counterparties, to match, override or dispute. */
export function ConfirmationsPage() {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const { entity } = useEntity();
  const counterparties = useCounterparties();
  const [params, setParams] = useSearchParams();
  const view = asQueueView(params.get('view'));
  const counterpartyId = params.get('counterparty');
  const [uploading, setUploading] = useState(false);
  const set = (key: string, value: string | null) => {
    const next = new URLSearchParams(params);
    if (value) next.set(key, value);
    else next.delete(key);
    setParams(next, { replace: true });
  };
  // One call for every received confirmation: the views and the figures are counts and filters of it.
  const received = $api.useQuery(
    'get',
    '/api/v1/confirmations',
    {
      params: {
        query: {
          legalEntityId: entity?.id,
          status: ALL,
          counterpartyId: counterpartyId ?? undefined,
          limit: RECEIVED_LIMIT,
        },
      },
    },
    { enabled: Boolean(entity) },
  );
  const all = useMemo(() => received.data ?? [], [received.data]);
  const rows = useMemo(() => all.filter((c) => queueStatuses(view).includes(c.status)), [all, view]);
  const count = (...statuses: ConfirmationStatus[]) => all.filter((c) => statuses.includes(c.status)).length;
  const columns = useMemo<ColDef<Confirmation>[]>(
    () => [
      {
        field: 'tradeNumber',
        headerName: t('confirmations.trade'),
        width: 150,
        cellRenderer: (p: ICellRendererParams<Confirmation>) =>
          p.data && (
            <Anchor
              component={Link}
              to={`/trades/${p.data.tradeId}`}
              size="sm"
              fw={600}
              onClick={(e) => e.stopPropagation()}
            >
              {p.data.tradeNumber}
            </Anchor>
          ),
      },
      {
        field: 'status',
        headerName: t('allocation.status'),
        width: 150,
        cellRenderer: (p: ICellRendererParams<Confirmation>) =>
          p.data && <ConfirmationStatusBadge status={p.data.status} />,
      },
      {
        colId: 'counterparty',
        headerName: t('trades.columns.counterparty'),
        width: 240,
        valueGetter: (p) =>
          p.data?.counterpartyId ? (counterparties.byId.get(p.data.counterpartyId)?.name ?? '') : '',
      },
      { field: 'reference', headerName: t('confirmations.theirReference'), width: 170 },
      {
        colId: 'differences',
        headerName: t('confirmations.differences'),
        flex: 1,
        minWidth: 200,
        valueGetter: (p) =>
          p.data
            ? differenceFields(p.data.differences)
                .map((field) => t(`confirmations.fields.${field}`, { defaultValue: field }))
                .join(', ')
            : '',
      },
      {
        field: 'source',
        headerName: t('confirmations.source'),
        width: 140,
        valueFormatter: (p) => t(`trades.sources.${String(p.value)}`, { defaultValue: String(p.value) }),
      },
      {
        field: 'createdAt',
        headerName: t('confirmations.receivedAt'),
        width: 160,
        valueFormatter: (p) => formatDateTime(p.value as string),
      },
      { field: 'createdBy', headerName: t('confirmations.receivedBy'), width: 130 },
    ],
    [t, counterparties.byId],
  );
  const mismatched = count('MISMATCHED');
  return (
    <Stack gap="lg">
      <PageHeader
        title={t('confirmations.title')}
        description={t('confirmations.description', { entity: entity?.code ?? '' })}
        actions={
          <Can op="confirmation_receiveCsv">
            <Button leftSection={<IconFileUpload size={16} />} onClick={() => setUploading(true)}>
              {t('confirmations.upload')}
            </Button>
          </Can>
        }
      />
      {!entity ? (
        <Alert color="gray">{t('header.noEntity')}</Alert>
      ) : (
        <>
          <LimitNotice shown={all.length} limit={RECEIVED_LIMIT} />
          <SimpleGrid cols={{ base: 1, xs: 2, lg: 4 }} spacing="md">
            <KpiCard
              label={t('confirmations.kpi.mismatched')}
              value={mismatched}
              loading={received.isPending}
              trend={mismatched > 0 ? t('confirmations.kpi.mismatchedHint') : t('confirmations.kpi.noneWaiting')}
              trendTone={mismatched > 0 ? 'warning' : 'neutral'}
              testId="kpi-mismatched"
            />
            <KpiCard
              label={t('confirmations.kpi.disputed')}
              value={count('DISPUTED')}
              loading={received.isPending}
              trend={t('confirmations.kpi.disputedHint')}
              trendTone={count('DISPUTED') > 0 ? 'down' : 'neutral'}
            />
            <KpiCard
              label={t('confirmations.kpi.received')}
              value={count('RECEIVED')}
              loading={received.isPending}
              trend={t('confirmations.kpi.receivedHint')}
            />
            <KpiCard
              label={t('confirmations.kpi.settled')}
              value={count('MATCHED', 'OVERRIDDEN')}
              loading={received.isPending}
              trend={t('confirmations.kpi.settledHint')}
              trendTone="up"
            />
          </SimpleGrid>
          <Stack gap="sm">
            <Group align="flex-end" gap="sm">
              <SegmentedControl
                value={view}
                onChange={(value) => set('view', value === 'open' ? null : value)}
                data={QUEUE_VIEWS.map((value) => ({ value, label: t(`confirmations.views.${value}`) }))}
              />
              <Select
                aria-label={t('trades.columns.counterparty')}
                placeholder={t('settlement.allCounterparties')}
                data={counterparties.list.map((c) => ({ value: c.id, label: `${c.code} · ${c.name}` }))}
                value={counterpartyId}
                onChange={(value) => set('counterparty', value)}
                searchable
                clearable
                w={300}
              />
            </Group>
            <QueryState isPending={false} error={received.error}>
              {received.isPending ? (
                <Paper withBorder radius="lg">
                  <TableSkeleton rows={6} />
                </Paper>
              ) : rows.length === 0 ? (
                <Paper withBorder radius="lg">
                  <EmptyState
                    title={view === 'open' ? t('confirmations.emptyOpen') : t('confirmations.empty')}
                    description={t('confirmations.emptyHint')}
                    testId="confirmations-empty"
                  />
                </Paper>
              ) : (
                <DataGrid<Confirmation>
                  rowData={rows}
                  columnDefs={columns}
                  rowId={(row) => row.id}
                  height={Math.min(520, 46 + rows.length * 40 + 2)}
                  onRowClicked={(event) => event.data && void navigate(`/confirmations/${event.data.id}`)}
                  rowStyle={{ cursor: 'pointer' }}
                />
              )}
            </QueryState>
            <Text size="xs" c="dimmed">
              {t('confirmations.hint')}
            </Text>
          </Stack>
        </>
      )}
      {uploading && <UploadConfirmationsModal onClose={() => setUploading(false)} />}
    </Stack>
  );
}
