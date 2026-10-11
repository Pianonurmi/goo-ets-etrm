import { Badge, Button, Code, Drawer, Group, Select, Stack, Table, Text, TextInput } from '@mantine/core';
import { IconInbox } from '@tabler/icons-react';
import type { ColDef } from 'ag-grid-community';
import { useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link, useSearchParams } from 'react-router';
import { $api } from '@/shared/api/client';
import type { Schema } from '@/shared/api/types';
import { useSession } from '@/shared/auth/SessionContext';
import { notifySuccess } from '@/shared/components/notify';
import { PageHeader } from '@/shared/components/PageHeader';
import { ProblemAlert } from '@/shared/components/ProblemAlert';
import { QueryState } from '@/shared/components/QueryState';
import { formatDateTime } from '@/shared/format/format';
import { DataGrid } from '@/shared/grid/DataGrid';
import { useInvalidateReference } from './PartyDetails';

type ChangeRequest = Schema<'ChangeRequestView'>;
type Status = ChangeRequest['status'];
const STATUS_COLORS: Record<Status, string> = {
  PENDING: 'yellow',
  APPLIED: 'teal',
  REJECTED: 'red',
  CANCELLED: 'gray',
};

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
  return (
    <Drawer opened onClose={onClose} position="right" size="lg" title={request.summary}>
      <Stack>
        <Group gap="xs">
          <Badge color={STATUS_COLORS[request.status]} variant="light">
            {request.status}
          </Badge>
          <Text size="sm">
            {request.entityType} · {request.entityKey}
          </Text>
        </Group>
        <Text size="sm">
          {t('admin.requestedByWho', { who: request.requestedBy, at: formatDateTime(request.requestedAt) })}
        </Text>
        <Text size="sm">
          {t('risk.reason')}: {request.reason}
        </Text>
        {request.decidedBy && (
          <Text size="sm">
            {t('admin.decidedByWho', { who: request.decidedBy, at: formatDateTime(request.decidedAt) })}
            {request.decisionNote ? ` · ${request.decisionNote}` : ''}
          </Text>
        )}
        <Table withTableBorder fz="sm">
          <Table.Tbody>
            {Object.entries(request.change).map(([key, value]) => (
              <Table.Tr key={key}>
                <Table.Td>
                  <Code>{key}</Code>
                </Table.Td>
                <Table.Td>{typeof value === 'string' ? value : JSON.stringify(value)}</Table.Td>
              </Table.Tr>
            ))}
          </Table.Tbody>
        </Table>
        {task.data?.items[0] && (
          <Button
            component={Link}
            to={`/inbox/${task.data.items[0].id}`}
            variant="light"
            leftSection={<IconInbox size={16} />}
          >
            {t('risk.approvalTask')}
          </Button>
        )}
        {mayCancel && (
          <Stack gap="xs">
            <ProblemAlert error={cancel.error} />
            <TextInput label={t('admin.cancelNote')} value={note} onChange={(e) => setNote(e.currentTarget.value)} />
            <Button
              color="red"
              variant="light"
              disabled={!note.trim()}
              loading={cancel.isPending}
              onClick={() => cancel.mutate({ params: { path: { id: request.id } }, body: { note } })}
            >
              {t('admin.cancelRequest')}
            </Button>
          </Stack>
        )}
      </Stack>
    </Drawer>
  );
}

/** Four-eyes changes to sensitive master data: registry integration, counterparty registry accounts, bank accounts. */
export function ChangeRequestsPage() {
  const { t } = useTranslation();
  const [params, setParams] = useSearchParams();
  const status = (params.get('status') ?? 'PENDING') as Status | 'ALL';
  const [open, setOpen] = useState<ChangeRequest | null>(null);
  const requests = $api.useQuery('get', '/api/v1/reference/change-requests', {
    params: { query: { status: status === 'ALL' ? undefined : status } },
  });
  const columns = useMemo<ColDef<ChangeRequest>[]>(
    () => [
      {
        field: 'requestedAt',
        headerName: t('admin.requestedAt'),
        width: 170,
        sort: 'desc',
        valueFormatter: (p) => formatDateTime(p.value as string),
      },
      { field: 'entityType', headerName: t('marketData.kind'), width: 230 },
      { field: 'summary', headerName: t('admin.summary'), flex: 1, minWidth: 260 },
      { field: 'requestedBy', headerName: t('risk.requestedBy'), width: 130 },
      { field: 'status', headerName: t('allocation.status'), width: 120 },
      { field: 'decidedBy', headerName: t('admin.decidedBy'), width: 130 },
    ],
    [t],
  );
  return (
    <Stack>
      <PageHeader title={t('admin.changeRequestsTitle')} description={t('admin.changeRequestsDescription')} />
      <Select
        label={t('allocation.status')}
        data={(['PENDING', 'APPLIED', 'REJECTED', 'CANCELLED', 'ALL'] as const).map((value) => ({
          value,
          label: t(`admin.requestStatus.${value}`),
        }))}
        value={status}
        allowDeselect={false}
        onChange={(value) => value && setParams({ status: value }, { replace: true })}
        w={220}
      />
      <QueryState isPending={requests.isPending} error={requests.error}>
        <DataGrid<ChangeRequest>
          rowData={requests.data ?? []}
          columnDefs={columns}
          rowId={(row) => row.id}
          height={480}
          onRowClicked={(event) => setOpen(event.data ?? null)}
          overlayNoRowsTemplate={t('common.empty')}
        />
      </QueryState>
      {open && <RequestDrawer request={open} onClose={() => setOpen(null)} />}
    </Stack>
  );
}
