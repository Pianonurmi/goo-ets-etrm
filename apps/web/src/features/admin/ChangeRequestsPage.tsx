import { useUrlState } from '@/shared/useUrlState';
import { Button, Drawer, Group, Paper, SegmentedControl, Stack, Table, Text, TextInput, Title } from '@mantine/core';
import { IconInbox, IconShieldCheck } from '@tabler/icons-react';
import type { ColDef, ICellRendererParams } from 'ag-grid-community';
import { useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link } from 'react-router';
import { $api } from '@/shared/api/client';
import type { Schema } from '@/shared/api/types';
import { useSession } from '@/shared/auth/SessionContext';
import { FactGrid } from '@/shared/components/DetailLayout';
import { EmptyState } from '@/shared/components/EmptyState';
import { notifySuccess } from '@/shared/components/notify';
import { PageHeader } from '@/shared/components/PageHeader';
import { ProblemAlert } from '@/shared/components/ProblemAlert';
import { QueryState } from '@/shared/components/QueryState';
import { TableSkeleton } from '@/shared/components/Skeletons';
import { StatusBadge, statusLabel } from '@/shared/components/StatusBadge';
import { formatDateTime } from '@/shared/format/format';
import { DataGrid } from '@/shared/grid/DataGrid';
import { eventLabel } from '@/features/home/model/home';
import { useInvalidateReference } from './PartyDetails';

type ChangeRequest = Schema<'ChangeRequestView'>;
type Status = ChangeRequest['status'];
const FILTERS = ['PENDING', 'APPLIED', 'REJECTED', 'CANCELLED', 'ALL'] as const;

function RequestStatus({ status }: { status: Status }) {
  const { t } = useTranslation();
  return <StatusBadge status={status} label={t(`admin.requestStatus.${status}`)} />;
}

function RequestDrawer({ request, onClose }: { request: ChangeRequest; onClose: () => void }) {
  const { t } = useTranslation();
  const { me } = useSession();
  const invalidate = useInvalidateReference();
  const [note, setNote] = useState('');
  const task = $api.useQuery(
    'get',
    '/api/v1/workflow/tasks',
    { params: { query: { subjectRef: request.id, type: ['REFERENCE_DATA_CHANGE'] } } },
    { enabled: request.status === 'PENDING' },
  );
  const cancel = $api.useMutation('post', '/api/v1/reference/change-requests/{id}/cancel', {
    meta: { notify: false },
    onSuccess: () => {
      notifySuccess(t('admin.cancelled'));
      void invalidate();
      onClose();
    },
  });
  const mayCancel = request.status === 'PENDING' && (request.requestedBy === me.userId || me.roles.includes('ADMIN'));
  const approval = task.data?.items[0];
  return (
    <Drawer opened onClose={onClose} position="right" size="lg" title={<Text fw={600}>{request.summary}</Text>}>
      <Stack gap="lg">
        <Group gap="xs">
          <RequestStatus status={request.status} />
          <Text size="sm" c="dimmed">
            {statusLabel(request.entityType)} · {request.entityKey}
          </Text>
        </Group>
        <Paper withBorder p="md">
          <FactGrid
            cols={2}
            facts={[
              { label: t('admin.requestedBy'), value: request.requestedBy },
              { label: t('admin.requestedAt'), value: formatDateTime(request.requestedAt) },
              { label: t('admin.decidedBy'), value: request.decidedBy ?? '—' },
              { label: t('admin.decidedAt'), value: formatDateTime(request.decidedAt) || '—' },
            ]}
          />
        </Paper>
        <Stack gap={4}>
          <Title order={4} size="h6">
            {t('admin.why')}
          </Title>
          <Text size="sm">{request.reason}</Text>
          {request.decisionNote && (
            <Text size="sm" c="dimmed">
              {t('admin.decisionNote', { note: request.decisionNote })}
            </Text>
          )}
        </Stack>
        <Stack gap="xs">
          <Title order={4} size="h6">
            {t('admin.requestedChange')}
          </Title>
          <Table withTableBorder withRowBorders fz="sm" verticalSpacing={6}>
            <Table.Tbody>
              {Object.entries(request.change).map(([key, value]) => (
                <Table.Tr key={key}>
                  <Table.Td w="40%" c="dimmed">
                    {eventLabel(key)}
                  </Table.Td>
                  <Table.Td ff={typeof value === 'string' ? undefined : 'monospace'}>
                    {value === null || value === undefined
                      ? '—'
                      : typeof value === 'string'
                        ? value
                        : JSON.stringify(value)}
                  </Table.Td>
                </Table.Tr>
              ))}
            </Table.Tbody>
          </Table>
        </Stack>
        {approval && (
          <Button
            component={Link}
            to={`/inbox/${approval.id}`}
            variant="light"
            leftSection={<IconInbox size={16} />}
            w="fit-content"
          >
            {t('admin.openApproval')}
          </Button>
        )}
        {mayCancel && (
          <Paper withBorder p="md">
            <Stack gap="xs">
              <Title order={4} size="h6">
                {t('admin.cancelRequest')}
              </Title>
              <ProblemAlert error={cancel.error} />
              <TextInput label={t('admin.cancelNote')} value={note} onChange={(e) => setNote(e.currentTarget.value)} />
              <Button
                color="red"
                variant="light"
                w="fit-content"
                disabled={!note.trim()}
                loading={cancel.isPending}
                onClick={() => cancel.mutate({ params: { path: { id: request.id } }, body: { note } })}
              >
                {t('admin.cancelRequest')}
              </Button>
            </Stack>
          </Paper>
        )}
      </Stack>
    </Drawer>
  );
}

/** Four-eyes changes to sensitive master data: registry integration, counterparty registry accounts, bank accounts. */
export function ChangeRequestsPage() {
  const { t } = useTranslation();
  const { params, reset: resetUrl } = useUrlState();
  const status = FILTERS.find((value) => value === params.get('status')) ?? 'PENDING';
  const [open, setOpen] = useState<ChangeRequest | null>(null);
  const requests = $api.useQuery('get', '/api/v1/reference/change-requests', {
    params: { query: { status: status === 'ALL' ? undefined : status } },
  });
  const rows = requests.data ?? [];
  const columns = useMemo<ColDef<ChangeRequest>[]>(
    () => [
      {
        field: 'requestedAt',
        headerName: t('admin.requestedAt'),
        width: 160,
        sort: 'desc',
        valueFormatter: (p) => formatDateTime(p.value as string),
      },
      {
        field: 'entityType',
        headerName: t('admin.kind'),
        width: 220,
        valueFormatter: (p) => statusLabel(p.value as string),
      },
      { field: 'summary', headerName: t('admin.summary'), flex: 1, minWidth: 260, tooltipField: 'summary' },
      { field: 'requestedBy', headerName: t('admin.requestedBy'), width: 130 },
      {
        field: 'status',
        headerName: t('admin.status'),
        width: 130,
        cellRenderer: (p: ICellRendererParams<ChangeRequest>) =>
          p.data ? <RequestStatus status={p.data.status} /> : null,
      },
      {
        field: 'decidedBy',
        headerName: t('admin.decidedBy'),
        width: 130,
        valueFormatter: (p) => (p.value as string | null) ?? '—',
      },
    ],
    [t],
  );
  return (
    <Stack>
      <PageHeader title={t('admin.changeRequestsTitle')} description={t('admin.changeRequestsDescription')} />
      <SegmentedControl
        w="fit-content"
        data={FILTERS.map((value) => ({ value, label: t(`admin.requestStatus.${value}`) }))}
        value={status}
        onChange={(value) => resetUrl({ status: value })}
        aria-label={t('admin.status')}
        data-testid="change-request-status"
      />
      <QueryState isPending={false} error={requests.error}>
        {requests.isPending ? (
          <Paper withBorder>
            <TableSkeleton rows={4} />
          </Paper>
        ) : rows.length === 0 ? (
          <Paper withBorder>
            <EmptyState
              icon={<IconShieldCheck size={22} />}
              title={t(status === 'PENDING' ? 'admin.noPendingRequests' : 'admin.noRequests')}
              description={t('admin.noRequestsHint')}
              action={
                status !== 'ALL' ? (
                  <Button variant="light" size="xs" onClick={() => resetUrl({ status: 'ALL' })}>
                    {t('admin.showAllRequests')}
                  </Button>
                ) : undefined
              }
            />
          </Paper>
        ) : (
          <DataGrid<ChangeRequest>
            rowData={rows}
            columnDefs={columns}
            rowId={(row) => row.id}
            fitRows={12}
            onRowClicked={(event) => setOpen(event.data ?? null)}
          />
        )}
      </QueryState>
      {open && <RequestDrawer request={open} onClose={() => setOpen(null)} />}
    </Stack>
  );
}
