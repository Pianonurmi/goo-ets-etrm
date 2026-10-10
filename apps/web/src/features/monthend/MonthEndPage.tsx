import {
  Alert,
  Badge,
  Button,
  Card,
  Group,
  List,
  Modal,
  NumberInput,
  Select,
  SimpleGrid,
  Stack,
  Table,
  Tabs,
  Text,
  TextInput,
  Textarea,
  ThemeIcon,
  Title,
} from '@mantine/core';
import {
  IconAlertTriangle,
  IconCheck,
  IconDownload,
  IconFileExport,
  IconLock,
  IconLockOpen,
  IconPlayerPlay,
  IconUpload,
} from '@tabler/icons-react';
import { useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useSearchParams } from 'react-router';
import { $api } from '@/shared/api/client';
import { decimal, type Body, type Schema } from '@/shared/api/types';
import { Can, useCan } from '@/shared/auth/Can';
import { notifySuccess } from '@/shared/components/notify';
import { PageHeader } from '@/shared/components/PageHeader';
import { ProblemAlert } from '@/shared/components/ProblemAlert';
import { QueryState } from '@/shared/components/QueryState';
import { useEntity } from '@/shared/context/EntityContext';
import { formatDateTime, formatDecimal } from '@/shared/format/format';
import { parseGlBalances, previousPeriod } from './model/glBalances';

type Period = Schema<'AccountingPeriodView'>;
type GlExport = Schema<'GlExportView'>;
type PolicyChange = Body<'RequestPolicyChange'>;
type BookType = NonNullable<PolicyChange['bookType']>;
type Treatment = NonNullable<PolicyChange['treatment']>;

const amount = (value: number | null | undefined) => (value == null ? '–' : formatDecimal(value, 2, 2));

function useInvalidateMonthEnd() {
  const queryClient = useQueryClient();
  return () =>
    queryClient.invalidateQueries({
      predicate: (q) =>
        typeof q.queryKey[1] === 'string' &&
        (q.queryKey[1].startsWith('/api/v1/ledger') || q.queryKey[1].startsWith('/api/v1/workflow')),
    });
}

export function PeriodStatusBadge({ status }: { status: Period['status'] }) {
  const colors: Record<Period['status'], string> = { OPEN: 'blue', SOFT_CLOSED: 'yellow', CLOSED: 'gray' };
  return (
    <Badge color={colors[status]} variant="light" data-testid="period-status">
      {status.replace(/_/g, ' ')}
    </Badge>
  );
}

function PeriodCard({ entityId, period }: { entityId: string; period: string }) {
  const { t } = useTranslation();
  const invalidate = useInvalidateMonthEnd();
  const canSeeBlockers = useCan('ledger_hardCloseBlockers');
  const periods = $api.useQuery('get', '/api/v1/ledger/periods', { params: { query: { legalEntityId: entityId } } });
  const blockers = $api.useQuery(
    'get',
    '/api/v1/ledger/periods/{period}/hard-close-blockers',
    { params: { path: { period }, query: { legalEntityId: entityId } } },
    { enabled: canSeeBlockers },
  );
  const onDone = (view: Period) => {
    notifySuccess(t('monthEnd.periodNow', { period: view.period, status: view.status.replace(/_/g, ' ') }));
    void invalidate();
  };
  const close = $api.useMutation('post', '/api/v1/ledger/periods/{period}/close', {
    meta: { notify: false },
    onSuccess: onDone,
  });
  const reopen = $api.useMutation('post', '/api/v1/ledger/periods/{period}/reopen', {
    meta: { notify: false },
    onSuccess: onDone,
  });
  const current = (periods.data ?? []).find((p) => p.period === period);
  const status = current?.status ?? 'OPEN';
  const pathParams = { path: { period }, query: { legalEntityId: entityId } };
  return (
    <Card withBorder>
      <Group justify="space-between" mb="sm">
        <Group gap="xs">
          <Title order={4}>{t('monthEnd.period', { period })}</Title>
          <PeriodStatusBadge status={status} />
        </Group>
        <Group gap="xs">
          {status === 'OPEN' && (
            <Can op="ledger_closePeriod">
              <Button
                variant="light"
                leftSection={<IconLock size={16} />}
                loading={close.isPending}
                onClick={() => close.mutate({ params: { ...pathParams, query: { ...pathParams.query, hard: false } } })}
                data-testid="soft-close"
              >
                {t('monthEnd.softClose')}
              </Button>
            </Can>
          )}
          {status !== 'CLOSED' && (
            <Can op="ledger_closePeriod">
              <Button
                color="red"
                variant="light"
                leftSection={<IconLock size={16} />}
                disabled={(blockers.data ?? []).length > 0}
                loading={close.isPending}
                onClick={() => close.mutate({ params: { ...pathParams, query: { ...pathParams.query, hard: true } } })}
              >
                {t('monthEnd.hardClose')}
              </Button>
            </Can>
          )}
          {status === 'SOFT_CLOSED' && (
            <Can op="ledger_reopenPeriod">
              <Button
                variant="default"
                leftSection={<IconLockOpen size={16} />}
                loading={reopen.isPending}
                onClick={() => reopen.mutate({ params: pathParams })}
              >
                {t('monthEnd.reopen')}
              </Button>
            </Can>
          )}
        </Group>
      </Group>
      {current?.closedBy && (
        <Text size="sm" c="dimmed" mb="sm">
          {t('monthEnd.closedBy', { who: current.closedBy, at: formatDateTime(current.closedAt) })}
        </Text>
      )}
      <ProblemAlert error={close.error ?? reopen.error} />
      {canSeeBlockers && (
        <Stack gap="xs" data-testid="hard-close-checklist">
          <Text size="sm" fw={600}>
            {t('monthEnd.checklist')}
          </Text>
          <QueryState isPending={blockers.isPending} error={blockers.error}>
            {status === 'CLOSED' ? (
              <Text size="sm" c="dimmed">
                {t('monthEnd.hardClosed')}
              </Text>
            ) : (blockers.data ?? []).length === 0 ? (
              <Group gap="xs">
                <ThemeIcon color="teal" size="sm" radius="xl">
                  <IconCheck size={12} />
                </ThemeIcon>
                <Text size="sm">{t('monthEnd.readyToClose')}</Text>
              </Group>
            ) : (
              <List
                spacing={4}
                size="sm"
                icon={
                  <ThemeIcon color="orange" size="sm" radius="xl">
                    <IconAlertTriangle size={12} />
                  </ThemeIcon>
                }
              >
                {(blockers.data ?? []).map((blocker) => (
                  <List.Item key={blocker}>{blocker}</List.Item>
                ))}
              </List>
            )}
          </QueryState>
        </Stack>
      )}
    </Card>
  );
}

function PeriodEndRuns({ entityId, period }: { entityId: string; period: string }) {
  const { t } = useTranslation();
  const invalidate = useInvalidateMonthEnd();
  const runs = $api.useQuery('get', '/api/v1/ledger/period-end/runs', {
    params: { query: { legalEntityId: entityId, period } },
  });
  const run = $api.useMutation('post', '/api/v1/ledger/period-end/runs', {
    meta: { notify: false },
    onSuccess: (result) => {
      notifySuccess(t('monthEnd.runDone', { seq: result.seq }));
      void invalidate();
    },
  });
  const latest = [...(runs.data ?? [])].sort((a, b) => b.seq - a.seq)[0];
  return (
    <Card withBorder>
      <Group justify="space-between" mb="sm">
        <Title order={5}>{t('monthEnd.periodEnd')}</Title>
        <Can op="periodEnd_run">
          <Button
            variant="light"
            leftSection={<IconPlayerPlay size={16} />}
            loading={run.isPending}
            onClick={() => run.mutate({ body: { legalEntityId: entityId, period } })}
          >
            {t('monthEnd.runPeriodEnd')}
          </Button>
        </Can>
      </Group>
      <ProblemAlert error={run.error} />
      <QueryState isPending={runs.isPending} error={runs.error}>
        {!latest ? (
          <Text size="sm" c="dimmed">
            {t('monthEnd.noRuns')}
          </Text>
        ) : (
          <Stack gap="xs" data-testid="period-end-run">
            <Text size="sm" c="dimmed">
              {t('monthEnd.runBy', {
                seq: latest.seq,
                who: latest.runBy,
                at: formatDateTime(latest.runAt),
                count: (runs.data ?? []).length,
              })}
            </Text>
            {latest.warnings.map((warning) => (
              <Alert key={warning} color="orange" py={6}>
                {warning}
              </Alert>
            ))}
            <Table striped withTableBorder>
              <Table.Thead>
                <Table.Tr>
                  <Table.Th>{t('monthEnd.component')}</Table.Th>
                  <Table.Th>{t('monthEnd.key')}</Table.Th>
                  <Table.Th ta="right">{t('monthEnd.required')}</Table.Th>
                  <Table.Th ta="right">{t('monthEnd.booked')}</Table.Th>
                  <Table.Th ta="right">{t('monthEnd.posted')}</Table.Th>
                </Table.Tr>
              </Table.Thead>
              <Table.Tbody>
                {latest.components.map((component) => (
                  <Table.Tr key={`${component.kind}-${component.key}`}>
                    <Table.Td>
                      {t(`monthEnd.kinds.${component.kind}`)}
                      {component.disclosedOnly && (
                        <Badge ml="xs" size="xs" variant="light" color="gray">
                          {t('monthEnd.disclosedOnly')}
                        </Badge>
                      )}
                    </Table.Td>
                    <Table.Td>{component.key}</Table.Td>
                    <Table.Td ta="right">{amount(component.required)}</Table.Td>
                    <Table.Td ta="right">{amount(component.booked)}</Table.Td>
                    <Table.Td ta="right">{amount(component.posted)}</Table.Td>
                  </Table.Tr>
                ))}
              </Table.Tbody>
            </Table>
          </Stack>
        )}
      </QueryState>
    </Card>
  );
}

function saveText(content: string, name: string) {
  const link = document.createElement('a');
  link.href = URL.createObjectURL(new Blob([content], { type: 'text/csv;charset=utf-8' }));
  link.download = name;
  document.body.appendChild(link);
  link.click();
  link.remove();
  URL.revokeObjectURL(link.href);
}

function ExportActions({ glExport }: { glExport: GlExport }) {
  const { t } = useTranslation();
  const invalidate = useInvalidateMonthEnd();
  const [mode, setMode] = useState<'acknowledge' | 'reject' | null>(null);
  const [text, setText] = useState('');
  const done = () => {
    setMode(null);
    setText('');
    void invalidate();
  };
  const delivered = $api.useMutation('post', '/api/v1/ledger/exports/{id}/delivered', { onSuccess: done });
  const acknowledge = $api.useMutation('post', '/api/v1/ledger/exports/{id}/acknowledge', {
    meta: { notify: false },
    onSuccess: done,
  });
  const reject = $api.useMutation('post', '/api/v1/ledger/exports/{id}/reject', {
    meta: { notify: false },
    onSuccess: done,
  });
  const path = { path: { id: glExport.id } };
  return (
    <Group gap={4} wrap="nowrap">
      <Button
        size="compact-xs"
        variant="subtle"
        leftSection={<IconDownload size={12} />}
        onClick={() => saveText(glExport.content, `gl-${glExport.period}-${glExport.exportSeq}.csv`)}
      >
        {t('monthEnd.download')}
      </Button>
      {glExport.status === 'GENERATED' && (
        <Can op="ledger_delivered">
          <Button size="compact-xs" variant="subtle" onClick={() => delivered.mutate({ params: path })}>
            {t('monthEnd.markDelivered')}
          </Button>
        </Can>
      )}
      {(glExport.status === 'GENERATED' || glExport.status === 'DELIVERED') && (
        <Can op="ledger_acknowledge">
          <Button size="compact-xs" variant="subtle" onClick={() => setMode('acknowledge')}>
            {t('monthEnd.acknowledge')}
          </Button>
          <Button size="compact-xs" variant="subtle" color="red" onClick={() => setMode('reject')}>
            {t('monthEnd.reject')}
          </Button>
        </Can>
      )}
      <Modal
        opened={mode !== null}
        onClose={() => setMode(null)}
        title={mode === 'reject' ? t('monthEnd.reject') : t('monthEnd.acknowledge')}
      >
        <Stack>
          <ProblemAlert error={acknowledge.error ?? reject.error} />
          <TextInput
            label={mode === 'reject' ? t('risk.reason') : t('monthEnd.ackReference')}
            value={text}
            onChange={(e) => setText(e.currentTarget.value)}
          />
          <Button
            disabled={!text.trim()}
            loading={acknowledge.isPending || reject.isPending}
            onClick={() =>
              mode === 'reject'
                ? reject.mutate({ params: path, body: { reason: text } })
                : acknowledge.mutate({ params: path, body: { reference: text } })
            }
          >
            {t('common.confirm')}
          </Button>
        </Stack>
      </Modal>
    </Group>
  );
}

function GlInterface({ entityId, period }: { entityId: string; period: string }) {
  const { t } = useTranslation();
  const invalidate = useInvalidateMonthEnd();
  const canRead = useCan('ledger_exports');
  const [format, setFormat] = useState<string | null>(null);
  const [importing, setImporting] = useState(false);
  const [pasted, setPasted] = useState('');
  const formats = $api.useQuery('get', '/api/v1/ledger/export-formats');
  const exports = $api.useQuery(
    'get',
    '/api/v1/ledger/exports',
    { params: { query: { legalEntityId: entityId, period } } },
    { enabled: canRead },
  );
  const reconciliation = $api.useQuery(
    'get',
    '/api/v1/ledger/reconciliation',
    { params: { query: { legalEntityId: entityId, period } } },
    { enabled: canRead },
  );
  const create = $api.useMutation('post', '/api/v1/ledger/exports/{period}', {
    meta: { notify: false },
    onSuccess: (created) => {
      notifySuccess(t('monthEnd.exported', { seq: created.exportSeq, count: created.entryCount }));
      void invalidate();
    },
  });
  const parsed = parseGlBalances(pasted);
  const importBalances = $api.useMutation('put', '/api/v1/ledger/gl-balances/{period}', {
    meta: { notify: false },
    onSuccess: () => {
      notifySuccess(t('monthEnd.balancesImported'));
      setImporting(false);
      setPasted('');
      void invalidate();
    },
  });
  if (!canRead) return <Alert color="gray">{t('monthEnd.glRestricted')}</Alert>;
  const activeFormats = (formats.data ?? []).filter((f) => f.active);
  return (
    <Stack>
      <Card withBorder>
        <Group justify="space-between" mb="sm" align="flex-end">
          <Title order={5}>{t('monthEnd.glExports')}</Title>
          <Can op="ledger_export">
            <Group gap="xs" align="flex-end">
              <Select
                aria-label={t('marketData.format')}
                data={activeFormats.map((f) => ({
                  value: f.code,
                  label: f.verified ? f.name : `${f.name} (${t('monthEnd.unverified')})`,
                }))}
                value={format ?? activeFormats[0]?.code ?? null}
                onChange={setFormat}
                w={260}
              />
              <Button
                leftSection={<IconFileExport size={16} />}
                loading={create.isPending}
                onClick={() =>
                  create.mutate({
                    params: {
                      path: { period },
                      query: { legalEntityId: entityId, format: format ?? activeFormats[0]?.code },
                    },
                  })
                }
              >
                {t('monthEnd.createExport')}
              </Button>
            </Group>
          </Can>
        </Group>
        <ProblemAlert error={create.error} />
        <QueryState isPending={exports.isPending} error={exports.error}>
          {(exports.data ?? []).length === 0 ? (
            <Text size="sm" c="dimmed">
              {t('monthEnd.noExports')}
            </Text>
          ) : (
            <Table striped withTableBorder>
              <Table.Thead>
                <Table.Tr>
                  <Table.Th>#</Table.Th>
                  <Table.Th>{t('marketData.kind')}</Table.Th>
                  <Table.Th>{t('marketData.format')}</Table.Th>
                  <Table.Th ta="right">{t('monthEnd.entries')}</Table.Th>
                  <Table.Th>{t('allocation.status')}</Table.Th>
                  <Table.Th>{t('monthEnd.exportedBy')}</Table.Th>
                  <Table.Th>{t('monthEnd.hashShort')}</Table.Th>
                  <Table.Th />
                </Table.Tr>
              </Table.Thead>
              <Table.Tbody>
                {(exports.data ?? []).map((row) => (
                  <Table.Tr key={row.id}>
                    <Table.Td>{row.exportSeq}</Table.Td>
                    <Table.Td>{row.kind}</Table.Td>
                    <Table.Td>{row.format}</Table.Td>
                    <Table.Td ta="right">{row.entryCount}</Table.Td>
                    <Table.Td>
                      <Badge
                        variant="light"
                        color={row.status === 'ACKNOWLEDGED' ? 'teal' : row.status === 'REJECTED' ? 'red' : 'blue'}
                      >
                        {row.status}
                      </Badge>
                      {row.statusReason && (
                        <Text size="xs" c="dimmed">
                          {row.statusReason}
                        </Text>
                      )}
                    </Table.Td>
                    <Table.Td>
                      <Text size="xs">
                        {row.exportedBy} · {formatDateTime(row.exportedAt)}
                      </Text>
                    </Table.Td>
                    <Table.Td>
                      <Text size="xs" ff="monospace">
                        {row.contentHash.slice(0, 12)}
                      </Text>
                    </Table.Td>
                    <Table.Td>
                      <ExportActions glExport={row} />
                    </Table.Td>
                  </Table.Tr>
                ))}
              </Table.Tbody>
            </Table>
          )}
        </QueryState>
      </Card>
      <Card withBorder>
        <Group justify="space-between" mb="sm">
          <Title order={5}>{t('monthEnd.reconciliation')}</Title>
          <Can op="ledger_importBalances">
            <Button variant="light" leftSection={<IconUpload size={16} />} onClick={() => setImporting(true)}>
              {t('monthEnd.importBalances')}
            </Button>
          </Can>
        </Group>
        <QueryState isPending={reconciliation.isPending} error={reconciliation.error}>
          {(reconciliation.data ?? []).length === 0 ? (
            <Text size="sm" c="dimmed">
              {t('common.empty')}
            </Text>
          ) : (
            <Table striped withTableBorder>
              <Table.Thead>
                <Table.Tr>
                  <Table.Th>{t('ledger.glMapping')}</Table.Th>
                  <Table.Th ta="right">{t('monthEnd.subLedger')}</Table.Th>
                  <Table.Th ta="right">{t('monthEnd.exportedAmount')}</Table.Th>
                  <Table.Th ta="right">{t('monthEnd.notExported')}</Table.Th>
                  <Table.Th ta="right">{t('monthEnd.generalLedger')}</Table.Th>
                  <Table.Th ta="right">{t('monthEnd.difference')}</Table.Th>
                  <Table.Th />
                </Table.Tr>
              </Table.Thead>
              <Table.Tbody>
                {(reconciliation.data ?? []).map((row) => (
                  <Table.Tr key={row.glAccount}>
                    <Table.Td>{row.glAccount}</Table.Td>
                    <Table.Td ta="right">{amount(row.subLedger)}</Table.Td>
                    <Table.Td ta="right">{amount(row.exported)}</Table.Td>
                    <Table.Td ta="right">{amount(row.notExported)}</Table.Td>
                    <Table.Td ta="right">{amount(row.generalLedger)}</Table.Td>
                    <Table.Td ta="right" c={row.glDifference ? 'red' : undefined}>
                      {amount(row.glDifference)}
                    </Table.Td>
                    <Table.Td>
                      {row.reconciled ? (
                        <IconCheck size={16} color="var(--mantine-color-teal-6)" />
                      ) : (
                        <IconAlertTriangle size={16} color="var(--mantine-color-orange-6)" />
                      )}
                    </Table.Td>
                  </Table.Tr>
                ))}
              </Table.Tbody>
            </Table>
          )}
        </QueryState>
      </Card>
      <Modal opened={importing} onClose={() => setImporting(false)} title={t('monthEnd.importBalances')} size="lg">
        <Stack>
          <ProblemAlert error={importBalances.error} />
          <Text size="sm" c="dimmed">
            {t('monthEnd.importHint')}
          </Text>
          <Textarea
            value={pasted}
            onChange={(e) => setPasted(e.currentTarget.value)}
            autosize
            minRows={6}
            ff="monospace"
            placeholder={'glAccount;balance;currency\n1610;110000.00;EUR'}
          />
          {parsed.errors.length > 0 && (
            <Alert color="orange">{t('monthEnd.badLines', { lines: parsed.errors.join(', ') })}</Alert>
          )}
          <Button
            disabled={parsed.lines.length === 0 || parsed.errors.length > 0}
            loading={importBalances.isPending}
            onClick={() =>
              importBalances.mutate({
                params: { path: { period }, query: { legalEntityId: entityId } },
                body: parsed.lines.map((line) => ({ ...line, balance: decimal(line.balance) })),
              })
            }
          >
            {t('monthEnd.importCount', { count: parsed.lines.length })}
          </Button>
        </Stack>
      </Modal>
    </Stack>
  );
}

const BOOK_TYPES: BookType[] = ['TRADING', 'OWN_USE', 'HEDGING', 'COMPLIANCE'];
const TREATMENTS: Treatment[] = ['POST_PL', 'POST_OCI', 'DISCLOSE_ONLY'];

function PolicyChangeModal({ entityId, onClose }: { entityId: string; onClose: () => void }) {
  const { t } = useTranslation();
  const invalidate = useInvalidateMonthEnd();
  const [kind, setKind] = useState<PolicyChange['kind']>('MTM');
  const [bookType, setBookType] = useState<BookType>('TRADING');
  const [treatment, setTreatment] = useState<Treatment>('POST_PL');
  const [goo, setGoo] = useState('');
  const [eua, setEua] = useState('');
  const [currency, setCurrency] = useState('EUR');
  const [expiryDays, setExpiryDays] = useState<number | ''>('');
  const [reason, setReason] = useState('');
  const request = $api.useMutation('post', '/api/v1/ledger/period-end/policy-changes', {
    meta: { notify: false },
    onSuccess: () => {
      notifySuccess(t('monthEnd.policyRequested'));
      void invalidate();
      onClose();
    },
  });
  return (
    <Modal opened onClose={onClose} title={t('monthEnd.requestPolicy')}>
      <Stack>
        <ProblemAlert error={request.error} />
        <Text size="sm" c="dimmed">
          {t('risk.fourEyesHint')}
        </Text>
        <Select
          label={t('marketData.kind')}
          data={[
            { value: 'MTM', label: t('monthEnd.mtmPolicy') },
            { value: 'NRV', label: t('monthEnd.nrvPolicy') },
          ]}
          value={kind}
          allowDeselect={false}
          onChange={(value) => value && setKind(value)}
        />
        {kind === 'MTM' ? (
          <Group grow>
            <Select
              label={t('monthEnd.bookType')}
              data={BOOK_TYPES}
              value={bookType}
              allowDeselect={false}
              onChange={(value) => value && setBookType(value)}
            />
            <Select
              label={t('monthEnd.treatment')}
              data={TREATMENTS.map((value) => ({ value, label: t(`monthEnd.treatments.${value}`) }))}
              value={treatment}
              allowDeselect={false}
              onChange={(value) => value && setTreatment(value)}
            />
          </Group>
        ) : (
          <SimpleGrid cols={2}>
            <TextInput
              label={t('monthEnd.gooCostToSell')}
              inputMode="decimal"
              value={goo}
              onChange={(e) => setGoo(e.currentTarget.value)}
            />
            <TextInput
              label={t('monthEnd.euaCostToSell')}
              inputMode="decimal"
              value={eua}
              onChange={(e) => setEua(e.currentTarget.value)}
            />
            <TextInput
              label={t('trades.form.currency')}
              value={currency}
              maxLength={3}
              onChange={(e) => setCurrency(e.currentTarget.value.toUpperCase())}
            />
            <NumberInput
              label={t('monthEnd.expiryDays')}
              value={expiryDays}
              onChange={(v) => setExpiryDays(v === '' ? '' : Number(v))}
              min={0}
              allowDecimal={false}
            />
          </SimpleGrid>
        )}
        <Textarea
          label={t('risk.reason')}
          value={reason}
          onChange={(e) => setReason(e.currentTarget.value)}
          autosize
          minRows={2}
        />
        <Button
          disabled={!reason.trim()}
          loading={request.isPending}
          onClick={() =>
            request.mutate({
              body:
                kind === 'MTM'
                  ? { kind, legalEntityId: entityId, bookType, treatment, reason }
                  : {
                      kind,
                      legalEntityId: entityId,
                      gooCostToSellPerMwh: goo ? decimal(goo) : undefined,
                      allowanceCostToSell: eua ? decimal(eua) : undefined,
                      currency,
                      expiryDays: expiryDays === '' ? undefined : expiryDays,
                      reason,
                    },
            })
          }
        >
          {t('risk.sendForApproval')}
        </Button>
      </Stack>
    </Modal>
  );
}

function Policies({ entityId }: { entityId: string }) {
  const { t } = useTranslation();
  const [requesting, setRequesting] = useState(false);
  const mtm = $api.useQuery('get', '/api/v1/ledger/period-end/policies/mtm', {
    params: { query: { legalEntityId: entityId } },
  });
  const nrv = $api.useQuery(
    'get',
    '/api/v1/ledger/period-end/policies/nrv',
    { params: { query: { legalEntityId: entityId } } },
    { retry: false },
  );
  const changes = $api.useQuery('get', '/api/v1/ledger/period-end/policy-changes', {
    params: { query: { status: 'PENDING' } },
  });
  const signed = (by: string | null | undefined, at: string | null | undefined) =>
    by ? t('curves.signedOffByWho', { who: by, at: formatDateTime(at) }) : t('curves.notSignedOff');
  return (
    <Stack>
      <Group justify="flex-end">
        <Can op="periodEnd_requestPolicyChange">
          <Button variant="light" onClick={() => setRequesting(true)}>
            {t('monthEnd.requestPolicy')}
          </Button>
        </Can>
      </Group>
      {(changes.data ?? []).length > 0 && (
        <Alert color="yellow">{t('monthEnd.pendingPolicies', { count: (changes.data ?? []).length })}</Alert>
      )}
      <Card withBorder>
        <Title order={5} mb="sm">
          {t('monthEnd.mtmPolicy')}
        </Title>
        <QueryState isPending={mtm.isPending} error={mtm.error}>
          <Table striped>
            <Table.Thead>
              <Table.Tr>
                <Table.Th>{t('monthEnd.bookType')}</Table.Th>
                <Table.Th>{t('monthEnd.treatment')}</Table.Th>
                <Table.Th>{t('monthEnd.scope')}</Table.Th>
                <Table.Th>{t('curves.signedOffBy')}</Table.Th>
              </Table.Tr>
            </Table.Thead>
            <Table.Tbody>
              {(mtm.data ?? []).map((policy) => (
                <Table.Tr key={policy.id}>
                  <Table.Td>{policy.bookType}</Table.Td>
                  <Table.Td>{t(`monthEnd.treatments.${policy.treatment}`)}</Table.Td>
                  <Table.Td>{policy.legalEntityId ? t('monthEnd.entityPolicy') : t('monthEnd.groupPolicy')}</Table.Td>
                  <Table.Td>{signed(policy.signedOffBy, policy.signedOffAt)}</Table.Td>
                </Table.Tr>
              ))}
            </Table.Tbody>
          </Table>
        </QueryState>
      </Card>
      <Card withBorder>
        <Title order={5} mb="sm">
          {t('monthEnd.nrvPolicy')}
        </Title>
        {nrv.data ? (
          <SimpleGrid cols={{ base: 2, md: 4 }}>
            <Text size="sm">
              {t('monthEnd.gooCostToSell')}: {formatDecimal(nrv.data.gooCostToSellPerMwh, 2, 4)} {nrv.data.currency}
            </Text>
            <Text size="sm">
              {t('monthEnd.euaCostToSell')}: {formatDecimal(nrv.data.allowanceCostToSell, 2, 4)} {nrv.data.currency}
            </Text>
            <Text size="sm">
              {t('monthEnd.expiryDays')}: {nrv.data.expiryDays}
            </Text>
            <Text size="sm">{signed(nrv.data.signedOffBy, nrv.data.signedOffAt)}</Text>
          </SimpleGrid>
        ) : (
          <Text size="sm" c="dimmed">
            {t('common.empty')}
          </Text>
        )}
      </Card>
      {requesting && <PolicyChangeModal entityId={entityId} onClose={() => setRequesting(false)} />}
    </Stack>
  );
}

/** Period end of the selected entity: close and its checklist, period-end runs, the GL interface and policies. */
export function MonthEndPage() {
  const { t } = useTranslation();
  const { entity } = useEntity();
  const [params, setParams] = useSearchParams();
  const period = params.get('period') ?? previousPeriod(new Date());
  const tab = params.get('tab') ?? 'close';
  const set = (key: string, value: string | null) => {
    const next = new URLSearchParams(params);
    if (value) next.set(key, value);
    else next.delete(key);
    setParams(next, { replace: true });
  };
  return (
    <Stack>
      <PageHeader title={t('monthEnd.title')} description={t('monthEnd.description', { entity: entity?.code ?? '' })} />
      {!entity ? (
        <Alert color="gray">{t('header.noEntity')}</Alert>
      ) : (
        <>
          <TextInput
            type="month"
            label={t('ledger.period')}
            value={period}
            onChange={(e) => set('period', e.currentTarget.value || null)}
            w={170}
            data-testid="period-picker"
          />
          <Tabs value={tab} onChange={(value) => set('tab', value === 'close' ? null : value)} keepMounted={false}>
            <Tabs.List>
              <Tabs.Tab value="close">{t('monthEnd.tabs.close')}</Tabs.Tab>
              <Tabs.Tab value="gl">{t('monthEnd.tabs.gl')}</Tabs.Tab>
              <Tabs.Tab value="policies">{t('monthEnd.tabs.policies')}</Tabs.Tab>
            </Tabs.List>
            <Tabs.Panel value="close" pt="md">
              <Stack>
                <PeriodCard entityId={entity.id} period={period} />
                <PeriodEndRuns entityId={entity.id} period={period} />
              </Stack>
            </Tabs.Panel>
            <Tabs.Panel value="gl" pt="md">
              <GlInterface entityId={entity.id} period={period} />
            </Tabs.Panel>
            <Tabs.Panel value="policies" pt="md">
              <Policies entityId={entity.id} />
            </Tabs.Panel>
          </Tabs>
        </>
      )}
    </Stack>
  );
}
