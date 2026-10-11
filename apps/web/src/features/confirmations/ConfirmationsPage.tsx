import { Alert, Anchor, Badge, Button, Group, SegmentedControl, Select, Stack, Text } from '@mantine/core';
import { IconFileUpload } from '@tabler/icons-react';
import type { ColDef, ICellRendererParams } from 'ag-grid-community';
import { useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link, useNavigate, useSearchParams } from 'react-router';
import { $api } from '@/shared/api/client';
import { Can } from '@/shared/auth/Can';
import { PageHeader } from '@/shared/components/PageHeader';
import { QueryState } from '@/shared/components/QueryState';
import { useEntity } from '@/shared/context/EntityContext';
import { formatDateTime } from '@/shared/format/format';
import { DataGrid } from '@/shared/grid/DataGrid';
import { useCounterparties } from '@/features/trades/hooks';
import { UploadConfirmationsModal } from './ConfirmationModals';
import {
  QUEUE_VIEWS,
  STATUS_COLORS,
  asQueueView,
  queueStatuses,
  type Confirmation,
  type ConfirmationStatus,
} from './model/confirmations';

export function ConfirmationStatusBadge({ status, testId }: { status: ConfirmationStatus; testId?: string }) {
  return (
    <Badge color={STATUS_COLORS[status]} variant="light" data-testid={testId}>
      {status}
    </Badge>
  );
}

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
  const queue = $api.useQuery(
    'get',
    '/api/v1/confirmations',
    {
      params: {
        query: {
          legalEntityId: entity?.id,
          status: queueStatuses(view),
          counterpartyId: counterpartyId ?? undefined,
        },
      },
    },
    { enabled: Boolean(entity) },
  );
  const columns = useMemo<ColDef<Confirmation>[]>(
    () => [
      {
        field: 'tradeNumber',
        headerName: t('confirmations.trade'),
        width: 150,
        cellRenderer: (p: ICellRendererParams<Confirmation>) =>
          p.data && (
            <Anchor component={Link} to={`/trades/${p.data.tradeId}`} size="sm" onClick={(e) => e.stopPropagation()}>
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
        width: 220,
        valueGetter: (p) =>
          p.data?.counterpartyId ? (counterparties.byId.get(p.data.counterpartyId)?.name ?? '') : '',
      },
      { field: 'reference', headerName: t('confirmations.theirReference'), width: 180 },
      {
        colId: 'differences',
        headerName: t('confirmations.differences'),
        flex: 1,
        minWidth: 220,
        valueGetter: (p) =>
          p.data?.differences
            .filter((difference) => difference.severity !== 'NOTE')
            .map((difference) => difference.field)
            .join(', ') ?? '',
      },
      { field: 'source', headerName: t('confirmations.source'), width: 100 },
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
  return (
    <Stack>
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
          <Group align="flex-end">
            <SegmentedControl
              value={view}
              onChange={(value) => set('view', value === 'open' ? null : value)}
              data={QUEUE_VIEWS.map((value) => ({ value, label: t(`confirmations.views.${value}`) }))}
            />
            <Select
              label={t('trades.columns.counterparty')}
              placeholder={t('settlement.allCounterparties')}
              data={counterparties.list.map((c) => ({ value: c.id, label: `${c.code} · ${c.name}` }))}
              value={counterpartyId}
              onChange={(value) => set('counterparty', value)}
              searchable
              clearable
              w={280}
            />
          </Group>
          <QueryState isPending={queue.isPending} error={queue.error}>
            <DataGrid<Confirmation>
              rowData={queue.data ?? []}
              columnDefs={columns}
              rowId={(row) => row.id}
              height={480}
              onRowClicked={(event) => event.data && void navigate(`/confirmations/${event.data.id}`)}
              rowStyle={{ cursor: 'pointer' }}
            />
          </QueryState>
          <Text size="xs" c="dimmed">
            {t('confirmations.hint')}
          </Text>
        </>
      )}
      {uploading && <UploadConfirmationsModal onClose={() => setUploading(false)} />}
    </Stack>
  );
}
