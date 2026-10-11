import { Button, Code, Drawer, Group, ScrollArea, Stack, Text, TextInput } from '@mantine/core';
import type { ColDef } from 'ag-grid-community';
import dayjs from 'dayjs';
import { useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useSearchParams } from 'react-router';
import { $api } from '@/shared/api/client';
import type { Schema } from '@/shared/api/types';
import { PageHeader } from '@/shared/components/PageHeader';
import { QueryState } from '@/shared/components/QueryState';
import { formatDateTime } from '@/shared/format/format';
import { DataGrid } from '@/shared/grid/DataGrid';

type AuditEvent = Schema<'AuditEventView'>;
const PAGE = 200;
const FILTERS = ['eventType', 'actor', 'aggregateType', 'aggregateRef'] as const;

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
      { field: 'seq', headerName: '#', width: 100 },
      {
        field: 'occurredAt',
        headerName: t('audit.occurredAt'),
        width: 180,
        valueFormatter: (p) => formatDateTime(p.value as string),
      },
      { field: 'eventType', headerName: t('audit.eventType'), width: 240 },
      { field: 'actor', headerName: t('audit.actor'), width: 140 },
      { field: 'aggregateType', headerName: t('audit.aggregateType'), width: 160 },
      { field: 'aggregateRef', headerName: t('audit.aggregateRef'), flex: 1, minWidth: 260 },
    ],
    [t],
  );
  return (
    <Stack>
      <PageHeader title={t('audit.title')} description={t('audit.description')} />
      <Group align="flex-end">
        {FILTERS.map((key) => (
          <TextInput
            key={key}
            label={t(`audit.${key}`)}
            value={filter[key]}
            onChange={(e) => set(key, e.currentTarget.value)}
            w={200}
            data-testid={`audit-${key}`}
          />
        ))}
        <TextInput
          type="date"
          label={t('trades.from')}
          value={from}
          onChange={(e) => set('from', e.currentTarget.value)}
        />
        <TextInput type="date" label={t('trades.to')} value={to} onChange={(e) => set('to', e.currentTarget.value)} />
      </Group>
      <QueryState isPending={events.isPending} error={events.error}>
        <DataGrid<AuditEvent>
          rowData={rows}
          columnDefs={columns}
          rowId={(row) => row.eventId}
          height={520}
          onRowClicked={(event) => setOpen(event.data ?? null)}
          overlayNoRowsTemplate={t('common.empty')}
        />
        <Group justify="space-between">
          <Text size="sm" c="dimmed">
            {t('audit.shown', { count: rows.length })}
          </Text>
          {events.hasNextPage && (
            <Button variant="default" loading={events.isFetchingNextPage} onClick={() => void events.fetchNextPage()}>
              {t('ledger.more')}
            </Button>
          )}
        </Group>
      </QueryState>
      {open && (
        <Drawer
          opened
          onClose={() => setOpen(null)}
          position="right"
          size="xl"
          title={`${open.eventType} · #${open.seq}`}
        >
          <Stack gap="xs">
            <Text size="sm">
              {t('audit.byAt', {
                who: open.actor,
                at: formatDateTime(open.occurredAt),
                recorded: formatDateTime(open.recordedAt),
              })}
            </Text>
            {open.correlationId && (
              <Text size="xs" c="dimmed">
                {t('audit.correlation')}: {open.correlationId}
              </Text>
            )}
            <ScrollArea h="70vh">
              <Code block data-testid="audit-payload">
                {JSON.stringify(open.payload, null, 2)}
              </Code>
            </ScrollArea>
          </Stack>
        </Drawer>
      )}
    </Stack>
  );
}
