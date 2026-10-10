import { Badge, Button, Group, Modal, MultiSelect, Select, Stack, Switch, Text, Textarea } from '@mantine/core';
import type { ColDef, ICellRendererParams } from 'ag-grid-community';
import { useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useNavigate, useSearchParams } from 'react-router';
import { $api } from '@/shared/api/client';
import { useSession } from '@/shared/auth/SessionContext';
import { notifySuccess } from '@/shared/components/notify';
import { PageHeader } from '@/shared/components/PageHeader';
import { QueryState } from '@/shared/components/QueryState';
import { useEntity } from '@/shared/context/EntityContext';
import { formatDateTime } from '@/shared/format/format';
import { DataGrid } from '@/shared/grid/DataGrid';
import { useInvalidateWorkflow, useTaskTypes } from './hooks';
import { decisionBlock, isOverdue, reasonRequired, type Decision, type Task } from './model/decisions';
import { SubjectLink } from './SubjectLink';
import { StatusBadge } from './TaskBadges';

const STATUSES = ['OPEN', 'APPROVED', 'REJECTED', 'ACKNOWLEDGED', 'COMPLETED', 'CANCELLED'] as const;
const SORTS = ['DUE', 'REQUESTED', 'PRIORITY'] as const;
type Status = (typeof STATUSES)[number];
type Sort = (typeof SORTS)[number];

/** Inbox filters live in the URL, so a filtered inbox can be bookmarked and shared. */
function useInboxFilters() {
  const [params, setParams] = useSearchParams();
  const filters = {
    status: (params.get('status') ?? 'OPEN') as Status,
    types: params.getAll('type'),
    actionable: params.get('actionable') !== 'false',
    mine: params.get('mine') === 'true',
    overdue: params.get('overdue') === 'true',
    entityOnly: params.get('entityOnly') === 'true',
    sort: (params.get('sort') ?? 'DUE') as Sort,
  };
  const update = (changes: Partial<typeof filters>) => {
    const next = { ...filters, ...changes };
    const search = new URLSearchParams();
    if (next.status !== 'OPEN') search.set('status', next.status);
    next.types.forEach((type) => search.append('type', type));
    if (!next.actionable) search.set('actionable', 'false');
    if (next.mine) search.set('mine', 'true');
    if (next.overdue) search.set('overdue', 'true');
    if (next.entityOnly) search.set('entityOnly', 'true');
    if (next.sort !== 'DUE') search.set('sort', next.sort);
    setParams(search, { replace: true });
  };
  return { filters, update };
}

function BulkDecisionModal({
  decision,
  tasks,
  onClose,
}: {
  decision: Decision | null;
  tasks: Task[];
  onClose: () => void;
}) {
  const { t } = useTranslation();
  const types = useTaskTypes();
  const invalidate = useInvalidateWorkflow();
  const [reason, setReason] = useState('');
  const bulk = $api.useMutation('post', '/api/v1/workflow/tasks/bulk-decision');
  const needsReason = decision !== null && tasks.some((task) => reasonRequired(types.get(task.type), decision));

  const submit = () => {
    if (!decision) return;
    bulk.mutate(
      { body: { decision, reason: reason.trim() || null, taskIds: tasks.map((task) => task.id) } },
      {
        onSuccess: (results) => {
          const failed = results.filter((result) => !result.ok);
          notifySuccess(t('inbox.bulkResult', { ok: results.length - failed.length, failed: failed.length }));
          setReason('');
          void invalidate();
          onClose();
        },
      },
    );
  };

  return (
    <Modal
      opened={decision !== null}
      onClose={onClose}
      title={t(`inbox.bulk.${decision ?? 'APPROVE'}`, { count: tasks.length })}
    >
      <Stack>
        <Text size="sm">{t('inbox.bulkHint')}</Text>
        <Textarea
          label={t('inbox.reason')}
          required={needsReason}
          value={reason}
          onChange={(event) => setReason(event.currentTarget.value)}
          autosize
          minRows={2}
        />
        <Group justify="flex-end">
          <Button variant="default" onClick={onClose}>
            {t('common.cancel')}
          </Button>
          <Button
            color={decision === 'REJECT' ? 'red' : 'teal'}
            loading={bulk.isPending}
            disabled={needsReason && !reason.trim()}
            onClick={submit}
            data-testid="bulk-confirm"
          >
            {t(`inbox.decision.${decision ?? 'APPROVE'}`)}
          </Button>
        </Group>
      </Stack>
    </Modal>
  );
}

export function InboxPage() {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const { me } = useSession();
  const { entity } = useEntity();
  const types = useTaskTypes();
  const { filters, update } = useInboxFilters();
  const [selected, setSelected] = useState<Task[]>([]);
  const [bulkDecision, setBulkDecision] = useState<Decision | null>(null);

  const query = {
    status: filters.status,
    type: filters.types.length > 0 ? filters.types : undefined,
    actionable: filters.actionable,
    mine: filters.mine,
    overdue: filters.overdue || undefined,
    legalEntityId: filters.entityOnly && entity ? entity.id : undefined,
    sort: filters.sort,
    limit: 100,
  };
  const tasks = $api.useInfiniteQuery(
    'get',
    '/api/v1/workflow/tasks',
    { params: { query } },
    {
      // null, not undefined: the binding turns an undefined first page into cursor=0.
      initialPageParam: null as string | null,
      getNextPageParam: (page) => page.nextCursor ?? undefined,
      pageParamName: 'cursor',
    },
  );
  const rows = useMemo(() => tasks.data?.pages.flatMap((page) => page.items) ?? [], [tasks.data]);
  const decidable = selected.filter((task) => decisionBlock(task, types.get(task.type), me) === null);

  const columns = useMemo<ColDef<Task>[]>(
    () => [
      { field: 'priority', headerName: t('inbox.columns.priority'), width: 100, sort: undefined },
      {
        colId: 'type',
        headerName: t('inbox.columns.type'),
        valueGetter: (params) => (params.data ? (types.get(params.data.type)?.displayName ?? params.data.type) : ''),
        width: 200,
      },
      { field: 'summary', headerName: t('inbox.columns.summary'), flex: 2, minWidth: 260 },
      {
        colId: 'subject',
        headerName: t('inbox.columns.subject'),
        width: 210,
        cellRenderer: (params: ICellRendererParams<Task>) =>
          params.data ? (
            <SubjectLink subjectType={params.data.subjectType} subjectRef={params.data.subjectRef} />
          ) : null,
      },
      { field: 'requestedBy', headerName: t('inbox.columns.requestedBy'), width: 140 },
      {
        field: 'requestedAt',
        headerName: t('inbox.columns.requestedAt'),
        width: 160,
        valueFormatter: (params) => formatDateTime(params.value as string),
      },
      {
        field: 'dueAt',
        headerName: t('inbox.columns.dueAt'),
        width: 170,
        cellRenderer: (params: ICellRendererParams<Task>) =>
          params.data ? (
            <Group gap={4} wrap="nowrap">
              <span>{formatDateTime(params.data.dueAt)}</span>
              {isOverdue(params.data) && (
                <Badge color="red" size="xs">
                  {t('inbox.overdue')}
                </Badge>
              )}
            </Group>
          ) : null,
      },
      { field: 'assignee', headerName: t('inbox.columns.assignee'), width: 130 },
      {
        field: 'status',
        headerName: t('inbox.columns.status'),
        width: 130,
        cellRenderer: (params: ICellRendererParams<Task>) =>
          params.data ? <StatusBadge status={params.data.status} /> : null,
      },
    ],
    [t, types],
  );

  const typeOptions = [...types.values()]
    .map((type) => ({ value: type.taskType, label: type.displayName }))
    .sort((a, b) => a.label.localeCompare(b.label));

  return (
    <Stack>
      <PageHeader
        title={t('inbox.title')}
        description={t('inbox.description')}
        actions={
          <>
            <Button
              color="teal"
              disabled={decidable.length === 0}
              onClick={() => setBulkDecision('APPROVE')}
              data-testid="bulk-approve"
            >
              {t('inbox.bulk.APPROVE', { count: decidable.length })}
            </Button>
            <Button
              color="red"
              variant="light"
              disabled={decidable.length === 0}
              onClick={() => setBulkDecision('REJECT')}
            >
              {t('inbox.bulk.REJECT', { count: decidable.length })}
            </Button>
          </>
        }
      />
      <Group gap="md" align="flex-end">
        <Select
          label={t('inbox.filters.status')}
          data={STATUSES.map((status) => ({ value: status, label: t(`inbox.status.${status}`) }))}
          value={filters.status}
          onChange={(value) => update({ status: value ?? 'OPEN' })}
          allowDeselect={false}
          w={170}
        />
        <MultiSelect
          label={t('inbox.filters.type')}
          data={typeOptions}
          value={filters.types}
          onChange={(value) => update({ types: value })}
          placeholder={t('inbox.filters.allTypes')}
          clearable
          searchable
          w={320}
        />
        <Select
          label={t('inbox.filters.sort')}
          data={SORTS.map((sort) => ({ value: sort, label: t(`inbox.sort.${sort}`) }))}
          value={filters.sort}
          onChange={(value) => update({ sort: value ?? 'DUE' })}
          allowDeselect={false}
          w={170}
        />
        <Stack gap={6}>
          <Switch
            label={t('inbox.filters.actionable')}
            checked={filters.actionable}
            onChange={(event) => update({ actionable: event.currentTarget.checked })}
            data-testid="filter-actionable"
          />
          <Switch
            label={t('inbox.filters.mine')}
            checked={filters.mine}
            onChange={(event) => update({ mine: event.currentTarget.checked })}
          />
        </Stack>
        <Stack gap={6}>
          <Switch
            label={t('inbox.filters.overdue')}
            checked={filters.overdue}
            onChange={(event) => update({ overdue: event.currentTarget.checked })}
          />
          <Switch
            label={t('inbox.filters.entityOnly', { entity: entity?.code ?? '' })}
            checked={filters.entityOnly}
            disabled={!entity}
            onChange={(event) => update({ entityOnly: event.currentTarget.checked })}
          />
        </Stack>
      </Group>
      <QueryState isPending={tasks.isPending} error={tasks.error}>
        <DataGrid<Task>
          rowData={rows}
          columnDefs={columns}
          rowId={(task) => task.id}
          rowSelection={{ mode: 'multiRow' }}
          onSelectionChanged={(event) => setSelected(event.api.getSelectedRows())}
          onRowClicked={(event) => {
            if (event.data && !(event.event?.target instanceof HTMLInputElement))
              void navigate(`/inbox/${event.data.id}`);
          }}
          overlayNoRowsTemplate={t('inbox.empty')}
          height={560}
        />
        <Group justify="space-between">
          <Text size="sm" c="dimmed" data-testid="inbox-count">
            {t('inbox.shown', { count: rows.length })}
          </Text>
          {tasks.hasNextPage && (
            <Button variant="light" loading={tasks.isFetchingNextPage} onClick={() => void tasks.fetchNextPage()}>
              {t('inbox.loadMore')}
            </Button>
          )}
        </Group>
      </QueryState>
      <BulkDecisionModal decision={bulkDecision} tasks={decidable} onClose={() => setBulkDecision(null)} />
    </Stack>
  );
}
