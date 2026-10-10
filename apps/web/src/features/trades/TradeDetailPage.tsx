import {
  Alert,
  Anchor,
  Badge,
  Button,
  Card,
  Group,
  Modal,
  SimpleGrid,
  Stack,
  Table,
  Tabs,
  Text,
  TextInput,
  Textarea,
  Title,
} from '@mantine/core';
import { IconArrowLeft, IconInbox } from '@tabler/icons-react';
import { useQueryClient } from '@tanstack/react-query';
import { useState, type ReactNode } from 'react';
import { useTranslation } from 'react-i18next';
import { Link, useParams } from 'react-router';
import { $api } from '@/shared/api/client';
import type { Schema } from '@/shared/api/types';
import { useCan } from '@/shared/auth/Can';
import { notifySuccess } from '@/shared/components/notify';
import { ProblemAlert } from '@/shared/components/ProblemAlert';
import { QueryState } from '@/shared/components/QueryState';
import { formatDate, formatDateTime, formatDecimal, formatMoney } from '@/shared/format/format';
import { useBooks, useCounterparties } from './hooks';
import { diffVersions } from './model/diff';
import type { TradeView } from './model/tradeForm';
import { TradeStatusBadge } from './TradeStatusBadge';

const OPEN = ['DRAFT', 'PENDING_APPROVAL', 'APPROVED', 'CONFIRMED', 'PARTIALLY_DELIVERED'];

function Field({ label, children }: { label: string; children: ReactNode }) {
  return (
    <Stack gap={0}>
      <Text size="xs" c="dimmed">
        {label}
      </Text>
      <Text size="sm" component="div">
        {children ?? '—'}
      </Text>
    </Stack>
  );
}

function useRefreshTrades() {
  const queryClient = useQueryClient();
  return () =>
    queryClient.invalidateQueries({
      predicate: (q) =>
        typeof q.queryKey[1] === 'string' &&
        (q.queryKey[1].startsWith('/api/v1/trades') || q.queryKey[1].startsWith('/api/v1/workflow')),
    });
}

function Lifecycle({ trade }: { trade: TradeView }) {
  const { t } = useTranslation();
  const refresh = useRefreshTrades();
  const canAmend = useCan('trade_amend');
  const canCancel = useCan('trade_cancel');
  const canConfirm = useCan('trade_confirm');
  const [modal, setModal] = useState<'cancel' | 'confirm' | null>(null);
  const [reason, setReason] = useState('');
  const [reference, setReference] = useState('');
  const path = { params: { path: { id: trade.id } } };
  const done = (message: string) => () => {
    notifySuccess(message);
    setModal(null);
    void refresh();
  };
  const cancel = $api.useMutation('post', '/api/v1/trades/{id}/cancel', {
    onSuccess: done(t('trades.cancelledDone')),
    meta: { notify: false },
  });
  const confirm = $api.useMutation('post', '/api/v1/trades/{id}/confirm', {
    onSuccess: done(t('trades.confirmedDone')),
  });
  const task = $api.useQuery(
    'get',
    '/api/v1/workflow/tasks',
    { params: { query: { subjectRef: trade.id, type: ['TRADE_APPROVAL'], actionable: false } } },
    { enabled: trade.status === 'PENDING_APPROVAL' },
  );
  const approvalTask = task.data?.items[0];
  const open = OPEN.includes(trade.status);

  return (
    <Group>
      {approvalTask && (
        <Button
          component={Link}
          to={`/inbox/${approvalTask.id}`}
          variant="light"
          leftSection={<IconInbox size={16} />}
          data-testid="approval-task"
        >
          {t('trades.approvalTask')}
        </Button>
      )}
      {canConfirm && trade.status === 'APPROVED' && (
        <Button color="teal" onClick={() => setModal('confirm')} data-testid="confirm-trade">
          {t('trades.confirm')}
        </Button>
      )}
      {canAmend && open && (
        <Button component={Link} to={`/trades/${trade.id}/amend`} variant="default" data-testid="amend-trade">
          {t('trades.amend')}
        </Button>
      )}
      {canCancel && open && (
        <Button color="red" variant="light" onClick={() => setModal('cancel')}>
          {t('trades.cancel')}
        </Button>
      )}
      <Modal opened={modal === 'cancel'} onClose={() => setModal(null)} title={t('trades.cancel')}>
        <Stack>
          <Textarea
            label={t('inbox.reason')}
            required
            value={reason}
            onChange={(e) => setReason(e.currentTarget.value)}
          />
          <Button
            color="red"
            disabled={!reason.trim()}
            loading={cancel.isPending}
            onClick={() => cancel.mutate({ ...path, body: { reason: reason.trim() } })}
          >
            {t('trades.cancel')}
          </Button>
        </Stack>
      </Modal>
      <Modal opened={modal === 'confirm'} onClose={() => setModal(null)} title={t('trades.confirm')}>
        <Stack>
          <Text size="sm">{t('trades.confirmHint')}</Text>
          <ProblemAlert error={confirm.error} />
          <TextInput
            label={t('trades.confirmationRef')}
            required
            value={reference}
            onChange={(e) => setReference(e.currentTarget.value)}
            data-testid="confirmation-ref"
          />
          <Button
            color="teal"
            disabled={!reference.trim()}
            loading={confirm.isPending}
            onClick={() => confirm.mutate({ ...path, body: { confirmationRef: reference.trim() } })}
            data-testid="confirm-submit"
          >
            {t('trades.confirm')}
          </Button>
        </Stack>
      </Modal>
    </Group>
  );
}

function Legs({ trade }: { trade: TradeView }) {
  const { t } = useTranslation();
  return (
    <Stack>
      {trade.legs.map((leg) => (
        <Card withBorder key={leg.id}>
          <Group justify="space-between" mb="xs">
            <Title order={5}>
              {t('trades.form.leg', { number: leg.legNo })}: {leg.side} {formatDecimal(leg.quantity, 0, 3)} {leg.unit} @{' '}
              {formatDecimal(leg.price, 2, 6)} {leg.priceCurrency}
            </Title>
            {leg.notional != null && <Badge variant="light">{formatMoney(leg.notional, leg.priceCurrency)}</Badge>}
          </Group>
          <SimpleGrid cols={{ base: 2, md: 4 }} mb="sm">
            <Field label={t('trades.columns.product')}>{leg.allowanceProductCode ?? leg.criteriaSummary}</Field>
            <Field label={t('trades.bucket')}>
              {leg.bucketCode ? `${leg.bucketCode} (${leg.bucketMatch ?? ''})` : null}
            </Field>
            <Field label={t('trades.form.deliveryAccount')}>{leg.deliveryAccountId?.slice(-8) ?? null}</Field>
            <Field label={t('trades.form.counterpartyAccount')}>{leg.counterpartyAccountRef ?? null}</Field>
          </SimpleGrid>
          <Table withTableBorder striped>
            <Table.Thead>
              <Table.Tr>
                <Table.Th>#</Table.Th>
                <Table.Th>{t('trades.form.windowStart')}</Table.Th>
                <Table.Th>{t('trades.form.windowEnd')}</Table.Th>
                <Table.Th>{t('trades.form.deliveryDate')}</Table.Th>
                <Table.Th ta="right">{t('trades.scheduled')}</Table.Th>
                <Table.Th ta="right">{t('trades.delivered')}</Table.Th>
                <Table.Th>{t('trades.columns.status')}</Table.Th>
              </Table.Tr>
            </Table.Thead>
            <Table.Tbody>
              {leg.deliveries.map((delivery) => (
                <Table.Tr key={delivery.id}>
                  <Table.Td>{delivery.scheduleNo}</Table.Td>
                  <Table.Td>{formatDate(delivery.deliveryWindow.start)}</Table.Td>
                  <Table.Td>{formatDate(delivery.deliveryWindow.endExclusive)}</Table.Td>
                  <Table.Td>{formatDate(delivery.deliveryDate)}</Table.Td>
                  <Table.Td ta="right">{formatDecimal(delivery.quantity, 0, 3)}</Table.Td>
                  <Table.Td ta="right">{formatDecimal(delivery.deliveredQuantity, 0, 3)}</Table.Td>
                  <Table.Td>{delivery.status}</Table.Td>
                </Table.Tr>
              ))}
            </Table.Tbody>
          </Table>
        </Card>
      ))}
    </Stack>
  );
}

type Version = Schema<'TradeVersionView'>;

function Versions({ tradeId }: { tradeId: string }) {
  const { t } = useTranslation();
  const versions = $api.useQuery('get', '/api/v1/trades/{id}/versions', { params: { path: { id: tradeId } } });
  const list: Version[] = [...(versions.data ?? [])].sort((a, b) => b.version - a.version);
  return (
    <QueryState isPending={versions.isPending} error={versions.error}>
      <Stack>
        {list.map((version) => {
          const previous = list.find((candidate) => candidate.version === version.version - 1);
          const changes = previous ? diffVersions(previous.trade, version.trade) : [];
          return (
            <Card withBorder key={version.version}>
              <Group justify="space-between">
                <Text fw={600}>{t('trades.version', { version: version.version })}</Text>
                <Text size="xs" c="dimmed">
                  {version.recordedBy} · {formatDateTime(version.recordedAt)}
                </Text>
              </Group>
              <Text size="sm">{version.reason ?? ''}</Text>
              {changes.length > 0 && (
                <Table mt="xs" withTableBorder>
                  <Table.Tbody>
                    {changes.slice(0, 40).map((change) => (
                      <Table.Tr key={change.path}>
                        <Table.Td>
                          <Text size="xs" ff="monospace">
                            {change.path}
                          </Text>
                        </Table.Td>
                        <Table.Td>
                          <Text size="xs" c="red">
                            {JSON.stringify(change.before) ?? '—'}
                          </Text>
                        </Table.Td>
                        <Table.Td>
                          <Text size="xs" c="teal">
                            {JSON.stringify(change.after) ?? '—'}
                          </Text>
                        </Table.Td>
                      </Table.Tr>
                    ))}
                  </Table.Tbody>
                </Table>
              )}
            </Card>
          );
        })}
      </Stack>
    </QueryState>
  );
}

function Confirmations({ tradeId }: { tradeId: string }) {
  const { t } = useTranslation();
  const confirmations = $api.useQuery('get', '/api/v1/trades/{tradeId}/confirmations', {
    params: { path: { tradeId } },
  });
  return (
    <QueryState isPending={confirmations.isPending} error={confirmations.error}>
      {(confirmations.data ?? []).length === 0 ? (
        <Text size="sm" c="dimmed">
          {t('trades.noConfirmations')}
        </Text>
      ) : (
        <Table withTableBorder striped>
          <Table.Thead>
            <Table.Tr>
              <Table.Th>{t('trades.direction')}</Table.Th>
              <Table.Th>{t('trades.columns.status')}</Table.Th>
              <Table.Th>{t('trades.source')}</Table.Th>
              <Table.Th>{t('trades.version', { version: '' })}</Table.Th>
              <Table.Th>{t('trades.differences')}</Table.Th>
              <Table.Th>{t('trades.created')}</Table.Th>
            </Table.Tr>
          </Table.Thead>
          <Table.Tbody>
            {(confirmations.data ?? []).map((confirmation) => (
              <Table.Tr key={confirmation.id}>
                <Table.Td>{confirmation.direction}</Table.Td>
                <Table.Td>{confirmation.status}</Table.Td>
                <Table.Td>{confirmation.source}</Table.Td>
                <Table.Td>{confirmation.tradeVersion}</Table.Td>
                <Table.Td>{confirmation.differences.length}</Table.Td>
                <Table.Td>{formatDateTime(confirmation.createdAt)}</Table.Td>
              </Table.Tr>
            ))}
          </Table.Tbody>
        </Table>
      )}
    </QueryState>
  );
}

function MarkToMarket({ tradeId }: { tradeId: string }) {
  const { t } = useTranslation();
  const [date, setDate] = useState(new Date().toISOString().slice(0, 10));
  const mtm = $api.useQuery(
    'get',
    '/api/v1/valuation/trades/{id}/mtm',
    { params: { path: { id: tradeId }, query: { date } } },
    { retry: false },
  );
  return (
    <Stack>
      <TextInput
        type="date"
        label={t('trades.mtm.asOf')}
        description={t('trades.mtm.asOfHint')}
        value={date}
        onChange={(e) => setDate(e.currentTarget.value)}
        w={220}
      />
      {mtm.error ? (
        <Alert color="gray">{t('trades.noValuation')}</Alert>
      ) : (
        <QueryState isPending={mtm.isPending} error={null}>
          {(mtm.data ?? []).length === 0 ? (
            <Alert color="gray">{t('trades.noValuation')}</Alert>
          ) : (
            <Table withTableBorder striped>
              <Table.Thead>
                <Table.Tr>
                  <Table.Th>{t('trades.form.deliveryDate')}</Table.Th>
                  <Table.Th ta="right">{t('trades.columns.quantity')}</Table.Th>
                  <Table.Th ta="right">{t('trades.mtm.contractPrice')}</Table.Th>
                  <Table.Th ta="right">{t('trades.mtm.marketPrice')}</Table.Th>
                  <Table.Th ta="right">{t('trades.mtm.mtm')}</Table.Th>
                  <Table.Th ta="right">{t('trades.mtm.mtmBase')}</Table.Th>
                  <Table.Th>{t('trades.mtm.curve')}</Table.Th>
                  <Table.Th>{t('trades.columns.status')}</Table.Th>
                </Table.Tr>
              </Table.Thead>
              <Table.Tbody>
                {(mtm.data ?? []).map((row) => (
                  <Table.Tr key={`${row.runId}-${row.scheduleId}`}>
                    <Table.Td>{formatDate(row.deliveryDate)}</Table.Td>
                    <Table.Td ta="right">{formatDecimal(row.quantity, 0, 3)}</Table.Td>
                    <Table.Td ta="right">{formatMoney(row.contractPrice, row.contractCurrency)}</Table.Td>
                    <Table.Td ta="right">
                      {row.marketPriceContract != null
                        ? formatMoney(row.marketPriceContract, row.contractCurrency)
                        : '—'}
                    </Table.Td>
                    <Table.Td ta="right">{row.mtm != null ? formatMoney(row.mtm, row.contractCurrency) : '—'}</Table.Td>
                    <Table.Td ta="right">{row.mtmBase != null ? formatDecimal(row.mtmBase, 2, 2) : '—'}</Table.Td>
                    <Table.Td>{row.curveCode ? `${row.curveCode} ${formatDate(row.curveDate)}` : '—'}</Table.Td>
                    <Table.Td>{row.status}</Table.Td>
                  </Table.Tr>
                ))}
              </Table.Tbody>
            </Table>
          )}
        </QueryState>
      )}
    </Stack>
  );
}

export function TradeDetailPage() {
  const { t } = useTranslation();
  const { tradeId = '' } = useParams();
  const trade = $api.useQuery('get', '/api/v1/trades/{id}', { params: { path: { id: tradeId } } });
  const counterparties = useCounterparties();
  const books = useBooks(trade.data?.legalEntityId);
  return (
    <Stack>
      <Anchor component={Link} to="/trades" size="sm">
        <Group gap={4}>
          <IconArrowLeft size={14} />
          {t('trades.back')}
        </Group>
      </Anchor>
      <QueryState isPending={trade.isPending} error={trade.error}>
        {trade.data &&
          (() => {
            const data = trade.data;
            return (
              <Stack>
                <Card withBorder>
                  <Group justify="space-between" align="flex-start">
                    <Stack gap={2}>
                      <Group gap="xs">
                        <Title order={3} data-testid="trade-number">
                          {data.tradeNumber}
                        </Title>
                        <TradeStatusBadge status={data.status} />
                        <Badge variant="outline" color="gray">
                          v{data.version}
                        </Badge>
                      </Group>
                      <Text size="sm" c="dimmed">
                        {t(`trades.families.${data.productFamily}`)} · {data.instrumentType} ·{' '}
                        {formatDate(data.tradeDate)}
                      </Text>
                    </Stack>
                    <Lifecycle trade={data} />
                  </Group>
                  <SimpleGrid cols={{ base: 2, md: 4 }} mt="md">
                    <Field label={t('trades.columns.counterparty')}>
                      {counterparties.byId.get(data.counterpartyId)?.name ?? data.counterpartyId}
                    </Field>
                    <Field label={t('trades.columns.book')}>{books.byId.get(data.bookId)?.code ?? data.bookId}</Field>
                    <Field label={t('trades.columns.trader')}>{data.trader}</Field>
                    <Field label={t('trades.columns.notional')}>
                      {data.notional != null ? formatMoney(data.notional, data.legs[0]?.priceCurrency) : null}
                    </Field>
                    <Field label={t('trades.columns.externalRef')}>{data.externalRef}</Field>
                    <Field label="UTI">{data.regulatory?.uti ?? null}</Field>
                    <Field label={t('trades.approved')}>
                      {data.approvedBy ? `${data.approvedBy} · ${formatDateTime(data.approvedAt)}` : null}
                    </Field>
                    <Field label={t('trades.confirmed')}>
                      {data.confirmationRef ? `${data.confirmationRef} · ${formatDateTime(data.confirmedAt)}` : null}
                    </Field>
                  </SimpleGrid>
                  {(data.approvalFindings ?? []).length > 0 && (
                    <Alert
                      mt="md"
                      color={data.requiresRiskApproval ? 'red' : 'yellow'}
                      title={t('trades.findings')}
                      data-testid="findings"
                    >
                      {(data.approvalFindings ?? []).map((finding) => (
                        <Text key={finding.code} size="sm">
                          {finding.severity}: {finding.message}
                        </Text>
                      ))}
                    </Alert>
                  )}
                </Card>
                <Tabs defaultValue="legs" keepMounted={false}>
                  <Tabs.List>
                    <Tabs.Tab value="legs">{t('trades.tabs.legs')}</Tabs.Tab>
                    <Tabs.Tab value="versions">{t('trades.tabs.versions')}</Tabs.Tab>
                    <Tabs.Tab value="confirmations">{t('trades.tabs.confirmations')}</Tabs.Tab>
                    <Tabs.Tab value="mtm">{t('trades.tabs.mtm')}</Tabs.Tab>
                  </Tabs.List>
                  <Tabs.Panel value="legs" pt="md">
                    <Legs trade={data} />
                  </Tabs.Panel>
                  <Tabs.Panel value="versions" pt="md">
                    <Versions tradeId={data.id} />
                  </Tabs.Panel>
                  <Tabs.Panel value="confirmations" pt="md">
                    <Confirmations tradeId={data.id} />
                  </Tabs.Panel>
                  <Tabs.Panel value="mtm" pt="md">
                    <MarkToMarket tradeId={data.id} />
                  </Tabs.Panel>
                </Tabs>
              </Stack>
            );
          })()}
      </QueryState>
    </Stack>
  );
}
