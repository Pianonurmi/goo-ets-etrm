import {
  Alert,
  Anchor,
  Button,
  Drawer,
  Group,
  Select,
  SimpleGrid,
  Stack,
  Table,
  Tabs,
  Text,
  TextInput,
  Textarea,
} from '@mantine/core';
import { IconPencil, IconPlus, IconRotate2, IconShieldCheck } from '@tabler/icons-react';
import type { ColDef, ICellRendererParams } from 'ag-grid-community';
import dayjs from 'dayjs';
import { useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useSearchParams } from 'react-router';
import { $api } from '@/shared/api/client';
import type { Schema } from '@/shared/api/types';
import { Can } from '@/shared/auth/Can';
import { KpiCard } from '@/shared/components/KpiCard';
import { notifySuccess } from '@/shared/components/notify';
import { PageHeader } from '@/shared/components/PageHeader';
import { ProblemAlert } from '@/shared/components/ProblemAlert';
import { QueryState } from '@/shared/components/QueryState';
import { StatusBadge, statusLabel } from '@/shared/components/StatusBadge';
import { useEntity } from '@/shared/context/EntityContext';
import { formatDate, formatDateTime, formatDecimal } from '@/shared/format/format';
import { DataGrid } from '@/shared/grid/DataGrid';
import { useAccounts, useInvalidateLedger } from './hooks';
import { ManualJournalModal } from './ManualJournalModal';
import { RuleChangeModal } from './RuleChangeModal';

type Entry = Schema<'JournalEntryView'>;
type TrialBalanceRow = Schema<'TrialBalanceRow'>;
type Account = Schema<'GlAccountView'>;
type Rule = Schema<'PostingRuleView'>;

const PAGE = 200;

const amount = (value: number) => formatDecimal(value, 2, 2);

/** Posted is done, a reversed entry is history, a reversal stands out as a correction. */
const ENTRY_TONE: Record<Entry['status'], string> = {
  POSTED: 'COMPLETED',
  REVERSED: 'SUPERSEDED',
  REVERSAL: 'WARNING',
};

export function EntryStatusBadge({ status }: { status: Entry['status'] }) {
  return <StatusBadge status={ENTRY_TONE[status]} label={statusLabel(status)} />;
}

function ActiveBadge({ active }: { active: boolean | undefined }) {
  return <StatusBadge status={active ? 'ACTIVE' : 'INACTIVE'} />;
}

/** The open period and what blocks closing it, posting rules waiting for approval, and the chart of accounts. */
function LedgerKpis({ entityId }: { entityId: string }) {
  const { t } = useTranslation();
  const summary = $api.useQuery('get', '/api/v1/home/summary', { params: { query: { legalEntityId: entityId } } });
  const changes = $api.useQuery('get', '/api/v1/ledger/rules/changes', { params: { query: { status: 'PENDING' } } });
  const accounts = useAccounts(entityId);
  const ledger = summary.data?.ledger;
  const pending = changes.data?.length ?? 0;
  return (
    <SimpleGrid cols={{ base: 1, xs: 2, lg: 3 }} spacing="md" data-testid="ledger-kpis">
      <KpiCard
        label={t('ledger.kpi.period')}
        value={ledger?.period ?? '—'}
        loading={summary.isPending}
        trend={
          ledger && `${statusLabel(ledger.status)} · ${t('ledger.kpi.blockers', { count: ledger.blockers.length })}`
        }
        trendTone={ledger && ledger.blockers.length > 0 ? 'warning' : 'neutral'}
        to="/month-end"
      />
      <KpiCard
        label={t('ledger.kpi.pendingRules')}
        value={pending}
        loading={changes.isPending}
        trend={pending > 0 ? t('ledger.kpi.pendingRulesHint') : t('ledger.kpi.noPendingRules')}
        trendTone={pending > 0 ? 'warning' : 'neutral'}
        to="/ledger?tab=rules"
      />
      <KpiCard
        label={t('ledger.kpi.accounts')}
        value={(accounts.data ?? []).filter((account) => account.active).length}
        loading={accounts.isPending}
        trend={t('ledger.kpi.accountsHint')}
        to="/ledger?tab=accounts"
      />
    </SimpleGrid>
  );
}

function EntryDrawer({
  entryId,
  onClose,
  onOpen,
}: {
  entryId: string;
  onClose: () => void;
  onOpen: (id: string) => void;
}) {
  const { t } = useTranslation();
  const invalidate = useInvalidateLedger();
  const [reason, setReason] = useState('');
  const entry = $api.useQuery('get', '/api/v1/ledger/entries/{id}', { params: { path: { id: entryId } } });
  const reverse = $api.useMutation('post', '/api/v1/ledger/entries/{id}/reverse', {
    meta: { notify: false },
    onSuccess: (reversal) => {
      notifySuccess(t('ledger.reversed', { number: reversal.entryNumber }));
      setReason('');
      void invalidate();
    },
  });
  const e = entry.data;
  return (
    <Drawer
      opened
      onClose={onClose}
      position="right"
      size="xl"
      title={e ? t('ledger.entryTitle', { number: e.entryNumber }) : ''}
    >
      <QueryState isPending={entry.isPending} error={entry.error}>
        {e && (
          <Stack>
            <Group gap="xs">
              <EntryStatusBadge status={e.status} />
              <Text size="sm">{e.description}</Text>
            </Group>
            <Table withRowBorders={false} fz="sm">
              <Table.Tbody>
                <Table.Tr>
                  <Table.Td c="dimmed">{t('ledger.postingDate')}</Table.Td>
                  <Table.Td>
                    {formatDate(e.postingDate)} · {e.period}
                  </Table.Td>
                </Table.Tr>
                <Table.Tr>
                  <Table.Td c="dimmed">{t('ledger.rule')}</Table.Td>
                  <Table.Td>
                    {e.ruleCode ?? t('ledger.manual')} · {e.eventType}
                  </Table.Td>
                </Table.Tr>
                <Table.Tr>
                  <Table.Td c="dimmed">{t('ledger.source')}</Table.Td>
                  <Table.Td>{[e.sourceType, e.sourceRef].filter(Boolean).join(' ') || '–'}</Table.Td>
                </Table.Tr>
                <Table.Tr>
                  <Table.Td c="dimmed">{t('ledger.createdBy')}</Table.Td>
                  <Table.Td>
                    {e.createdBy} · {formatDateTime(e.createdAt)}
                  </Table.Td>
                </Table.Tr>
                {(e.reversalOf ?? e.reversedBy) && (
                  <Table.Tr>
                    <Table.Td c="dimmed">{e.reversalOf ? t('ledger.reversalOf') : t('ledger.reversedBy')}</Table.Td>
                    <Table.Td>
                      <Anchor size="sm" onClick={() => onOpen((e.reversalOf ?? e.reversedBy) as string)}>
                        {t('ledger.openEntry')}
                      </Anchor>
                    </Table.Td>
                  </Table.Tr>
                )}
                <Table.Tr>
                  <Table.Td c="dimmed">{t('ledger.hash')}</Table.Td>
                  <Table.Td>
                    <Text size="xs" ff="monospace">
                      {e.hash.slice(0, 16)}…
                    </Text>
                  </Table.Td>
                </Table.Tr>
              </Table.Tbody>
            </Table>
            <Table withTableBorder striped data-testid="entry-lines">
              <Table.Thead>
                <Table.Tr>
                  <Table.Th>{t('ledger.account')}</Table.Th>
                  <Table.Th ta="right">{t('ledger.debit')}</Table.Th>
                  <Table.Th ta="right">{t('ledger.credit')}</Table.Th>
                  <Table.Th>{t('ledger.memo')}</Table.Th>
                </Table.Tr>
              </Table.Thead>
              <Table.Tbody>
                {e.lines.map((line) => (
                  <Table.Tr key={line.lineNo}>
                    <Table.Td>
                      {line.accountCode} · {line.accountName}
                    </Table.Td>
                    <Table.Td ta="right">{line.debit > 0 ? amount(line.debit) : ''}</Table.Td>
                    <Table.Td ta="right">{line.credit > 0 ? amount(line.credit) : ''}</Table.Td>
                    <Table.Td>
                      <Text size="xs">
                        {[line.memo, ...Object.entries(line.dimensions).map(([k, v]) => `${k}=${v}`)]
                          .filter(Boolean)
                          .join(' · ')}
                      </Text>
                    </Table.Td>
                  </Table.Tr>
                ))}
                <Table.Tr fw={600}>
                  <Table.Td>{e.currency}</Table.Td>
                  <Table.Td ta="right">{amount(e.totalDebit)}</Table.Td>
                  <Table.Td ta="right">{amount(e.totalCredit)}</Table.Td>
                  <Table.Td />
                </Table.Tr>
              </Table.Tbody>
            </Table>
            {e.status === 'POSTED' && (
              <Can op="ledger_reverse">
                <Stack gap="xs">
                  <ProblemAlert error={reverse.error} />
                  <Textarea
                    label={t('ledger.reverseReason')}
                    value={reason}
                    onChange={(event) => setReason(event.currentTarget.value)}
                    autosize
                    minRows={2}
                  />
                  <Button
                    color="orange"
                    variant="light"
                    leftSection={<IconRotate2 size={16} />}
                    disabled={!reason.trim()}
                    loading={reverse.isPending}
                    onClick={() => reverse.mutate({ params: { path: { id: e.id } }, body: { reason } })}
                  >
                    {t('ledger.reverse')}
                  </Button>
                </Stack>
              </Can>
            )}
          </Stack>
        )}
      </QueryState>
    </Drawer>
  );
}

function EntriesTab({ entityId }: { entityId: string }) {
  const { t } = useTranslation();
  const [params, setParams] = useSearchParams();
  const period = params.get('period') ?? '';
  const accountCode = params.get('account');
  const sourceRef = params.get('source') ?? '';
  const [open, setOpen] = useState<string | null>(null);
  const [manual, setManual] = useState(false);
  const accounts = useAccounts(entityId);
  const setFilter = (key: string, value: string | null) => {
    const next = new URLSearchParams(params);
    if (value) next.set(key, value);
    else next.delete(key);
    setParams(next, { replace: true });
  };
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

function TrialBalanceTab({ entityId }: { entityId: string }) {
  const { t } = useTranslation();
  const [period, setPeriod] = useState(dayjs().format('YYYY-MM'));
  const balance = $api.useQuery(
    'get',
    '/api/v1/ledger/trial-balance',
    { params: { query: { legalEntityId: entityId, period } } },
    { enabled: /^\d{4}-\d{2}$/.test(period) },
  );
  const columns = useMemo<ColDef<TrialBalanceRow>[]>(
    () => [
      { field: 'accountCode', headerName: t('ledger.account'), width: 120 },
      { field: 'accountName', headerName: t('ledger.accountName'), flex: 1, minWidth: 220 },
      {
        field: 'type',
        headerName: t('ledger.type'),
        width: 120,
        valueFormatter: (p) => statusLabel(String(p.value)),
      },
      {
        field: 'debit',
        headerName: t('ledger.debit'),
        width: 150,
        type: 'rightAligned',
        valueFormatter: (p) => amount(p.value as number),
      },
      {
        field: 'credit',
        headerName: t('ledger.credit'),
        width: 150,
        type: 'rightAligned',
        valueFormatter: (p) => amount(p.value as number),
      },
      {
        field: 'balance',
        headerName: t('ledger.balance'),
        width: 160,
        type: 'rightAligned',
        valueFormatter: (p) => amount(p.value as number),
      },
      { field: 'glMapping', headerName: t('ledger.glMapping'), width: 130 },
    ],
    [t],
  );
  return (
    <Stack>
      <TextInput
        type="month"
        label={t('ledger.period')}
        value={period}
        onChange={(e) => setPeriod(e.currentTarget.value)}
        w={170}
      />
      <QueryState isPending={balance.isPending} error={balance.error}>
        <DataGrid<TrialBalanceRow>
          rowData={balance.data ?? []}
          columnDefs={columns}
          rowId={(row) => row.accountCode}
          height={520}
          overlayNoRowsTemplate={t('common.empty')}
        />
      </QueryState>
    </Stack>
  );
}

function AccountsTab({ entityId }: { entityId: string }) {
  const { t } = useTranslation();
  const accounts = useAccounts(entityId);
  const columns = useMemo<ColDef<Account>[]>(
    () => [
      { field: 'code', headerName: t('ledger.account'), width: 120, sort: 'asc' },
      { field: 'name', headerName: t('ledger.accountName'), flex: 1, minWidth: 240 },
      {
        field: 'type',
        headerName: t('ledger.type'),
        width: 130,
        valueFormatter: (p) => statusLabel(String(p.value)),
      },
      { field: 'glMapping', headerName: t('ledger.glMapping'), width: 140 },
      {
        field: 'active',
        headerName: t('marketData.active'),
        width: 120,
        cellRenderer: (p: ICellRendererParams<Account>) => p.data && <ActiveBadge active={p.data.active} />,
      },
    ],
    [t],
  );
  return (
    <QueryState isPending={accounts.isPending} error={accounts.error}>
      <DataGrid<Account> rowData={accounts.data ?? []} columnDefs={columns} rowId={(row) => row.id} height={520} />
    </QueryState>
  );
}

function RuleHistory({ rule, onPropose, onClose }: { rule: Rule; onPropose: () => void; onClose: () => void }) {
  const { t } = useTranslation();
  const ruleCode = rule.ruleCode;
  const history = $api.useQuery('get', '/api/v1/ledger/rules/{ruleCode}/history', { params: { path: { ruleCode } } });
  return (
    <Drawer opened onClose={onClose} position="right" size="lg" title={t('ledger.ruleHistory', { rule: ruleCode })}>
      <Can op="ledger_upsertRule">
        <Button
          variant="light"
          mb="md"
          leftSection={<IconPencil size={16} />}
          onClick={onPropose}
          data-testid="rule-propose-change"
        >
          {t('ledger.proposeChange')}
        </Button>
      </Can>
      <QueryState isPending={history.isPending} error={history.error}>
        {(history.data ?? []).length === 0 ? (
          <Text size="sm" c="dimmed">
            {t('ledger.noHistory')}
          </Text>
        ) : (
          <Stack>
            {(history.data ?? []).map((version) => (
              <Stack key={version.version} gap={4}>
                <Text fw={600} size="sm">
                  {t('ledger.ruleVersion', { version: version.version, at: formatDateTime(version.recordedAt) })}
                </Text>
                <Text size="xs" c="dimmed">
                  {version.rule.eventType} · {t('ledger.priority')} {version.rule.priority ?? '–'} ·{' '}
                  {version.rule.effectiveFrom ?? ''}
                </Text>
                {version.rule.lines.map((line, index) => (
                  <Text size="xs" key={index} ff="monospace">
                    {line.side} {line.account} {line.amount}
                  </Text>
                ))}
              </Stack>
            ))}
          </Stack>
        )}
      </QueryState>
    </Drawer>
  );
}

function RulesTab({ entityId }: { entityId: string }) {
  const { t } = useTranslation();
  const [open, setOpen] = useState<Rule | null>(null);
  const [proposing, setProposing] = useState<{ rule?: Rule } | null>(null);
  const rules = $api.useQuery('get', '/api/v1/ledger/rules', { params: { query: { legalEntityId: entityId } } });
  const changes = $api.useQuery('get', '/api/v1/ledger/rules/changes', { params: { query: { status: 'PENDING' } } });
  const eventTypes = useMemo(() => [...new Set((rules.data ?? []).map((rule) => rule.eventType))].sort(), [rules.data]);
  const columns = useMemo<ColDef<Rule>[]>(
    () => [
      { field: 'ruleCode', headerName: t('ledger.rule'), width: 260, sort: 'asc' },
      { field: 'eventType', headerName: t('ledger.eventType'), width: 200 },
      { field: 'priority', headerName: t('ledger.priority'), width: 100 },
      { field: 'version', headerName: t('trades.version'), width: 100 },
      { field: 'effectiveFrom', headerName: t('risk.validFrom'), width: 130 },
      {
        colId: 'lines',
        headerName: t('ledger.lines'),
        flex: 1,
        minWidth: 240,
        valueGetter: (p) =>
          p.data?.lines.map((line) => `${line.side === 'DEBIT' ? 'Dr' : 'Cr'} ${line.account}`).join(', '),
      },
      {
        field: 'active',
        headerName: t('marketData.active'),
        width: 120,
        cellRenderer: (p: ICellRendererParams<Rule>) => p.data && <ActiveBadge active={p.data.active} />,
      },
    ],
    [t],
  );
  return (
    <Stack>
      <Group justify="flex-end">
        <Can op="ledger_upsertRule">
          <Button leftSection={<IconPlus size={16} />} onClick={() => setProposing({})} data-testid="rule-new">
            {t('ledger.newRule')}
          </Button>
        </Can>
      </Group>
      {(changes.data ?? []).length > 0 && (
        <Alert color="yellow" data-testid="pending-rule-changes">
          {t('ledger.pendingRuleChanges', { codes: (changes.data ?? []).map((c) => c.ruleCode).join(', ') })}
        </Alert>
      )}
      <QueryState isPending={rules.isPending} error={rules.error}>
        <DataGrid<Rule>
          rowData={rules.data ?? []}
          columnDefs={columns}
          rowId={(row) => row.id}
          height={520}
          onRowClicked={(event) => {
            if (event.data) setOpen(event.data);
          }}
        />
      </QueryState>
      {open && (
        <RuleHistory
          rule={open}
          onPropose={() => {
            setProposing({ rule: open });
            setOpen(null);
          }}
          onClose={() => setOpen(null)}
        />
      )}
      {proposing && (
        <RuleChangeModal
          entityId={entityId}
          rule={proposing.rule}
          eventTypes={eventTypes}
          onClose={() => setProposing(null)}
        />
      )}
    </Stack>
  );
}

const TABS = ['entries', 'trial-balance', 'accounts', 'rules'] as const;

/** The journal of the selected entity: entries, trial balance, chart of accounts and posting rules. */
export function LedgerPage() {
  const { t } = useTranslation();
  const { entity } = useEntity();
  const [params, setParams] = useSearchParams();
  const requested = params.get('tab');
  const tab = TABS.find((value) => value === requested) ?? 'entries';
  return (
    <Stack>
      <PageHeader title={t('ledger.title')} description={t('ledger.description', { entity: entity?.code ?? '' })} />
      {!entity ? (
        <Alert color="gray">{t('header.noEntity')}</Alert>
      ) : (
        <>
          <LedgerKpis entityId={entity.id} />
          <Tabs
            value={tab}
            onChange={(value) => setParams(value && value !== 'entries' ? { tab: value } : {}, { replace: true })}
            keepMounted={false}
          >
            <Tabs.List>
              <Tabs.Tab value="entries">{t('ledger.tabs.entries')}</Tabs.Tab>
              <Tabs.Tab value="trial-balance">{t('ledger.tabs.trialBalance')}</Tabs.Tab>
              <Tabs.Tab value="accounts">{t('ledger.tabs.accounts')}</Tabs.Tab>
              <Tabs.Tab value="rules">{t('ledger.tabs.rules')}</Tabs.Tab>
            </Tabs.List>
            <Tabs.Panel value="entries" pt="md">
              <EntriesTab entityId={entity.id} />
            </Tabs.Panel>
            <Tabs.Panel value="trial-balance" pt="md">
              <TrialBalanceTab entityId={entity.id} />
            </Tabs.Panel>
            <Tabs.Panel value="accounts" pt="md">
              <AccountsTab entityId={entity.id} />
            </Tabs.Panel>
            <Tabs.Panel value="rules" pt="md">
              <RulesTab entityId={entity.id} />
            </Tabs.Panel>
          </Tabs>
        </>
      )}
    </Stack>
  );
}
