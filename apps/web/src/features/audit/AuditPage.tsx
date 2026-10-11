import { Anchor, Button, Code, Drawer, Group, Paper, ScrollArea, Stack, Text, TextInput, Title } from '@mantine/core';
import { IconSearch, IconX } from '@tabler/icons-react';
import type { ColDef, ICellRendererParams } from 'ag-grid-community';
import dayjs from 'dayjs';
import { useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useSearchParams } from 'react-router';
import { $api } from '@/shared/api/client';
import type { Schema } from '@/shared/api/types';
import { FactGrid } from '@/shared/components/DetailLayout';
import { EmptyState } from '@/shared/components/EmptyState';
import { PageHeader } from '@/shared/components/PageHeader';
import { QueryState } from '@/shared/components/QueryState';
import { TableSkeleton } from '@/shared/components/Skeletons';
import { formatDateTime } from '@/shared/format/format';
import { DataGrid } from '@/shared/grid/DataGrid';
import { eventLabel } from '@/features/home/model/home';

type AuditEvent = Schema<'AuditEventView'>;
const PAGE = 200;
const FILTERS = ['eventType', 'actor', 'aggregateType', 'aggregateRef'] as const;
const DATES = ['from', 'to'] as const;

function Mono({ children }: { children: string | null | undefined }) {
  return (
    <Text span ff="monospace" size="xs">
      {children ?? '—'}
    </Text>
  );
}

/** One event: who, when and what, the record it belongs to, and its payload as recorded. */
function EventDrawer({
  event,
  onClose,
  onFilter,
}: {
  event: AuditEvent;
  onClose: () => void;
  onFilter: (aggregateRef: string) => void;
}) {
  const { t } = useTranslation();
  return (
    <Drawer
      opened
      onClose={onClose}
      position="right"
      size="xl"
      title={
        <Stack gap={0}>
          <Text fw={600}>{eventLabel(event.eventType)}</Text>
          <Text size="xs" c="dimmed" ff="monospace">
            {event.eventType} · #{event.seq}
          </Text>
        </Stack>
      }
    >
      <Stack gap="lg">
        <Text size="sm">
          {t('audit.byAt', {
            who: event.actor,
            at: formatDateTime(event.occurredAt),
            recorded: formatDateTime(event.recordedAt),
          })}
        </Text>
        <Paper withBorder p="md">
          <FactGrid
            cols={2}
            facts={[
              { label: t('audit.actor'), value: event.actor },
              { label: t('audit.occurredAt'), value: formatDateTime(event.occurredAt) },
              { label: t('audit.aggregateType'), value: event.aggregateType ?? '—' },
              { label: t('audit.aggregateRef'), value: <Mono>{event.aggregateRef}</Mono> },
              { label: t('audit.correlation'), value: <Mono>{event.correlationId}</Mono> },
              { label: t('audit.eventId'), value: <Mono>{event.eventId}</Mono> },
            ]}
          />
          {event.aggregateRef && (
            <Anchor component="button" size="sm" mt="md" onClick={() => onFilter(event.aggregateRef ?? '')}>
              {t('audit.sameRecord')}
            </Anchor>
          )}
        </Paper>
        <Stack gap="xs">
          <Title order={4} size="h6">
            {t('audit.payload')}
          </Title>
          <ScrollArea.Autosize mah="60vh">
            <Code block data-testid="audit-payload">
              {JSON.stringify(event.payload, null, 2)}
            </Code>
          </ScrollArea.Autosize>
        </Stack>
      </Stack>
    </Drawer>
  );
}

/** The append-only audit trail: every domain event and command, filterable, paged by sequence number. */
export function AuditPage() {
  const { t } = useTranslation();
  const [params, setParams] = useSearchParams();
  const [open, setOpen] = useState<AuditEvent | null>(null);
  const filter = Object.fromEntries(FILTERS.map((key) => [key, params.get(key) ?? ''])) as Record<
    (typeof FILTERS)[number],
    string
  >;
  const from = params.get('from') ?? '';
  const to = params.get('to') ?? '';
  const active = [...FILTERS, ...DATES].some((key) => params.get(key));
  const set = (key: string, value: string) => {
    const next = new URLSearchParams(params);
    if (value) next.set(key, value);
    else next.delete(key);
    setParams(next, { replace: true });
  };
  const events = $api.useInfiniteQuery(
    'get',
    '/api/v1/audit/events',
    {
      params: {
        query: {
          eventType: filter.eventType || undefined,
          actor: filter.actor || undefined,
          aggregateType: filter.aggregateType || undefined,
          aggregateRef: filter.aggregateRef || undefined,
          from: from ? dayjs(from).startOf('day').toISOString() : undefined,
          to: to ? dayjs(to).add(1, 'day').startOf('day').toISOString() : undefined,
          limit: PAGE,
        },
      },
    },
    {
      initialPageParam: null as number | null,
      getNextPageParam: (page) => (page.length === PAGE ? page[page.length - 1]?.seq : undefined),
      pageParamName: 'afterSeq',
    },
  );
  const rows = useMemo(() => events.data?.pages.flat() ?? [], [events.data]);
  const columns = useMemo<ColDef<AuditEvent>[]>(
    () => [
      { field: 'seq', headerName: '#', width: 90 },
      {
        field: 'occurredAt',
        headerName: t('audit.occurredAt'),
        width: 160,
        valueFormatter: (p) => formatDateTime(p.value as string),
      },
      { field: 'actor', headerName: t('audit.actor'), width: 130 },
      {
        field: 'eventType',
        headerName: t('audit.eventType'),
        width: 240,
        tooltipValueGetter: (p) => (p.data ? eventLabel(p.data.eventType) : ''),
      },
      { field: 'aggregateType', headerName: t('audit.aggregateType'), width: 150 },
      {
        field: 'aggregateRef',
        headerName: t('audit.aggregateRef'),
        flex: 1,
        minWidth: 240,
        cellRenderer: (p: ICellRendererParams<AuditEvent>) => <Mono>{p.data?.aggregateRef}</Mono>,
      },
    ],
    [t],
  );
  return (
    <Stack>
      <PageHeader title={t('audit.title')} description={t('audit.description')} />
      <Paper withBorder p="md">
        <Group align="flex-end" gap="sm">
          {FILTERS.map((key) => (
            <TextInput
              key={key}
              label={t(`audit.${key}`)}
              placeholder={t(`audit.placeholder.${key}`)}
              leftSection={key === 'aggregateRef' ? <IconSearch size={14} /> : undefined}
              value={filter[key]}
              onChange={(e) => set(key, e.currentTarget.value)}
              w={key === 'aggregateRef' ? 220 : key === 'eventType' ? 170 : 140}
              data-testid={`audit-${key}`}
            />
          ))}
          <TextInput
            type="date"
            label={t('audit.from')}
            value={from}
            onChange={(e) => set('from', e.currentTarget.value)}
            w={140}
          />
          <TextInput
            type="date"
            label={t('audit.to')}
            value={to}
            onChange={(e) => set('to', e.currentTarget.value)}
            w={140}
          />
          {active && (
            <Button
              variant="subtle"
              size="sm"
              leftSection={<IconX size={14} />}
              onClick={() => setParams({}, { replace: true })}
            >
              {t('audit.clear')}
            </Button>
          )}
        </Group>
      </Paper>
      <QueryState isPending={false} error={events.error}>
        {events.isPending ? (
          <Paper withBorder>
            <TableSkeleton rows={8} />
          </Paper>
        ) : rows.length === 0 ? (
          <Paper withBorder>
            <EmptyState
              title={t('audit.empty')}
              description={active ? t('audit.emptyFiltered') : t('audit.emptyHint')}
              action={
                active ? (
                  <Button variant="light" size="xs" onClick={() => setParams({}, { replace: true })}>
                    {t('audit.clear')}
                  </Button>
                ) : undefined
              }
            />
          </Paper>
        ) : (
          <DataGrid<AuditEvent>
            rowData={rows}
            columnDefs={columns}
            rowId={(row) => row.eventId}
            height={560}
            onRowClicked={(event) => setOpen(event.data ?? null)}
          />
        )}
        <Group justify="space-between">
          <Text size="sm" c="dimmed">
            {t('audit.shown', { count: rows.length })}
          </Text>
          {events.hasNextPage && (
            <Button variant="default" loading={events.isFetchingNextPage} onClick={() => void events.fetchNextPage()}>
              {t('audit.more')}
            </Button>
          )}
        </Group>
      </QueryState>
      {open && (
        <EventDrawer
          event={open}
          onClose={() => setOpen(null)}
          onFilter={(aggregateRef) => {
            setOpen(null);
            set('aggregateRef', aggregateRef);
          }}
        />
      )}
    </Stack>
  );
}
