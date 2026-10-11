import {
  Alert,
  Anchor,
  Badge,
  Button,
  Card,
  FileButton,
  Group,
  Modal,
  Select,
  SimpleGrid,
  Stack,
  Table,
  Tabs,
  Text,
  TextInput,
  Textarea,
} from '@mantine/core';
import { IconFileUpload, IconTransfer } from '@tabler/icons-react';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import type { ColDef, ICellRendererParams } from 'ag-grid-community';
import dayjs from 'dayjs';
import { useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link, useNavigate, useSearchParams } from 'react-router';
import { $api } from '@/shared/api/client';
import type { Schema } from '@/shared/api/types';
import { uploadText } from '@/shared/api/upload';
import { Can } from '@/shared/auth/Can';
import { PageHeader } from '@/shared/components/PageHeader';
import { ProblemAlert } from '@/shared/components/ProblemAlert';
import { QueryState } from '@/shared/components/QueryState';
import { useEntity } from '@/shared/context/EntityContext';
import { formatDate } from '@/shared/format/format';
import { DataGrid } from '@/shared/grid/DataGrid';
import { useRegistries } from '@/features/registry/TransferModal';
import {
  BREAK_STATUS_COLORS,
  BREAK_TYPES,
  CLOSED_STATUSES,
  STATEMENT_STATUS_COLORS,
  ageInDays,
  breakSubject,
  type Break,
  type BreakStatus,
  type Statement,
} from './model/breaks';

type TransactionReport = Schema<'TransactionImportReport'>;
const TABS = ['breaks', 'statements'] as const;

export function useInvalidateReconciliation() {
  const queryClient = useQueryClient();
  return () =>
    queryClient.invalidateQueries({
      predicate: (q) =>
        typeof q.queryKey[1] === 'string' &&
        ['/api/v1/inventory', '/api/v1/registry', '/api/v1/workflow'].some((prefix) =>
          (q.queryKey[1] as string).startsWith(prefix),
        ),
    });
}

/** The selected entity's registry accounts, for choosing a statement's account. */
export function useEntityAccounts(entityId: string | undefined) {
  return $api.useQuery(
    'get',
    '/api/v1/reference/registry-accounts',
    { params: { query: { holderType: 'LEGAL_ENTITY', holderId: entityId ?? '' } } },
    { enabled: Boolean(entityId), staleTime: 5 * 60_000 },
  );
}

export function BreakStatusBadge({ status, testId }: { status: BreakStatus; testId?: string }) {
  return (
    <Badge color={BREAK_STATUS_COLORS[status]} variant="light" data-testid={testId}>
      {status.replace(/_/g, ' ')}
    </Badge>
  );
}

function BreaksTab({ entityId }: { entityId: string }) {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const [params, setParams] = useSearchParams();
  const accounts = useEntityAccounts(entityId);
  const closed = params.get('closed') === 'true';
  const type = BREAK_TYPES.find((value) => value === params.get('type')) ?? null;
  const set = (key: string, value: string | null) => {
    const next = new URLSearchParams(params);
    if (value) next.set(key, value);
    else next.delete(key);
    setParams(next, { replace: true });
  };
  const breaks = $api.useQuery('get', '/api/v1/inventory/breaks', {
    params: { query: { status: closed ? CLOSED_STATUSES : undefined, type: type ?? undefined } },
  });
  const ageing = $api.useQuery('get', '/api/v1/inventory/breaks/ageing', {});
  const accountNumbers = useMemo(
    () => new Map((accounts.data ?? []).map((account) => [account.id, account.accountNumber])),
    [accounts.data],
  );
  const today = dayjs().format('YYYY-MM-DD');
  const rows = useMemo(
    () => (breaks.data ?? []).filter((brk) => brk.registryAccountId && accountNumbers.has(brk.registryAccountId)),
    [breaks.data, accountNumbers],
  );
  const columns = useMemo<ColDef<Break>[]>(
    () => [
      {
        field: 'type',
        headerName: t('reconciliation.type'),
        width: 210,
        valueFormatter: (p) => t(`reconciliation.types.${String(p.value)}`),
      },
      {
        field: 'status',
        headerName: t('allocation.status'),
        width: 170,
        cellRenderer: (p: ICellRendererParams<Break>) => p.data && <BreakStatusBadge status={p.data.status} />,
      },
      {
        colId: 'subject',
        headerName: t('reconciliation.subject'),
        flex: 1,
        minWidth: 220,
        valueGetter: (p) => (p.data ? breakSubject(p.data) : ''),
      },
      {
        colId: 'account',
        headerName: t('reconciliation.account'),
        width: 160,
        valueGetter: (p) => (p.data?.registryAccountId ? accountNumbers.get(p.data.registryAccountId) : ''),
      },
      {
        colId: 'age',
        headerName: t('reconciliation.ageDays'),
        width: 110,
        type: 'rightAligned',
        valueGetter: (p) => (p.data ? ageInDays(p.data, today) : null),
      },
      { field: 'lastSeenOn', headerName: t('reconciliation.lastSeen'), width: 130 },
      { field: 'assignee', headerName: t('reconciliation.assignee'), width: 130 },
      { field: 'resolution', headerName: t('reconciliation.resolution'), width: 260, tooltipField: 'resolution' },
    ],
    [t, accountNumbers, today],
  );
  return (
    <Stack>
      {ageing.data && (
        <SimpleGrid cols={{ base: 2, md: 4 }} data-testid="break-ageing">
          {(
            [
              ['upToOneDay', ageing.data.upToOneDay],
              ['upToOneWeek', ageing.data.upToOneWeek],
              ['upToOneMonth', ageing.data.upToOneMonth],
              ['older', ageing.data.older],
            ] as const
          ).map(([key, count]) => (
            <Card withBorder key={key} padding="sm">
              <Text size="xs" c="dimmed">
                {t(`reconciliation.ageing.${key}`)}
              </Text>
              <Text size="xl" fw={600} c={key === 'older' && count > 0 ? 'red' : undefined}>
                {count}
              </Text>
            </Card>
          ))}
        </SimpleGrid>
      )}
      <Group align="flex-end">
        <Select
          label={t('reconciliation.show')}
          data={[
            { value: 'open', label: t('reconciliation.openBreaks') },
            { value: 'closed', label: t('reconciliation.closedBreaks') },
          ]}
          value={closed ? 'closed' : 'open'}
          onChange={(value) => set('closed', value === 'closed' ? 'true' : null)}
          allowDeselect={false}
          w={200}
        />
        <Select
          label={t('reconciliation.type')}
          placeholder={t('reconciliation.anyType')}
          data={BREAK_TYPES.map((value) => ({ value, label: t(`reconciliation.types.${value}`) }))}
          value={type}
          onChange={(value) => set('type', value)}
          clearable
          w={240}
        />
      </Group>
      <QueryState isPending={breaks.isPending} error={breaks.error}>
        <DataGrid<Break>
          rowData={rows}
          columnDefs={columns}
          rowId={(row) => row.id}
          height={440}
          onRowClicked={(event) => event.data && void navigate(`/reconciliation/breaks/${event.data.id}`)}
          rowStyle={{ cursor: 'pointer' }}
        />
      </QueryState>
    </Stack>
  );
}

/** A holdings export of one account, parsed with the registry's format profile and reconciled at once. */
function HoldingsUploadModal({ accountId, onClose }: { accountId: string; onClose: () => void }) {
  const { t } = useTranslation();
  const invalidate = useInvalidateReconciliation();
  const [text, setText] = useState('');
  const [source, setSource] = useState('upload.csv');
  const [statementDate, setStatementDate] = useState(dayjs().format('YYYY-MM-DD'));
  const [result, setResult] = useState<Statement | null>(null);
  const upload = useMutation({
    mutationFn: () =>
      uploadText<Statement>(
        '/api/v1/registry/statements/csv',
        { registryAccountId: accountId, statementDate, source },
        text,
      ),
    meta: { notify: false },
    onSuccess: (statement) => {
      setResult(statement);
      void invalidate();
    },
  });
  return (
    <Modal opened onClose={onClose} title={t('reconciliation.uploadHoldings')} size="lg">
      <Stack>
        <ProblemAlert error={upload.error} />
        <Text size="sm" c="dimmed">
          {t('reconciliation.uploadHoldingsHint')}
        </Text>
        <TextInput
          type="date"
          label={t('reconciliation.statementDate')}
          value={statementDate}
          onChange={(e) => setStatementDate(e.currentTarget.value)}
          w={200}
        />
        <Textarea
          label={t('reconciliation.statementContent')}
          value={text}
          onChange={(e) => setText(e.currentTarget.value)}
          autosize
          minRows={6}
          maxRows={14}
          styles={{ input: { fontFamily: 'monospace' } }}
          data-testid="holdings-csv"
        />
        <Group justify="space-between">
          <FileButton
            accept=".csv,text/csv,text/plain"
            onChange={(file) => {
              if (!file) return;
              setSource(file.name);
              void file.text().then(setText);
            }}
          >
            {(props) => (
              <Button variant="subtle" {...props}>
                {t('settlement.chooseFile')}
              </Button>
            )}
          </FileButton>
          <Button
            disabled={!text.trim() || !statementDate}
            loading={upload.isPending}
            onClick={() => upload.mutate()}
            data-testid="holdings-upload"
          >
            {t('reconciliation.uploadAndReconcile')}
          </Button>
        </Group>
        {result && (
          <Alert color={result.status === 'BREAKS' ? 'orange' : 'teal'} data-testid="holdings-result">
            {t('reconciliation.statementResult', { lines: result.lineCount, breaks: result.breaks.length })}
          </Alert>
        )}
      </Stack>
    </Modal>
  );
}

/** A registry-wide transaction export: matching open instructions are confirmed or failed. */
function TransactionsUploadModal({ onClose }: { onClose: () => void }) {
  const { t } = useTranslation();
  const invalidate = useInvalidateReconciliation();
  const registries = useRegistries();
  const [registryCode, setRegistryCode] = useState<string | null>(null);
  const [text, setText] = useState('');
  const [report, setReport] = useState<TransactionReport | null>(null);
  const upload = useMutation({
    mutationFn: () =>
      uploadText<TransactionReport>(
        '/api/v1/registry/statements/transactions',
        { registryCode: registryCode ?? '' },
        text,
      ),
    meta: { notify: false },
    onSuccess: (answer) => {
      setReport(answer);
      void invalidate();
    },
  });
  return (
    <Modal opened onClose={onClose} title={t('reconciliation.uploadTransactions')} size="lg">
      <Stack>
        <ProblemAlert error={upload.error} />
        <Text size="sm" c="dimmed">
          {t('reconciliation.uploadTransactionsHint')}
        </Text>
        <Select
          label={t('registry.registry')}
          data={(registries.data ?? []).map((registry) => ({ value: registry.code, label: registry.code }))}
          value={registryCode}
          onChange={setRegistryCode}
          w={240}
        />
        <Textarea
          label={t('reconciliation.statementContent')}
          value={text}
          onChange={(e) => setText(e.currentTarget.value)}
          autosize
          minRows={6}
          maxRows={14}
          styles={{ input: { fontFamily: 'monospace' } }}
        />
        <Button disabled={!registryCode || !text.trim()} loading={upload.isPending} onClick={() => upload.mutate()}>
          {t('reconciliation.import')}
        </Button>
        {report && (
          <Stack gap="xs" data-testid="transactions-result">
            <Text size="sm">
              {t('reconciliation.transactionResult', {
                lines: report.lines,
                confirmed: report.confirmed.length,
                failed: report.failed.length,
                final: report.alreadyFinal,
                unmatched: report.unmatched.length,
              })}
            </Text>
            {report.unmatched.length > 0 && (
              <Table>
                <Table.Thead>
                  <Table.Tr>
                    <Table.Th>{t('reconciliation.line')}</Table.Th>
                    <Table.Th>{t('registry.externalRef')}</Table.Th>
                    <Table.Th>{t('registry.key')}</Table.Th>
                    <Table.Th>{t('allocation.status')}</Table.Th>
                  </Table.Tr>
                </Table.Thead>
                <Table.Tbody>
                  {report.unmatched.map((line) => (
                    <Table.Tr key={line.lineNo}>
                      <Table.Td>{line.lineNo}</Table.Td>
                      <Table.Td>{line.externalRef}</Table.Td>
                      <Table.Td>{line.instructionKey}</Table.Td>
                      <Table.Td>{line.status}</Table.Td>
                    </Table.Tr>
                  ))}
                </Table.Tbody>
              </Table>
            )}
          </Stack>
        )}
      </Stack>
    </Modal>
  );
}

function StatementsTab({ entityId }: { entityId: string }) {
  const { t } = useTranslation();
  const [params, setParams] = useSearchParams();
  const accounts = useEntityAccounts(entityId);
  const accountId = params.get('account') ?? accounts.data?.[0]?.id ?? null;
  const [uploading, setUploading] = useState<'holdings' | 'transactions' | null>(null);
  const [open, setOpen] = useState<Statement | null>(null);
  const statements = $api.useQuery(
    'get',
    '/api/v1/inventory/statements',
    { params: { query: { registryAccountId: accountId ?? '' } } },
    { enabled: Boolean(accountId) },
  );
  const columns = useMemo<ColDef<Statement>[]>(
    () => [
      { field: 'statementDate', headerName: t('reconciliation.statementDate'), width: 140, sort: 'desc' },
      {
        field: 'kind',
        headerName: t('registry.kind'),
        width: 150,
        valueFormatter: (p) => t(`reconciliation.statementKinds.${String(p.value)}`),
      },
      { field: 'source', headerName: t('reconciliation.source'), flex: 1, minWidth: 180 },
      { field: 'lineCount', headerName: t('reconciliation.lines'), width: 100, type: 'rightAligned' },
      {
        colId: 'breaks',
        headerName: t('reconciliation.breaks'),
        width: 100,
        type: 'rightAligned',
        valueGetter: (p) => p.data?.breaks.length ?? 0,
      },
      {
        field: 'status',
        headerName: t('allocation.status'),
        width: 140,
        cellRenderer: (p: ICellRendererParams<Statement>) =>
          p.data && (
            <Badge color={STATEMENT_STATUS_COLORS[p.data.status]} variant="light">
              {p.data.status}
            </Badge>
          ),
      },
    ],
    [t],
  );
  return (
    <Stack>
      <Group justify="space-between" align="flex-end">
        <Select
          label={t('reconciliation.account')}
          data={(accounts.data ?? []).map((account) => ({
            value: account.id,
            label: `${account.registryCode} · ${account.accountNumber}`,
          }))}
          value={accountId}
          onChange={(value) => {
            const next = new URLSearchParams(params);
            if (value) next.set('account', value);
            setParams(next, { replace: true });
          }}
          allowDeselect={false}
          w={320}
        />
        <Group gap="xs">
          <Can op="registry_importTransactions">
            <Button
              variant="light"
              leftSection={<IconTransfer size={16} />}
              onClick={() => setUploading('transactions')}
            >
              {t('reconciliation.uploadTransactions')}
            </Button>
          </Can>
          {accountId && (
            <Can op="registry_importHoldings">
              <Button
                leftSection={<IconFileUpload size={16} />}
                onClick={() => setUploading('holdings')}
                data-testid="upload-holdings"
              >
                {t('reconciliation.uploadHoldings')}
              </Button>
            </Can>
          )}
        </Group>
      </Group>
      <QueryState isPending={statements.isPending && Boolean(accountId)} error={statements.error}>
        <DataGrid<Statement>
          rowData={statements.data ?? []}
          columnDefs={columns}
          rowId={(row) => row.id}
          height={400}
          onRowClicked={(event) => setOpen(event.data ?? null)}
          rowStyle={{ cursor: 'pointer' }}
        />
      </QueryState>
      {open && (
        <Modal
          opened
          onClose={() => setOpen(null)}
          title={t('reconciliation.statementOf', { date: formatDate(open.statementDate) })}
          size="lg"
        >
          {open.breaks.length === 0 ? (
            <Text size="sm">{t('reconciliation.noBreaks')}</Text>
          ) : (
            <Table>
              <Table.Tbody>
                {open.breaks.map((brk) => (
                  <Table.Tr key={brk.id}>
                    <Table.Td>
                      <Anchor component={Link} to={`/reconciliation/breaks/${brk.id}`} size="sm">
                        {t(`reconciliation.types.${brk.type}`)}
                      </Anchor>
                    </Table.Td>
                    <Table.Td>{breakSubject(brk)}</Table.Td>
                    <Table.Td>
                      <BreakStatusBadge status={brk.status} />
                    </Table.Td>
                  </Table.Tr>
                ))}
              </Table.Tbody>
            </Table>
          )}
        </Modal>
      )}
      {uploading === 'holdings' && accountId && (
        <HoldingsUploadModal accountId={accountId} onClose={() => setUploading(null)} />
      )}
      {uploading === 'transactions' && <TransactionsUploadModal onClose={() => setUploading(null)} />}
    </Stack>
  );
}

/** Registry statements against our books: breaks to work, and statement uploads. */
export function ReconciliationPage() {
  const { t } = useTranslation();
  const { entity } = useEntity();
  const [params, setParams] = useSearchParams();
  const tab = TABS.find((value) => value === params.get('tab')) ?? 'breaks';
  return (
    <Stack>
      <PageHeader
        title={t('reconciliation.title')}
        description={t('reconciliation.description', { entity: entity?.code ?? '' })}
      />
      {!entity ? (
        <Alert color="gray">{t('header.noEntity')}</Alert>
      ) : (
        <Tabs
          value={tab}
          onChange={(value) => setParams(value && value !== 'breaks' ? { tab: value } : {}, { replace: true })}
          keepMounted={false}
        >
          <Tabs.List>
            <Tabs.Tab value="breaks">{t('reconciliation.tabs.breaks')}</Tabs.Tab>
            <Tabs.Tab value="statements">{t('reconciliation.tabs.statements')}</Tabs.Tab>
          </Tabs.List>
          <Tabs.Panel value="breaks" pt="md">
            <BreaksTab entityId={entity.id} />
          </Tabs.Panel>
          <Tabs.Panel value="statements" pt="md">
            <StatementsTab entityId={entity.id} />
          </Tabs.Panel>
        </Tabs>
      )}
    </Stack>
  );
}
