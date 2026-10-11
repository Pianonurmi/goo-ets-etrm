import {
  Button,
  Group,
  Modal,
  MultiSelect,
  Paper,
  Select,
  Stack,
  Switch,
  Text,
  Textarea,
  Tooltip,
} from '@mantine/core';
import { IconChecks, IconInbox, IconX } from '@tabler/icons-react';
import type { ColDef, ICellRendererParams, RowStyle } from 'ag-grid-community';
import { useMediaQuery } from '@mantine/hooks';
import { useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useNavigate, useSearchParams } from 'react-router';
import { $api } from '@/shared/api/client';
import { useSession } from '@/shared/auth/SessionContext';
import { EmptyState } from '@/shared/components/EmptyState';
import { notifySuccess } from '@/shared/components/notify';
import { PageHeader } from '@/shared/components/PageHeader';
import { QueryState } from '@/shared/components/QueryState';
import { TableSkeleton } from '@/shared/components/Skeletons';
import { useEntity } from '@/shared/context/EntityContext';
import { formatDateTime } from '@/shared/format/format';
import { DataGrid } from '@/shared/grid/DataGrid';
import { useInvalidateWorkflow, useTaskTypes } from './hooks';
import { decisionBlock, isOverdue, reasonRequired, type Decision, type Task } from './model/decisions';
import { span } from './model/time';
import { SubjectLink } from './SubjectLink';
import { DueLabel, PriorityLabel, TaskStatus, useSpanText } from './TaskBadges';

const STATUSES = ['OPEN', 'APPROVED', 'REJECTED', 'ACKNOWLEDGED', 'COMPLETED', 'CANCELLED'] as const;
const SORTS = ['DUE', 'REQUESTED', 'PRIORITY'] as const;
const CENTERED = { display: 'flex', alignItems: 'center' };
const OVERDUE_ROW: RowStyle = { boxShadow: 'inset 3px 0 0 var(--etrm-loss)' };
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

const NO_FILTERS = { status: 'OPEN', types: [], mine: false, overdue: false, entityOnly: false } as const;

export function InboxPage() {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const { me } = useSession();
  const { entity } = useEntity();
  const types = useTaskTypes();
  const words = useSpanText();
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
  const anyAssigned = rows.some((task) => task.assignee);
  // Below 1200 px the subject and priority stay on the task page, so the grid fits without scrolling sideways.
  const wide = useMediaQuery('(min-width: 75em)', true);
  const decidable = selected.filter((task) => decisionBlock(task, types.get(task.type), me) === null);
  const filtered =
    filters.status !== 'OPEN' || filters.types.length > 0 || filters.mine || filters.overdue || filters.entityOnly;
  const clear = () => update({ ...NO_FILTERS, types: [] });

  const columns = useMemo<ColDef<Task>[]>(
    () => [
      {
        colId: 'type',
        headerName: t('inbox.columns.type'),
        valueGetter: (params) => (params.data ? (types.get(params.data.type)?.displayName ?? params.data.type) : ''),
        width: 170,
      },
      { field: 'summary', headerName: t('inbox.columns.summary'), flex: 2, minWidth: 220, tooltipField: 'summary' },
      {
        colId: 'subject',
        headerName: t('inbox.columns.subject'),
        hide: !wide,
        width: 170,
        valueGetter: (params) => params.data?.subjectType ?? '',
        cellRenderer: (params: ICellRendererParams<Task>) =>
          params.data ? (
            <SubjectLink subjectType={params.data.subjectType} subjectRef={params.data.subjectRef} />
          ) : null,
      },
      {
        field: 'requestedAt',
        headerName: t('inbox.columns.requested'),
        width: 150,
        cellRenderer: (params: ICellRendererParams<Task>) =>
          params.data ? (
            <Tooltip label={formatDateTime(params.data.requestedAt)} withArrow>
              <span>
                {params.data.requestedBy} ·{' '}
                {t('inbox.ago', { span: words(span(new Date(params.data.requestedAt).getTime(), Date.now())) })}
              </span>
            </Tooltip>
          ) : null,
      },
      {
        field: 'dueAt',
        headerName: t('inbox.columns.dueAt'),
        width: 140,
        cellStyle: CENTERED,
        cellRenderer: (params: ICellRendererParams<Task>) => (params.data ? <DueLabel task={params.data} /> : null),
      },
      {
        field: 'priority',
        headerName: t('inbox.columns.priority'),
        hide: !wide,
        width: 100,
        cellStyle: CENTERED,
        cellRenderer: (params: ICellRendererParams<Task>) =>
          params.data ? <PriorityLabel priority={params.data.priority} /> : null,
      },
      {
        field: 'assignee',
        headerName: t('inbox.columns.assignee'),
        width: 110,
        hide: !anyAssigned || !wide,
        valueFormatter: (params) => (params.value as string | null) ?? '—',
      },
      {
        field: 'status',
        headerName: t('inbox.columns.status'),
        width: 130,
        hide: filters.status === 'OPEN',
        cellRenderer: (params: ICellRendererParams<Task>) =>
          params.data ? <TaskStatus status={params.data.status} /> : null,
      },
    ],
    [t, types, words, filters.status, anyAssigned, wide],
  );

  const typeOptions = [...types.values()]
    .map((type) => ({ value: type.taskType, label: type.displayName }))
    .sort((a, b) => a.label.localeCompare(b.label));

  return (
    <Stack>
      <PageHeader title={t('inbox.title')} description={t('inbox.description')} />
      <Paper withBorder p="md">
        <Group gap="md" align="flex-end" justify="space-between">
          <Group gap="sm" align="flex-end">
            <Select
              label={t('inbox.filters.status')}
              data={STATUSES.map((status) => ({ value: status, label: t(`inbox.status.${status}`) }))}
              value={filters.status}
              onChange={(value) => update({ status: value ?? 'OPEN' })}
              allowDeselect={false}
              w={150}
            />
            <MultiSelect
              label={t('inbox.filters.type')}
              data={typeOptions}
              value={filters.types}
              onChange={(value) => update({ types: value })}
              placeholder={filters.types.length === 0 ? t('inbox.filters.allTypes') : undefined}
              clearable
              searchable
              w={280}
            />
            <Select
              label={t('inbox.filters.sort')}
              data={SORTS.map((sort) => ({ value: sort, label: t(`inbox.sort.${sort}`) }))}
              value={filters.sort}
              onChange={(value) => update({ sort: value ?? 'DUE' })}
              allowDeselect={false}
              w={150}
            />
          </Group>
          {filtered && (
            <Button variant="subtle" size="xs" leftSection={<IconX size={14} />} onClick={clear}>
              {t('inbox.clearFilters')}
            </Button>
          )}
        </Group>
        <Group gap="lg" mt="md">
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
        </Group>
      </Paper>
      {selected.length > 0 && (
        <Paper withBorder p="sm" bg="var(--etrm-tint)" data-testid="inbox-selection">
          <Group justify="space-between" gap="sm">
            <Text size="sm">{t('inbox.selection', { count: selected.length, decidable: decidable.length })}</Text>
            <Group gap="xs">
              <Button
                size="xs"
                color="teal"
                leftSection={<IconChecks size={14} />}
                disabled={decidable.length === 0}
                onClick={() => setBulkDecision('APPROVE')}
                data-testid="bulk-approve"
              >
                {t('inbox.bulk.APPROVE', { count: decidable.length })}
              </Button>
              <Button
                size="xs"
                color="red"
                variant="light"
                disabled={decidable.length === 0}
                onClick={() => setBulkDecision('REJECT')}
              >
                {t('inbox.bulk.REJECT', { count: decidable.length })}
              </Button>
            </Group>
          </Group>
        </Paper>
      )}
      <QueryState isPending={false} error={tasks.error}>
        {tasks.isPending ? (
          <Paper withBorder>
            <TableSkeleton rows={6} />
          </Paper>
        ) : rows.length === 0 ? (
          <Paper withBorder>
            {filters.actionable && !filtered ? (
              <EmptyState
                testId="inbox-empty"
                icon={<IconInbox size={22} />}
                title={t('inbox.allClear')}
                description={t('inbox.allClearHint')}
                action={
                  <Button variant="light" size="xs" onClick={() => update({ actionable: false })}>
                    {t('inbox.showAllOpen')}
                  </Button>
                }
              />
            ) : (
              <EmptyState
                testId="inbox-empty"
                title={t('inbox.empty')}
                description={t('inbox.emptyHint')}
                action={
                  filtered ? (
                    <Button variant="light" size="xs" onClick={clear}>
                      {t('inbox.clearFilters')}
                    </Button>
                  ) : undefined
                }
              />
            )}
          </Paper>
        ) : (
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
            getRowStyle={(params) => (params.data && isOverdue(params.data) ? OVERDUE_ROW : undefined)}
            fitRows={13}
            overlayNoRowsTemplate={t('inbox.empty')}
          />
        )}
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
