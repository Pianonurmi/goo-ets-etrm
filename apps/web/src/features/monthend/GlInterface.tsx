import {
  Alert,
  Button,
  Card,
  Group,
  Modal,
  Select,
  Stack,
  Table,
  Text,
  Textarea,
  TextInput,
  Title,
} from '@mantine/core';
import { IconAlertTriangle, IconCheck, IconDownload, IconFileExport, IconUpload } from '@tabler/icons-react';
import { useMutation } from '@tanstack/react-query';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { $api } from '@/shared/api/client';
import { download } from '@/shared/api/download';
import { decimal } from '@/shared/api/types';
import { Can, useCan } from '@/shared/auth/Can';
import { notifySuccess } from '@/shared/components/notify';
import { ProblemAlert } from '@/shared/components/ProblemAlert';
import { QueryState } from '@/shared/components/QueryState';
import { StatusBadge, statusLabel } from '@/shared/components/StatusBadge';
import { useEntity } from '@/shared/context/EntityContext';
import { formatDateTime } from '@/shared/format/format';
import { useInvalidateMonthEnd } from './hooks';
import { parseGlBalances } from './model/glBalances';
import { glExportCsvUrl, glExportFileName, hasContent } from './model/glExport';
import { type GlExport, amount } from './shared';

export function saveText(content: string, name: string) {
  const link = document.createElement('a');
  link.href = URL.createObjectURL(new Blob([content], { type: 'text/csv;charset=utf-8' }));
  link.download = name;
  document.body.appendChild(link);
  link.click();
  link.remove();
  URL.revokeObjectURL(link.href);
}

export function ExportActions({ glExport }: { glExport: GlExport }) {
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
      {hasContent(glExport) && (
        <Button
          size="compact-xs"
          variant="subtle"
          leftSection={<IconDownload size={12} />}
          onClick={() => saveText(glExport.content, `gl-${glExport.period}-${glExport.exportSeq}.csv`)}
        >
          {t('monthEnd.download')}
        </Button>
      )}
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

export function GlInterface({ entityId, period }: { entityId: string; period: string }) {
  const { t } = useTranslation();
  const invalidate = useInvalidateMonthEnd();
  const { entity } = useEntity();
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
  const chosenFormat = format ?? (formats.data ?? []).find((f) => f.active)?.code;
  const csv = useMutation({
    meta: { notify: false },
    mutationFn: () =>
      download(
        glExportCsvUrl(period, entityId, chosenFormat),
        glExportFileName(entity?.code ?? 'entity', period, chosenFormat),
        { method: 'POST' },
      ),
    onSuccess: () => {
      notifySuccess(t('monthEnd.csvExported'));
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
              <Can op="ledger_exportCsv">
                <Button
                  variant="light"
                  leftSection={<IconDownload size={16} />}
                  loading={csv.isPending}
                  onClick={() => csv.mutate()}
                  data-testid="export-csv"
                >
                  {t('monthEnd.exportCsv')}
                </Button>
              </Can>
            </Group>
          </Can>
        </Group>
        <ProblemAlert error={create.error ?? csv.error} />
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
                    <Table.Td>{statusLabel(row.kind ?? '')}</Table.Td>
                    <Table.Td>{row.format}</Table.Td>
                    <Table.Td ta="right">{row.entryCount}</Table.Td>
                    <Table.Td>
                      <StatusBadge status={row.status ?? 'GENERATED'} />
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
