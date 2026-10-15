import { Alert, Button, Group, Select, Stack, Text, TextInput } from '@mantine/core';
import { IconPlus, IconShieldCheck } from '@tabler/icons-react';
import type { ColDef, ICellRendererParams } from 'ag-grid-community';
import { useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { $api } from '@/shared/api/client';
import { Can } from '@/shared/auth/Can';
import { QueryState } from '@/shared/components/QueryState';
import { DataGrid } from '@/shared/grid/DataGrid';
import { useUrlState } from '@/shared/useUrlState';
import { EntryDrawer } from './EntryDrawer';
import { useAccounts } from './hooks';
import { ManualJournalModal } from './ManualJournalModal';
import { type Entry, EntryStatusBadge, amount } from './shared';

export const PAGE = 200;

export function EntriesTab({ entityId }: { entityId: string }) {
  const { t } = useTranslation();
  const { params, set: patchUrl } = useUrlState();
  const period = params.get('period') ?? '';
  const accountCode = params.get('account');
  const sourceRef = params.get('source') ?? '';
  const [open, setOpen] = useState<string | null>(null);
  const [manual, setManual] = useState(false);
  const accounts = useAccounts(entityId);
  const setFilter = (key: string, value: string | null) => patchUrl({ [key]: value });
  const entries = $api.useInfiniteQuery(
    'get',
    '/api/v1/ledger/entries',
    {
      params: {
        query: {
          legalEntityId: entityId,
          period: period || undefined,
          accountCode: accountCode ?? undefined,
          sourceRef: sourceRef || undefined,
          limit: PAGE,
        },
      },
    },
    {
      initialPageParam: null as number | null,
      getNextPageParam: (page) => (page.length === PAGE ? page[page.length - 1]?.entryNumber : undefined),
      pageParamName: 'afterEntryNumber',
    },
  );
  const rows = useMemo(() => entries.data?.pages.flat() ?? [], [entries.data]);
  const verify = $api.useQuery(
    'get',
    '/api/v1/ledger/journal/verify',
    { params: { query: { legalEntityId: entityId } } },
    { enabled: false },
  );
  const columns = useMemo<ColDef<Entry>[]>(
    () => [
      { field: 'entryNumber', headerName: t('ledger.number'), width: 100 },
      { field: 'postingDate', headerName: t('ledger.postingDate'), width: 130 },
      { field: 'period', headerName: t('ledger.period'), width: 100 },
      {
        field: 'ruleCode',
        headerName: t('ledger.rule'),
        width: 230,
        valueFormatter: (p) => (p.value as string | null) ?? t('ledger.manual'),
      },
      { field: 'description', headerName: t('ledger.descriptionColumn'), flex: 1, minWidth: 220 },
      {
        field: 'totalDebit',
        headerName: t('ledger.amount'),
        width: 140,
        type: 'rightAligned',
        valueFormatter: (p) => amount(p.value as number),
      },
      { field: 'currency', headerName: t('trades.form.currency'), width: 90 },
      {
        field: 'status',
        headerName: t('allocation.status'),
        width: 130,
        cellRenderer: (p: ICellRendererParams<Entry>) => p.data && <EntryStatusBadge status={p.data.status} />,
      },
      { field: 'sourceRef', headerName: t('ledger.source'), width: 150 },
    ],
    [t],
  );
  const verified = verify.data as { ok?: boolean } | undefined;
  return (
    <Stack>
      <Group align="flex-end" justify="space-between">
        <Group align="flex-end">
          <TextInput
            type="month"
            label={t('ledger.period')}
            value={period}
            onChange={(e) => setFilter('period', e.currentTarget.value)}
            w={170}
          />
          <Select
            label={t('ledger.account')}
            data={(accounts.data ?? []).map((a) => ({ value: a.code, label: `${a.code} · ${a.name}` }))}
            value={accountCode}
            onChange={(value) => setFilter('account', value)}
            searchable
            clearable
            w={300}
            data-testid="account-filter"
          />
          <TextInput
            label={t('ledger.source')}
            value={sourceRef}
            onChange={(e) => setFilter('source', e.currentTarget.value)}
            w={220}
          />
        </Group>
        <Group gap="xs">
          <Button
            variant="default"
            leftSection={<IconShieldCheck size={16} />}
            loading={verify.isFetching}
            onClick={() => void verify.refetch()}
          >
            {t('ledger.verify')}
          </Button>
          <Can op="ledger_postManual">
            <Button leftSection={<IconPlus size={16} />} onClick={() => setManual(true)}>
              {t('ledger.manualEntry')}
            </Button>
          </Can>
        </Group>
      </Group>
      {verified && (
        <Alert color={verified.ok ? 'teal' : 'red'} data-testid="verify-result">
          {verified.ok ? t('ledger.chainIntact') : t('ledger.chainBroken')}
        </Alert>
      )}
      <QueryState isPending={entries.isPending} error={entries.error}>
        <DataGrid<Entry>
          rowData={rows}
          columnDefs={columns}
          rowId={(row) => row.id}
          height={480}
          onRowClicked={(event) => {
            if (event.data) setOpen(event.data.id);
          }}
          overlayNoRowsTemplate={t('common.empty')}
        />
        <Group justify="space-between">
          <Text size="sm" c="dimmed">
            {t('ledger.shown', { count: rows.length })}
          </Text>
          {entries.hasNextPage && (
            <Button variant="default" loading={entries.isFetchingNextPage} onClick={() => void entries.fetchNextPage()}>
              {t('ledger.more')}
            </Button>
          )}
        </Group>
      </QueryState>
      {open && <EntryDrawer entryId={open} onClose={() => setOpen(null)} onOpen={setOpen} />}
      {manual && (
        <ManualJournalModal entityId={entityId} accounts={accounts.data ?? []} onClose={() => setManual(false)} />
      )}
    </Stack>
  );
}
