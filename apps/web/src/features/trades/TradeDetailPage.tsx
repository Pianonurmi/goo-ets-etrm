import {
  ActionIcon,
  Alert,
  Anchor,
  Badge,
  Button,
  Group,
  Menu,
  Modal,
  Paper,
  Stack,
  Table,
  Tabs,
  Text,
  TextInput,
  Textarea,
} from '@mantine/core';
import { IconAlertTriangle, IconDots, IconFileText, IconInbox, IconPencil, IconX } from '@tabler/icons-react';
import { useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link, useParams } from 'react-router';
import { $api } from '@/shared/api/client';
import { ReceiveConfirmationModal, useInvalidateConfirmations } from '@/features/confirmations/ConfirmationModals';
import type { Schema } from '@/shared/api/types';
import { Can, useCan } from '@/shared/auth/Can';
import { ScrollTable } from '@/shared/components/ScrollTable';
import { DetailLayout, FactGrid } from '@/shared/components/DetailLayout';
import { EmptyState } from '@/shared/components/EmptyState';
import { Money, Quantity, Side } from '@/shared/components/Figures';
import { LifecycleTimeline } from '@/shared/components/LifecycleTimeline';
import { notifySuccess } from '@/shared/components/notify';
import { PageHeader } from '@/shared/components/PageHeader';
import { ProblemAlert } from '@/shared/components/ProblemAlert';
import { QueryState } from '@/shared/components/QueryState';
import { CardSkeleton, TableSkeleton } from '@/shared/components/Skeletons';
import { StatusBadge, statusLabel } from '@/shared/components/StatusBadge';
import { formatDate, formatDateTime, formatDecimal, formatMoney } from '@/shared/format/format';
import { useBooks, useCounterparties } from './hooks';
import { diffVersions } from './model/diff';
import { tradeSteps } from './model/lifecycle';
import type { TradeView } from './model/tradeForm';
import { CheckLimitsButton, DeliveryActions, SettleButton, TradeDocuments } from './TradeOperations';
import { TradeStatusBadge } from './TradeStatusBadge';

const OPEN = ['DRAFT', 'PENDING_APPROVAL', 'APPROVED', 'CONFIRMED', 'PARTIALLY_DELIVERED'];

type Version = Schema<'TradeVersionView'>;

function useRefreshTrades() {
  const queryClient = useQueryClient();
  return () =>
    queryClient.invalidateQueries({
      predicate: (q) =>
        typeof q.queryKey[1] === 'string' &&
        (q.queryKey[1].startsWith('/api/v1/trades') || q.queryKey[1].startsWith('/api/v1/workflow')),
    });
}

function useVersions(tradeId: string) {
  return $api.useQuery('get', '/api/v1/trades/{id}/versions', { params: { path: { id: tradeId } } });
}

/** The header actions: the next step of the trade as the primary action, the rest quieter or in the menu. */
function TradeActions({ trade }: { trade: TradeView }) {
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
  const more = open && (canAmend || canCancel);

  return (
    <>
      <CheckLimitsButton trade={trade} />
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
        <Button onClick={() => setModal('confirm')} data-testid="confirm-trade">
          {t('trades.confirm')}
        </Button>
      )}
      <SettleButton trade={trade} />
      {more && (
        <Menu position="bottom-end" withinPortal>
          <Menu.Target>
            <ActionIcon variant="default" size={36} aria-label={t('trades.more')} data-testid="trade-more">
              <IconDots size={18} />
            </ActionIcon>
          </Menu.Target>
          <Menu.Dropdown>
            {canAmend && (
              <Menu.Item
                component={Link}
                to={`/trades/${trade.id}/amend`}
                leftSection={<IconPencil size={14} />}
                data-testid="amend-trade"
              >
                {t('trades.amend')}
              </Menu.Item>
            )}
            {canCancel && (
              <Menu.Item
                color="red"
                leftSection={<IconX size={14} />}
                onClick={() => setModal('cancel')}
                data-testid="cancel-trade"
              >
                {t('trades.cancel')}
              </Menu.Item>
            )}
          </Menu.Dropdown>
        </Menu>
      )}
      <Modal opened={modal === 'cancel'} onClose={() => setModal(null)} title={t('trades.cancel')}>
        <Stack>
          <Text size="sm" c="dimmed">
            {t('trades.cancelHint')}
          </Text>
          <ProblemAlert error={cancel.error} />
          <Textarea
            label={t('inbox.reason')}
            required
            value={reason}
            onChange={(e) => setReason(e.currentTarget.value)}
          />
          <Group justify="flex-end">
            <Button variant="default" onClick={() => setModal(null)}>
              {t('common.cancel')}
            </Button>
            <Button
              color="red"
              disabled={!reason.trim()}
              loading={cancel.isPending}
              onClick={() => cancel.mutate({ ...path, body: { reason: reason.trim() } })}
            >
              {t('trades.cancel')}
            </Button>
          </Group>
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
            disabled={!reference.trim()}
            loading={confirm.isPending}
            onClick={() => confirm.mutate({ ...path, body: { confirmationRef: reference.trim() } })}
            data-testid="confirm-submit"
          >
            {t('trades.confirm')}
          </Button>
        </Stack>
      </Modal>
    </>
  );
}

function Legs({ trade }: { trade: TradeView }) {
  const { t } = useTranslation();
  return (
    <Stack gap="md">
      {trade.legs.map((leg) => (
        <Paper withBorder radius="lg" p="lg" key={leg.id}>
          <Group justify="space-between" align="baseline" mb="md">
            <Group gap="sm" align="baseline">
              <Text fw={600}>{t('trades.form.leg', { number: leg.legNo })}</Text>
              <Side side={leg.side} />
              <Quantity value={leg.quantity} unit={leg.unit} size="sm" />
              {leg.priceType === 'FIXED' && (
                <Text size="sm" c="dimmed" className="etrm-figure">
                  @ {formatDecimal(leg.price, 2, 6)} {leg.priceCurrency}
                  {leg.option ? ` ${t('trades.optionTerms.premiumSuffix')}` : ''}
                </Text>
              )}
            </Group>
            {leg.notional != null && <Money value={leg.notional} currency={leg.priceCurrency} fw={600} />}
          </Group>
          <FactGrid
            facts={[
              { label: t('trades.columns.product'), value: leg.allowanceProductCode ?? leg.criteriaSummary ?? '—' },
              {
                label: t('trades.form.priceType'),
                value:
                  leg.priceType === 'INDEX'
                    ? t('trades.ops.indexPrice', {
                        index: leg.priceIndex ?? '',
                        spread: formatDecimal(leg.price, 2, 6),
                      })
                    : t('trades.form.fixed'),
              },
              ...(leg.futuresContractCode
                ? [{ label: t('trades.form.futuresContract'), value: leg.futuresContractCode }]
                : []),
              {
                label: t('trades.bucket'),
                value: leg.bucketCode ? `${leg.bucketCode} (${leg.bucketMatch ?? ''})` : '—',
              },
              { label: t('trades.form.deliveryAccount'), value: leg.deliveryAccountId?.slice(-8) ?? '—' },
              { label: t('trades.form.counterpartyAccount'), value: leg.counterpartyAccountRef ?? '—' },
            ]}
          />
          {leg.option && (
            <div data-testid="trade-option-terms" data-leg={leg.legNo}>
              <Text size="sm" fw={600} mt="md" mb="xs">
                {t('trades.form.optionTerms')}
              </Text>
              <FactGrid
                facts={[
                  { label: t('trades.optionTerms.type'), value: t(`trades.optionTypes.${leg.option.optionType}`) },
                  {
                    label: t('trades.optionTerms.strike'),
                    value: `${formatDecimal(leg.option.strike, 2, 6)} ${leg.priceCurrency}`,
                  },
                  { label: t('trades.optionTerms.expiry'), value: formatDate(leg.option.expiryDate) },
                  { label: t('trades.optionTerms.underlying'), value: leg.option.underlyingContractCode },
                ]}
              />
            </div>
          )}
          {leg.option ? (
            <Text size="sm" c="dimmed" mt="md" data-testid="option-no-deliveries">
              {t('trades.optionTerms.noDeliveries')}
            </Text>
          ) : (
            <ScrollTable label={t('trades.tabs.legs')} minWidth={860} mt="md">
              <Table verticalSpacing="xs" highlightOnHover style={{ whiteSpace: 'nowrap' }}>
                <Table.Thead>
                  <Table.Tr>
                    <Table.Th w={40}>#</Table.Th>
                    <Table.Th>{t('trades.form.windowStart')}</Table.Th>
                    <Table.Th>{t('trades.form.windowEnd')}</Table.Th>
                    <Table.Th>{t('trades.form.deliveryDate')}</Table.Th>
                    <Table.Th ta="right">{t('trades.scheduled')}</Table.Th>
                    <Table.Th ta="right">{t('trades.delivered')}</Table.Th>
                    <Table.Th w={150}>{t('trades.columns.status')}</Table.Th>
                    <Table.Th />
                  </Table.Tr>
                </Table.Thead>
                <Table.Tbody className="etrm-figure">
                  {leg.deliveries.map((delivery) => (
                    <Table.Tr key={delivery.id}>
                      <Table.Td c="dimmed">{delivery.scheduleNo}</Table.Td>
                      <Table.Td>{formatDate(delivery.deliveryWindow.start)}</Table.Td>
                      <Table.Td>{formatDate(delivery.deliveryWindow.endExclusive)}</Table.Td>
                      <Table.Td>{formatDate(delivery.deliveryDate)}</Table.Td>
                      <Table.Td ta="right">{formatDecimal(delivery.quantity, 0, 3)}</Table.Td>
                      <Table.Td ta="right">{formatDecimal(delivery.deliveredQuantity, 0, 3)}</Table.Td>
                      <Table.Td>
                        <StatusBadge status={delivery.status} />
                      </Table.Td>
                      <Table.Td ta="right">
                        <DeliveryActions trade={trade} leg={leg} schedule={delivery} />
                      </Table.Td>
                    </Table.Tr>
                  ))}
                </Table.Tbody>
              </Table>
            </ScrollTable>
          )}
        </Paper>
      ))}
    </Stack>
  );
}

function Versions({ tradeId }: { tradeId: string }) {
  const { t } = useTranslation();
  const versions = useVersions(tradeId);
  const list: Version[] = [...(versions.data ?? [])].sort((a, b) => b.version - a.version);
  return (
    <QueryState isPending={versions.isPending} error={versions.error}>
      <Stack gap="sm">
        {list.map((version) => {
          const previous = list.find((candidate) => candidate.version === version.version - 1);
          const changes = previous ? diffVersions(previous.trade, version.trade) : [];
          return (
            <Paper withBorder radius="lg" p="md" key={version.version}>
              <Group justify="space-between">
                <Group gap="xs">
                  <Text fw={600}>{t('trades.version', { version: version.version })}</Text>
                  <StatusBadge status={version.status} size="sm" />
                </Group>
                <Text size="xs" c="dimmed">
                  {version.recordedBy} · {formatDateTime(version.recordedAt)}
                </Text>
              </Group>
              {version.reason && (
                <Text size="sm" mt={4}>
                  {version.reason}
                </Text>
              )}
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
            </Paper>
          );
        })}
      </Stack>
    </QueryState>
  );
}

function Confirmations({ tradeId }: { tradeId: string }) {
  const { t } = useTranslation();
  const invalidate = useInvalidateConfirmations();
  const [entering, setEntering] = useState(false);
  const confirmations = $api.useQuery('get', '/api/v1/trades/{tradeId}/confirmations', {
    params: { path: { tradeId } },
  });
  const issue = $api.useMutation('post', '/api/v1/trades/{tradeId}/confirmations', {
    onSuccess: () => {
      notifySuccess(t('confirmations.issued'));
      void invalidate();
    },
  });
  const list = confirmations.data ?? [];
  const current = list.find((c) => c.direction === 'OURS' && c.status === 'ISSUED');
  return (
    <QueryState isPending={confirmations.isPending} error={confirmations.error}>
      <Stack gap="sm">
        <Group justify="space-between">
          <Text size="sm" c="dimmed">
            {t('trades.confirmationsHint')}
          </Text>
          <Group gap="xs">
            {current && (
              <Can op="confirmation_receive">
                <Button variant="default" size="xs" onClick={() => setEntering(true)} data-testid="enter-theirs">
                  {t('confirmations.enterTheirs')}
                </Button>
              </Can>
            )}
            <Can op="confirmation_issue">
              <Button
                variant="light"
                size="xs"
                leftSection={<IconFileText size={14} />}
                loading={issue.isPending}
                onClick={() => issue.mutate({ params: { path: { tradeId } } })}
                data-testid="issue-confirmation"
              >
                {t('confirmations.issueOurs')}
              </Button>
            </Can>
          </Group>
        </Group>
        {list.length === 0 ? (
          <Paper withBorder radius="lg">
            <EmptyState title={t('trades.noConfirmations')} description={t('trades.noConfirmationsHint')} />
          </Paper>
        ) : (
          <Paper withBorder radius="lg">
            <ScrollTable label={t('trades.tabs.confirmations')} minWidth={720}>
              <Table verticalSpacing="sm" highlightOnHover>
                <Table.Thead>
                  <Table.Tr>
                    <Table.Th w={150}>{t('trades.columns.status')}</Table.Th>
                    <Table.Th>{t('trades.direction')}</Table.Th>
                    <Table.Th>{t('confirmations.theirReference')}</Table.Th>
                    <Table.Th>{t('trades.source')}</Table.Th>
                    <Table.Th ta="right">{t('trades.versionShort')}</Table.Th>
                    <Table.Th ta="right">{t('trades.differences')}</Table.Th>
                    <Table.Th>{t('trades.created')}</Table.Th>
                  </Table.Tr>
                </Table.Thead>
                <Table.Tbody>
                  {list.map((confirmation) => (
                    <Table.Tr key={confirmation.id}>
                      <Table.Td>
                        <Anchor component={Link} to={`/confirmations/${confirmation.id}`} underline="never">
                          <StatusBadge status={confirmation.status} style={{ cursor: 'pointer' }} />
                        </Anchor>
                      </Table.Td>
                      <Table.Td>{t(`confirmations.directions.${confirmation.direction}`)}</Table.Td>
                      <Table.Td>{confirmation.reference ?? '—'}</Table.Td>
                      <Table.Td>
                        {t(`trades.sources.${confirmation.source}`, { defaultValue: confirmation.source })}
                      </Table.Td>
                      <Table.Td ta="right" className="etrm-figure">
                        {confirmation.tradeVersion}
                      </Table.Td>
                      <Table.Td ta="right" className="etrm-figure">
                        {confirmation.differences.length}
                      </Table.Td>
                      <Table.Td className="etrm-figure">{formatDateTime(confirmation.createdAt)}</Table.Td>
                    </Table.Tr>
                  ))}
                </Table.Tbody>
              </Table>
            </ScrollTable>
          </Paper>
        )}
      </Stack>
      {entering && current && <ReceiveConfirmationModal ours={current} onClose={() => setEntering(false)} />}
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
  const rows = mtm.data ?? [];
  return (
    <Stack gap="sm">
      <TextInput
        type="date"
        label={t('trades.mtm.asOf')}
        description={t('trades.mtm.asOfHint')}
        value={date}
        onChange={(e) => setDate(e.currentTarget.value)}
        w={220}
      />
      {mtm.isPending ? (
        <TableSkeleton rows={3} />
      ) : mtm.error || rows.length === 0 ? (
        <Paper withBorder radius="lg">
          <EmptyState title={t('trades.noValuation')} description={t('trades.noValuationHint')} />
        </Paper>
      ) : (
        <Paper withBorder radius="lg">
          <ScrollTable label={t('trades.tabs.mtm')} minWidth={960}>
            <Table verticalSpacing="sm">
              <Table.Thead>
                <Table.Tr>
                  <Table.Th>{t('trades.form.deliveryDate')}</Table.Th>
                  <Table.Th ta="right">{t('trades.columns.quantity')}</Table.Th>
                  <Table.Th ta="right">{t('trades.mtm.contractPrice')}</Table.Th>
                  <Table.Th ta="right">{t('trades.mtm.marketPrice')}</Table.Th>
                  <Table.Th ta="right">{t('trades.mtm.discountFactor')}</Table.Th>
                  <Table.Th ta="right">{t('trades.mtm.mtm')}</Table.Th>
                  <Table.Th ta="right">{t('trades.mtm.mtmBase')}</Table.Th>
                  <Table.Th>{t('trades.mtm.curve')}</Table.Th>
                  <Table.Th w={150}>{t('trades.columns.status')}</Table.Th>
                </Table.Tr>
              </Table.Thead>
              <Table.Tbody className="etrm-figure">
                {rows.map((row) => (
                  <Table.Tr key={`${row.runId}-${row.scheduleId}`}>
                    <Table.Td>{formatDate(row.deliveryDate)}</Table.Td>
                    <Table.Td ta="right">{formatDecimal(row.quantity, 0, 3)}</Table.Td>
                    <Table.Td ta="right">{formatMoney(row.contractPrice, row.contractCurrency)}</Table.Td>
                    <Table.Td ta="right">
                      {row.marketPriceContract != null
                        ? formatMoney(row.marketPriceContract, row.contractCurrency)
                        : '—'}
                    </Table.Td>
                    <Table.Td ta="right" title={row.discountCurveCode ?? undefined}>
                      {row.discountFactor != null ? formatDecimal(row.discountFactor, 2, 6) : '—'}
                    </Table.Td>
                    <Table.Td ta="right">
                      {row.mtm != null ? (
                        <Money value={row.mtm} currency={row.contractCurrency} signed size="sm" />
                      ) : (
                        '—'
                      )}
                    </Table.Td>
                    <Table.Td ta="right">
                      {row.mtmBase != null ? <Money value={row.mtmBase} signed size="sm" /> : '—'}
                    </Table.Td>
                    <Table.Td>{row.curveCode ? `${row.curveCode} ${formatDate(row.curveDate)}` : '—'}</Table.Td>
                    <Table.Td>
                      <StatusBadge status={row.status} />
                    </Table.Td>
                  </Table.Tr>
                ))}
              </Table.Tbody>
            </Table>
          </ScrollTable>
        </Paper>
      )}
    </Stack>
  );
}

function Findings({ trade }: { trade: TradeView }) {
  const { t } = useTranslation();
  const findings = trade.approvalFindings ?? [];
  if (findings.length === 0) return null;
  return (
    <Alert
      color={trade.requiresRiskApproval ? 'red' : 'yellow'}
      title={t('trades.findings')}
      icon={<IconAlertTriangle size={18} />}
      data-testid="findings"
    >
      <Stack gap={4}>
        {findings.map((finding) => (
          <Text key={finding.code} size="sm">
            <Text span fw={600} size="sm">
              {t(`trades.severities.${finding.severity}`, { defaultValue: statusLabel(finding.severity) })}
            </Text>{' '}
            · {finding.message}
          </Text>
        ))}
      </Stack>
    </Alert>
  );
}

function TradeBody({ trade }: { trade: TradeView }) {
  const { t } = useTranslation();
  const counterparties = useCounterparties();
  const books = useBooks(trade.legalEntityId);
  const versions = useVersions(trade.id);
  const steps = tradeSteps(trade, versions.data ?? [], (key) => t(`trades.lifecycle.${key}`));
  const currency = trade.legs[0]?.priceCurrency;
  return (
    <Stack gap="lg">
      <PageHeader
        breadcrumbs={[{ label: t('trades.title'), to: '/trades' }, { label: trade.tradeNumber }]}
        title={<span data-testid="trade-number">{trade.tradeNumber}</span>}
        badge={
          <Group gap={6} wrap="nowrap">
            <TradeStatusBadge status={trade.status} />
            <Badge variant="default" radius="xl" tt="none">
              v{trade.version}
            </Badge>
          </Group>
        }
        description={`${t(`trades.families.${trade.productFamily}`)} · ${t(`trades.instruments.${trade.instrumentType}`)} · ${t('trades.tradedOn', { date: formatDate(trade.tradeDate) })}`}
        actions={<TradeActions trade={trade} />}
      />
      <DetailLayout
        summary={[
          {
            label: t('trades.columns.notional'),
            value: trade.notional != null ? <Money value={trade.notional} currency={currency} fw={600} /> : '—',
          },
          {
            label: t('trades.columns.counterparty'),
            value: counterparties.byId.get(trade.counterpartyId)?.name ?? trade.counterpartyId,
          },
          { label: t('trades.columns.book'), value: books.byId.get(trade.bookId)?.code ?? trade.bookId },
          { label: t('trades.columns.trader'), value: trade.trader },
          {
            label: t('trades.form.broker'),
            value: trade.brokerId ? (counterparties.byId.get(trade.brokerId)?.name ?? trade.brokerId) : '—',
          },
          { label: t('trades.form.masterAgreement'), value: trade.masterAgreementRef ?? '—' },
          { label: t('trades.columns.externalRef'), value: trade.externalRef ?? '—' },
          { label: 'UTI', value: trade.regulatory?.uti ?? '—' },
          ...(trade.intercompanyLinkId
            ? [
                {
                  label: t('trades.ops.intercompany'),
                  value: (
                    <Badge variant="light" color="grape" tt="none" data-testid="intercompany">
                      {t('trades.ops.mirrored')}
                    </Badge>
                  ),
                },
              ]
            : []),
        ]}
        aside={<LifecycleTimeline title={t('trades.lifecycle.title')} steps={steps} testId="trade-lifecycle" />}
      >
        <Findings trade={trade} />
        <Tabs defaultValue="legs" keepMounted={false}>
          <Tabs.List>
            <Tabs.Tab value="legs">{t('trades.tabs.legs')}</Tabs.Tab>
            <Tabs.Tab value="versions">{t('trades.tabs.versions')}</Tabs.Tab>
            <Tabs.Tab value="confirmations">{t('trades.tabs.confirmations')}</Tabs.Tab>
            <Tabs.Tab value="mtm">{t('trades.tabs.mtm')}</Tabs.Tab>
            <Tabs.Tab value="documents" data-testid="tab-documents">
              {t('trades.tabs.documents')}
            </Tabs.Tab>
          </Tabs.List>
          <Tabs.Panel value="legs" pt="md">
            <Legs trade={trade} />
          </Tabs.Panel>
          <Tabs.Panel value="versions" pt="md">
            <Versions tradeId={trade.id} />
          </Tabs.Panel>
          <Tabs.Panel value="confirmations" pt="md">
            <Confirmations tradeId={trade.id} />
          </Tabs.Panel>
          <Tabs.Panel value="mtm" pt="md">
            <MarkToMarket tradeId={trade.id} />
          </Tabs.Panel>
          <Tabs.Panel value="documents" pt="md">
            <TradeDocuments tradeId={trade.id} />
          </Tabs.Panel>
        </Tabs>
      </DetailLayout>
    </Stack>
  );
}

export function TradeDetailPage() {
  const { tradeId = '' } = useParams();
  const trade = $api.useQuery('get', '/api/v1/trades/{id}', { params: { path: { id: tradeId } } });
  return (
    <QueryState isPending={false} error={trade.error}>
      {trade.data ? (
        <TradeBody trade={trade.data} />
      ) : (
        <Stack gap="lg">
          <CardSkeleton height={90} />
          <CardSkeleton height={160} />
          <CardSkeleton height={240} />
        </Stack>
      )}
    </QueryState>
  );
}
