import { Alert, Anchor, Badge, Button, Group, Menu, Modal, Select, Stack, Table, Text, TextInput } from '@mantine/core';
import { IconChevronDown, IconShieldCheck } from '@tabler/icons-react';
import { useQueryClient } from '@tanstack/react-query';
import dayjs from 'dayjs';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link } from 'react-router';
import { $api } from '@/shared/api/client';
import { download } from '@/shared/api/download';
import { decimal, type Schema } from '@/shared/api/types';
import { useCan } from '@/shared/auth/Can';
import { FactGrid } from '@/shared/components/DetailLayout';
import { notifySuccess } from '@/shared/components/notify';
import { ProblemAlert } from '@/shared/components/ProblemAlert';
import { QueryState } from '@/shared/components/QueryState';
import { StatusBadge } from '@/shared/components/StatusBadge';
import { formatDateTime, formatDecimal } from '@/shared/format/format';
import { newKey } from '@/shared/ids';
import { canCheckLimits, canSettle, deliveryActions, type DeliveryAction } from './model/operations';
import { unitOf } from './model/tradeForm';

type Trade = Schema<'TradeView'>;
type Leg = Schema<'TradeLegView'>;
type Schedule = Schema<'DeliveryScheduleView'>;

function useRefresh() {
  const queryClient = useQueryClient();
  return () =>
    queryClient.invalidateQueries({
      predicate: (q) =>
        typeof q.queryKey[1] === 'string' &&
        ['/api/v1/trades', '/api/v1/inventory', '/api/v1/registry', '/api/v1/workflow'].some((prefix) =>
          (q.queryKey[1] as string).startsWith(prefix),
        ),
    });
}

/** Runs the pre-trade checks again against today's limits (nothing is stored) and lists what they find. */
export function CheckLimitsButton({ trade }: { trade: Trade }) {
  const { t } = useTranslation();
  const allowed = useCan('risk_preTradeCheck');
  const [opened, setOpened] = useState(false);
  const check = $api.useMutation('post', '/api/v1/risk/pre-trade-check', { meta: { notify: false } });
  if (!allowed || !canCheckLimits(trade)) return null;
  const run = () => {
    setOpened(true);
    check.mutate({ params: { query: { tradeId: trade.id } } });
  };
  return (
    <>
      <Button variant="default" leftSection={<IconShieldCheck size={16} />} onClick={run} data-testid="check-limits">
        {t('trades.ops.checkLimits')}
      </Button>
      <Modal opened={opened} onClose={() => setOpened(false)} title={t('trades.ops.checkLimits')} size="lg">
        <Stack>
          <Text size="sm" c="dimmed">
            {t('trades.ops.checkLimitsHint')}
          </Text>
          <ProblemAlert error={check.error} />
          <QueryState isPending={check.isPending} error={null}>
            {check.data &&
              (check.data.length === 0 ? (
                <Alert color="teal" data-testid="limit-findings">
                  {t('trades.ops.withinLimits')}
                </Alert>
              ) : (
                <Table withTableBorder data-testid="limit-findings">
                  <Table.Tbody>
                    {check.data.map((finding) => (
                      <Table.Tr key={`${finding.code}-${finding.message}`}>
                        <Table.Td w={200}>
                          <StatusBadge status={finding.severity === 'WARN' ? 'WARNING' : finding.severity} />
                        </Table.Td>
                        <Table.Td>
                          <Text size="sm">{finding.message}</Text>
                        </Table.Td>
                      </Table.Tr>
                    ))}
                  </Table.Tbody>
                </Table>
              ))}
          </QueryState>
        </Stack>
      </Modal>
    </>
  );
}

/** Marks a delivered trade settled (payment outside the system, or reconciled cash). */
export function SettleButton({ trade }: { trade: Trade }) {
  const { t } = useTranslation();
  const refresh = useRefresh();
  const allowed = useCan('trade_settle');
  const settle = $api.useMutation('post', '/api/v1/trades/{id}/settle', {
    onSuccess: () => {
      notifySuccess(t('trades.ops.settled'));
      void refresh();
    },
  });
  if (!allowed || !canSettle(trade)) return null;
  return (
    <Button
      loading={settle.isPending}
      onClick={() => settle.mutate({ params: { path: { id: trade.id } } })}
      data-testid="settle-trade"
    >
      {t('trades.ops.settle')}
    </Button>
  );
}

const OPERATION: Record<
  DeliveryAction,
  'trade_recordDelivery' | 'delivery_deliverAllowances' | 'delivery_matchReceipt'
> = {
  record: 'trade_recordDelivery',
  deliverAllowances: 'delivery_deliverAllowances',
  matchReceipt: 'delivery_matchReceipt',
};

/** The delivery actions of one schedule row, as a small menu. */
export function DeliveryActions({ trade, leg, schedule }: { trade: Trade; leg: Leg; schedule: Schedule }) {
  const { t } = useTranslation();
  const record = useCan(OPERATION.record);
  const deliver = useCan(OPERATION.deliverAllowances);
  const match = useCan(OPERATION.matchReceipt);
  const allowed: Record<DeliveryAction, boolean> = { record, deliverAllowances: deliver, matchReceipt: match };
  const actions = deliveryActions(trade, leg, schedule).filter((action) => allowed[action]);
  const [open, setOpen] = useState<DeliveryAction | null>(null);
  if (actions.length === 0) return null;
  const testId = `delivery-actions-${leg.legNo}-${schedule.scheduleNo}`;
  return (
    <>
      <Menu position="bottom-end" withinPortal>
        <Menu.Target>
          <Button size="compact-xs" variant="light" rightSection={<IconChevronDown size={12} />} data-testid={testId}>
            {t('trades.ops.deliver')}
          </Button>
        </Menu.Target>
        <Menu.Dropdown>
          {actions.map((action) => (
            <Menu.Item key={action} onClick={() => setOpen(action)} data-testid={`${testId}-${action}`}>
              {t(`trades.ops.actions.${action}`)}
            </Menu.Item>
          ))}
        </Menu.Dropdown>
      </Menu>
      {open === 'record' && <RecordDeliveryModal trade={trade} schedule={schedule} onClose={() => setOpen(null)} />}
      {open === 'deliverAllowances' && (
        <DeliverAllowancesModal trade={trade} leg={leg} schedule={schedule} onClose={() => setOpen(null)} />
      )}
      {open === 'matchReceipt' && (
        <MatchReceiptModal trade={trade} leg={leg} schedule={schedule} onClose={() => setOpen(null)} />
      )}
    </>
  );
}

/** What is still owed on the schedule, from the obligation the delivery modules work with. */
function ObligationFacts({ scheduleId }: { scheduleId: string }) {
  const { t } = useTranslation();
  const obligation = $api.useQuery('get', '/api/v1/trades/obligations/{scheduleId}', {
    params: { path: { scheduleId } },
  });
  const data = obligation.data;
  return (
    <QueryState isPending={obligation.isPending} error={obligation.error}>
      {data && (
        <FactGrid
          cols={2}
          facts={[
            {
              label: t('trades.ops.remaining'),
              value: `${formatDecimal(data.remainingQuantity, 0, 3)} ${unitOf(data.productFamily)}`,
              testId: 'obligation-remaining',
            },
            { label: t('trades.form.deliveryDate'), value: data.deliveryDate },
            { label: t('trades.form.counterpartyAccount'), value: data.counterpartyAccountRef ?? '—' },
            { label: t('trades.columns.status'), value: <StatusBadge status={data.status} /> },
          ]}
        />
      )}
    </QueryState>
  );
}

function RecordDeliveryModal({ trade, schedule, onClose }: { trade: Trade; schedule: Schedule; onClose: () => void }) {
  const { t } = useTranslation();
  const refresh = useRefresh();
  const [key] = useState(() => newKey('ui-delivery'));
  const [deliveredOn, setDeliveredOn] = useState(dayjs().format('YYYY-MM-DD'));
  const [quantity, setQuantity] = useState(String(schedule.remainingQuantity));
  const [reference, setReference] = useState('');
  const recordDelivery = $api.useMutation('post', '/api/v1/trades/{id}/deliveries', {
    meta: { notify: false },
    onSuccess: () => {
      notifySuccess(t('trades.ops.recorded'));
      void refresh();
      onClose();
    },
  });
  return (
    <Modal opened onClose={onClose} title={t('trades.ops.actions.record')}>
      <Stack>
        <Text size="sm" c="dimmed">
          {t('trades.ops.recordHint')}
        </Text>
        <ObligationFacts scheduleId={schedule.id} />
        <ProblemAlert error={recordDelivery.error} />
        <TextInput
          type="date"
          label={t('trades.ops.deliveredOn')}
          value={deliveredOn}
          onChange={(e) => setDeliveredOn(e.currentTarget.value)}
          required
        />
        <TextInput
          label={t('trades.columns.quantity')}
          inputMode="decimal"
          value={quantity}
          onChange={(e) => setQuantity(e.currentTarget.value)}
          required
          data-testid="delivery-quantity"
        />
        <TextInput
          label={t('trades.ops.reference')}
          placeholder={t('trades.ops.referenceHint')}
          value={reference}
          onChange={(e) => setReference(e.currentTarget.value)}
        />
        <Button
          disabled={!quantity.trim() || !deliveredOn}
          loading={recordDelivery.isPending}
          onClick={() =>
            recordDelivery.mutate({
              params: { path: { id: trade.id } },
              body: {
                scheduleId: schedule.id,
                deliveredOn,
                quantity: decimal(quantity.trim()),
                reference: reference.trim() || null,
                idempotencyKey: key,
              },
            })
          }
          data-testid="delivery-submit"
        >
          {t('trades.ops.actions.record')}
        </Button>
      </Stack>
    </Modal>
  );
}

function DeliverAllowancesModal({
  trade,
  leg,
  schedule,
  onClose,
}: {
  trade: Trade;
  leg: Leg;
  schedule: Schedule;
  onClose: () => void;
}) {
  const { t } = useTranslation();
  const refresh = useRefresh();
  const [key] = useState(() => newKey('ui-allowances'));
  const [quantity, setQuantity] = useState(String(schedule.remainingQuantity));
  const deliver = $api.useMutation('post', '/api/v1/deliveries/allowances', {
    meta: { notify: false },
    onSuccess: () => {
      notifySuccess(t('trades.ops.transferSubmitted'));
      void refresh();
    },
  });
  return (
    <Modal opened onClose={onClose} title={t('trades.ops.actions.deliverAllowances')}>
      <Stack>
        <Text size="sm" c="dimmed">
          {t('trades.ops.deliverAllowancesHint', { product: leg.allowanceProductCode ?? '', trade: trade.tradeNumber })}
        </Text>
        <ObligationFacts scheduleId={schedule.id} />
        <ProblemAlert error={deliver.error} />
        {deliver.data ? (
          <Alert color="teal" data-testid="transfer-result">
            <Group gap="xs">
              <Text size="sm">{t('trades.ops.transferCreated')}</Text>
              <StatusBadge status={deliver.data.status} />
              <Anchor component={Link} to={`/registry/instructions/${deliver.data.id}`} size="sm">
                {t('trades.ops.openInstruction')}
              </Anchor>
            </Group>
          </Alert>
        ) : (
          <>
            <TextInput
              label={t('trades.ops.tonnes')}
              inputMode="numeric"
              value={quantity}
              onChange={(e) => setQuantity(e.currentTarget.value)}
              required
              data-testid="transfer-quantity"
            />
            <Button
              disabled={!/^\d+$/.test(quantity.trim())}
              loading={deliver.isPending}
              onClick={() =>
                deliver.mutate({
                  body: { scheduleId: schedule.id, quantity: Number(quantity.trim()), idempotencyKey: key },
                })
              }
              data-testid="transfer-submit"
            >
              {t('trades.ops.submitTransfer')}
            </Button>
          </>
        )}
      </Stack>
    </Modal>
  );
}

/** Matches a received lot (GoO) or an incoming allowance movement (EUA) to a purchase schedule. */
function MatchReceiptModal({
  trade,
  leg,
  schedule,
  onClose,
}: {
  trade: Trade;
  leg: Leg;
  schedule: Schedule;
  onClose: () => void;
}) {
  const { t } = useTranslation();
  const refresh = useRefresh();
  const allowance = trade.productFamily === 'EMISSION_ALLOWANCE';
  const [lotId, setLotId] = useState<string | null>(null);
  const [movement, setMovement] = useState<string | null>(null);
  const lots = $api.useQuery(
    'get',
    '/api/v1/inventory/lots',
    { params: { query: { holderEntityId: trade.legalEntityId, status: ['HELD'], limit: 500 } } },
    { enabled: !allowance },
  );
  const holdings = $api.useQuery(
    'get',
    '/api/v1/inventory/holdings',
    { params: { query: { holderEntityId: trade.legalEntityId } } },
    { enabled: allowance },
  );
  const holding = (holdings.data ?? []).find((candidate) => candidate.productCode === leg.allowanceProductCode);
  const movements = $api.useQuery(
    'get',
    '/api/v1/inventory/holdings/{id}/movements',
    { params: { path: { id: holding?.id ?? '' } } },
    { enabled: Boolean(holding) },
  );
  const match = $api.useMutation('post', '/api/v1/deliveries/receipts', {
    meta: { notify: false },
    onSuccess: (result) => {
      notifySuccess(t('trades.ops.matched', { quantity: formatDecimal(result.quantity, 0, 3) }));
      void refresh();
      onClose();
    },
  });
  const incoming = (movements.data ?? []).filter(
    (candidate) => candidate.type === 'TRANSFER_IN' || candidate.type === 'PURCHASE',
  );
  const ready = allowance ? movement !== null && holding !== undefined : lotId !== null;
  return (
    <Modal opened onClose={onClose} title={t('trades.ops.actions.matchReceipt')} size="lg">
      <Stack>
        <Text size="sm" c="dimmed">
          {allowance ? t('trades.ops.matchAllowanceHint') : t('trades.ops.matchLotHint')}
        </Text>
        <ObligationFacts scheduleId={schedule.id} />
        <ProblemAlert error={match.error} />
        {allowance ? (
          <Select
            label={t('trades.ops.movement')}
            data={incoming.map((candidate) => ({
              value: String(candidate.seq),
              label: `${candidate.effectiveDate} · ${formatDecimal(candidate.quantity, 0, 0)} t · ${candidate.reference ?? candidate.type}`,
            }))}
            value={movement}
            onChange={setMovement}
            searchable
            nothingFoundMessage={t('trades.ops.nothingToMatch')}
            data-testid="match-movement"
          />
        ) : (
          <Select
            label={t('trades.ops.lot')}
            data={(lots.data ?? []).map((lot) => ({
              value: lot.id,
              label: `${lot.deviceName ?? lot.deviceGsrn} · ${formatDecimal(lot.quantityMwh, 0, 0)} MWh · ${lot.acquisitionRef ?? lot.id.slice(-8)}`,
            }))}
            value={lotId}
            onChange={setLotId}
            searchable
            nothingFoundMessage={t('trades.ops.nothingToMatch')}
            data-testid="match-lot"
          />
        )}
        <Button
          disabled={!ready}
          loading={match.isPending}
          onClick={() =>
            match.mutate({
              body: allowance
                ? { scheduleId: schedule.id, holdingId: holding?.id, movementSeq: Number(movement) }
                : { scheduleId: schedule.id, lotId },
            })
          }
          data-testid="match-submit"
        >
          {t('trades.ops.match')}
        </Button>
      </Stack>
    </Modal>
  );
}

/** Issued documents of the trade (our confirmations), with their content hash checked on request. */
export function TradeDocuments({ tradeId }: { tradeId: string }) {
  const { t } = useTranslation();
  const confirmations = $api.useQuery('get', '/api/v1/trades/{tradeId}/confirmations', {
    params: { path: { tradeId } },
  });
  const ours = (confirmations.data ?? []).filter((confirmation) => confirmation.direction === 'OURS');
  return (
    <QueryState isPending={confirmations.isPending} error={confirmations.error}>
      {ours.length === 0 ? (
        <Text size="sm" c="dimmed">
          {t('trades.ops.noDocuments')}
        </Text>
      ) : (
        <Table withTableBorder striped data-testid="trade-documents">
          <Table.Thead>
            <Table.Tr>
              <Table.Th>{t('trades.ops.document')}</Table.Th>
              <Table.Th>{t('trades.created')}</Table.Th>
              <Table.Th>{t('trades.ops.size')}</Table.Th>
              <Table.Th>SHA-256</Table.Th>
              <Table.Th />
            </Table.Tr>
          </Table.Thead>
          <Table.Tbody>
            {ours.map((confirmation) => (
              <ConfirmationDocuments key={confirmation.id} confirmationId={confirmation.id} />
            ))}
          </Table.Tbody>
        </Table>
      )}
    </QueryState>
  );
}

function ConfirmationDocuments({ confirmationId }: { confirmationId: string }) {
  const documents = $api.useQuery('get', '/api/v1/documents', {
    params: { query: { ownerType: 'TRADE_CONFIRMATION', ownerRef: confirmationId } },
  });
  return (
    <>
      {(documents.data ?? []).map((document) => (
        <DocumentRow key={document.id} documentId={document.id} />
      ))}
    </>
  );
}

function DocumentRow({ documentId }: { documentId: string }) {
  const { t } = useTranslation();
  const document = $api.useQuery('get', '/api/v1/documents/{id}', { params: { path: { id: documentId } } });
  const verify = $api.useQuery(
    'get',
    '/api/v1/documents/{id}/verify',
    { params: { path: { id: documentId } } },
    { enabled: false },
  );
  const data = document.data;
  if (!data) return null;
  const intact = verify.data?.intact;
  return (
    <Table.Tr>
      <Table.Td>
        <Anchor
          component="button"
          size="sm"
          onClick={() => void download(`/api/v1/documents/${data.id}/content`, data.fileName)}
        >
          {data.title}
        </Anchor>
        <Text size="xs" c="dimmed">
          {data.fileName}
        </Text>
      </Table.Td>
      <Table.Td>
        <Text size="sm">{formatDateTime(data.createdAt)}</Text>
        <Text size="xs" c="dimmed">
          {data.createdBy}
        </Text>
      </Table.Td>
      <Table.Td>{t('trades.ops.kilobytes', { size: formatDecimal(data.sizeBytes / 1024, 0, 1) })}</Table.Td>
      <Table.Td>
        <Text size="xs" ff="monospace" title={data.sha256}>
          {data.sha256.slice(0, 16)}…
        </Text>
      </Table.Td>
      <Table.Td>
        {verify.data ? (
          <Badge color={intact === true ? 'teal' : 'red'} variant="light" data-testid="document-verified">
            {intact === true ? t('trades.ops.intact') : t('trades.ops.altered')}
          </Badge>
        ) : (
          <Button
            size="compact-xs"
            variant="light"
            loading={verify.isFetching}
            onClick={() => void verify.refetch()}
            data-testid="verify-document"
          >
            {t('trades.ops.verify')}
          </Button>
        )}
      </Table.Td>
    </Table.Tr>
  );
}
