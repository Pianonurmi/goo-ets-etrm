import { useUrlState } from '@/shared/useUrlState';
import { Alert, Anchor, Button, Drawer, Paper, Select, Stack, Text } from '@mantine/core';
import { IconInbox } from '@tabler/icons-react';
import type { ColDef, ICellRendererParams } from 'ag-grid-community';
import { useMemo } from 'react';
import { useTranslation } from 'react-i18next';
import { Link } from 'react-router';
import { $api } from '@/shared/api/client';
import { FactGrid } from '@/shared/components/DetailLayout';
import { EmptyState } from '@/shared/components/EmptyState';
import { Money } from '@/shared/components/Figures';
import { LifecycleTimeline } from '@/shared/components/LifecycleTimeline';
import { QueryState } from '@/shared/components/QueryState';
import { TableSkeleton } from '@/shared/components/Skeletons';
import { StatusBadge } from '@/shared/components/StatusBadge';
import { formatDate, formatDateTime } from '@/shared/format/format';
import { DataGrid } from '@/shared/grid/DataGrid';

import { CALL_STATUSES, callSteps, newestFirst, statusFilter, type Account, type MarginCall } from './model/clearing';

/** One margin call: what is short, who asked, and the inbox task in which it is paid or disputed. */
function CallDrawer({ callId, onClose }: { callId: string; onClose: () => void }) {
  const { t } = useTranslation();
  const detail = $api.useQuery('get', '/api/v1/clearing/margin-calls/{callId}', { params: { path: { callId } } });
  const call = detail.data;
  const task = $api.useQuery(
    'get',
    '/api/v1/workflow/tasks',
    { params: { query: { subjectRef: callId, type: ['MARGIN_CALL'], status: 'OPEN' } } },
    { enabled: call?.status === 'OPEN' },
  );
  const open = task.data?.items[0];
  return (
    <Drawer
      opened
      onClose={onClose}
      position="right"
      size="lg"
      title={call ? t('clearing.callTitle', { date: formatDate(call.businessDate) }) : ''}
      data-testid="clearing-call-drawer"
    >
      <QueryState isPending={detail.isPending} error={detail.error}>
        {call && (
          <Stack>
            <Text size="sm" c="dimmed">
              {t('clearing.callHint')}
            </Text>
            <FactGrid
              cols={2}
              facts={[
                {
                  label: t('allocation.status'),
                  value: <StatusBadge status={call.status} data-testid="clearing-call-status" />,
                },
                {
                  label: t('clearing.callAmount'),
                  value: <Money value={call.amount} currency={call.currency} />,
                  testId: 'clearing-call-amount',
                },
                { label: t('clearing.businessDate'), value: formatDate(call.businessDate) },
                { label: t('clearing.requestedBy'), value: call.requestedBy },
                { label: t('clearing.requestedAt'), value: formatDateTime(call.requestedAt) },
                {
                  label: t('clearing.statement'),
                  value: (
                    <Anchor
                      component={Link}
                      to={`/clearing?tab=statements&account=${call.accountId}&statement=${call.statementId}`}
                      size="sm"
                      data-testid="clearing-call-statement"
                    >
                      {t('clearing.openStatement')}
                    </Anchor>
                  ),
                },
              ]}
            />
            {call.status === 'DISPUTED' && call.reason && (
              <Alert color="red" title={t('clearing.disputeReason')} data-testid="clearing-call-reason">
                {call.reason}
              </Alert>
            )}
            {open && (
              <Button
                component={Link}
                to={`/inbox/${open.id}`}
                leftSection={<IconInbox size={16} />}
                data-testid="clearing-call-task"
              >
                {t('clearing.callTask')}
              </Button>
            )}
            <LifecycleTimeline
              title={t('clearing.lifecycle.title')}
              steps={callSteps(call, (key) => t(`clearing.lifecycle.call.${key}`))}
              testId="clearing-call-lifecycle"
            />
          </Stack>
        )}
      </QueryState>
    </Drawer>
  );
}

/** Margin calls: cash at the member short of the initial margin; paid or disputed in the inbox. */
export function MarginCallsTab({ entityId, account }: { entityId: string; account: Account | null }) {
  const { t } = useTranslation();
  const { params, set } = useUrlState();
  const status = statusFilter(params.get('status'), CALL_STATUSES, 'OPEN');
  const callId = params.get('call');
  const calls = $api.useQuery('get', '/api/v1/clearing/margin-calls', {
    params: { query: { legalEntityId: entityId, status: status ?? undefined } },
  });
  const rows = useMemo(
    () => newestFirst((calls.data ?? []).filter((call) => !account || call.accountId === account.id)),
    [calls.data, account],
  );
  const columns = useMemo<ColDef<MarginCall>[]>(
    () => [
      { field: 'businessDate', headerName: t('clearing.businessDate'), width: 140, sort: 'desc' },
      {
        field: 'status',
        headerName: t('allocation.status'),
        width: 140,
        cellRenderer: (p: ICellRendererParams<MarginCall>) => p.data && <StatusBadge status={p.data.status} />,
      },
      {
        field: 'amount',
        headerName: t('clearing.callAmount'),
        width: 180,
        type: 'rightAligned',
        cellRenderer: (p: ICellRendererParams<MarginCall>) =>
          p.data && <Money value={p.data.amount} currency={p.data.currency} />,
      },
      { field: 'requestedBy', headerName: t('clearing.requestedBy'), width: 150 },
      {
        field: 'requestedAt',
        headerName: t('clearing.requestedAt'),
        width: 160,
        valueFormatter: (p) => formatDateTime(p.value as string),
      },
      { field: 'decidedBy', headerName: t('clearing.decidedBy'), width: 150 },
      {
        field: 'decidedAt',
        headerName: t('clearing.decidedAt'),
        width: 160,
        valueFormatter: (p) => formatDateTime(p.value as string | null),
      },
      { field: 'reason', headerName: t('clearing.disputeReason'), flex: 1, minWidth: 200, tooltipField: 'reason' },
    ],
    [t],
  );
  return (
    <Stack gap="sm">
      <Select
        aria-label={t('clearing.statusFilter')}
        data={[
          { value: 'ALL', label: t('clearing.anyStatus') },
          ...CALL_STATUSES.map((value) => ({ value, label: t(`clearing.callStatuses.${value}`) })),
        ]}
        value={status ?? 'ALL'}
        onChange={(value) => set({ status: value === 'OPEN' ? null : value })}
        allowDeselect={false}
        w={220}
        data-testid="clearing-calls-status"
      />
      <Text size="sm" c="dimmed" maw={760}>
        {t('clearing.callsHint')}
      </Text>
      <QueryState isPending={false} error={calls.error}>
        {calls.isPending ? (
          <Paper withBorder radius="lg">
            <TableSkeleton rows={4} />
          </Paper>
        ) : rows.length === 0 ? (
          <Paper withBorder radius="lg">
            <EmptyState
              title={status === 'OPEN' ? t('clearing.noOpenCalls') : t('clearing.noCalls')}
              description={t('clearing.noCallsHint')}
              testId="clearing-calls-empty"
            />
          </Paper>
        ) : (
          <div data-testid="clearing-calls-grid">
            <DataGrid<MarginCall>
              rowData={rows}
              columnDefs={columns}
              rowId={(row) => row.id}
              height={Math.min(480, 46 + rows.length * 40 + 2)}
              onRowClicked={(event) => event.data && set({ call: event.data.id })}
              rowStyle={{ cursor: 'pointer' }}
            />
          </div>
        )}
      </QueryState>
      {callId && <CallDrawer callId={callId} onClose={() => set({ call: null })} />}
    </Stack>
  );
}
