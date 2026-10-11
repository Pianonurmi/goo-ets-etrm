import {
  Alert,
  Anchor,
  Button,
  Checkbox,
  Group,
  Modal,
  Paper,
  Select,
  Stack,
  Table,
  Text,
  TextInput,
  Textarea,
  Title,
} from '@mantine/core';
import { IconHandGrab, IconInbox } from '@tabler/icons-react';
import dayjs from 'dayjs';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link, useParams } from 'react-router';
import { $api } from '@/shared/api/client';
import { decimal, type Body, type Schema } from '@/shared/api/types';
import { Can } from '@/shared/auth/Can';
import { DetailLayout, FactGrid } from '@/shared/components/DetailLayout';
import { LifecycleTimeline } from '@/shared/components/LifecycleTimeline';
import { notifySuccess } from '@/shared/components/notify';
import { ProblemAlert } from '@/shared/components/ProblemAlert';
import { PageHeader } from '@/shared/components/PageHeader';
import { QueryState } from '@/shared/components/QueryState';
import { CardSkeleton } from '@/shared/components/Skeletons';
import { StatusBadge } from '@/shared/components/StatusBadge';
import { formatDate } from '@/shared/format/format';
import { ageInDays, breakActions, breakSteps, breakSubject, facts, mismatches, type Break } from './model/breaks';
import { BreakStatusBadge, useInvalidateReconciliation } from './ReconciliationPage';

type Lot = Schema<'LotView'>;
type Receipt = Body<'ReceiveLots'>;
type Dialog =
  | 'confirmInstruction'
  | 'withdrawLot'
  | 'recordReceipt'
  | 'replaceLot'
  | 'correctLot'
  | 'correctHolding'
  | 'accept'
  | null;

/** A note, and optionally the registry's reference: the common shape of most resolutions. */
function NoteModal({
  title,
  hint,
  action,
  withReference,
  pending,
  error,
  onSubmit,
  onClose,
}: {
  title: string;
  hint: string;
  action: string;
  withReference?: boolean;
  pending: boolean;
  error: unknown;
  onSubmit: (note: string, reference: string) => void;
  onClose: () => void;
}) {
  const { t } = useTranslation();
  const [note, setNote] = useState('');
  const [reference, setReference] = useState('');
  return (
    <Modal opened onClose={onClose} title={title}>
      <Stack>
        <ProblemAlert error={error} />
        <Text size="sm" c="dimmed">
          {hint}
        </Text>
        {withReference && (
          <TextInput
            label={t('registry.externalRef')}
            value={reference}
            onChange={(e) => setReference(e.currentTarget.value)}
          />
        )}
        <Textarea
          label={t('reconciliation.note')}
          value={note}
          onChange={(e) => setNote(e.currentTarget.value)}
          required
          autosize
          minRows={2}
          data-testid="break-note"
        />
        <Button
          disabled={!note.trim()}
          loading={pending}
          onClick={() => onSubmit(note.trim(), reference.trim())}
          data-testid="break-note-submit"
        >
          {action}
        </Button>
      </Stack>
    </Modal>
  );
}

/** The certificates as the registry shows them: received as a new lot, or replacing ours. */
function ReceiptModal({
  brk,
  holderEntityId,
  lot,
  replace,
  onClose,
}: {
  brk: Break;
  holderEntityId: string;
  lot?: Lot;
  replace: boolean;
  onClose: () => void;
}) {
  const { t } = useTranslation();
  const invalidate = useInvalidateReconciliation();
  const range = lot?.range ?? brk.range ?? { start: 0, end: 0 };
  const [deviceGsrn, setDeviceGsrn] = useState(lot?.deviceGsrn ?? '');
  const [productionStart, setProductionStart] = useState(lot?.productionPeriod.start ?? '');
  const [productionEnd, setProductionEnd] = useState(lot?.productionPeriod.endExclusive ?? '');
  const [issueDate, setIssueDate] = useState(lot?.issueDate ?? '');
  const [issuingBody, setIssuingBody] = useState(lot?.issuingBody ?? '');
  const [issuingCountry, setIssuingCountry] = useState(lot?.issuingCountry ?? '');
  const [source, setSource] = useState<Receipt['acquisitionSource']>(lot?.acquisitionSource ?? 'TRANSFER_IN');
  const [cost, setCost] = useState(lot?.costBasisPerMwh != null ? String(lot.costBasisPerMwh) : '');
  const [note, setNote] = useState('');
  const done = () => {
    notifySuccess(t('reconciliation.resolved'));
    void invalidate();
    onClose();
  };
  const record = $api.useMutation('post', '/api/v1/registry/breaks/{id}/record-receipt', {
    meta: { notify: false },
    onSuccess: done,
  });
  const replaceLot = $api.useMutation('post', '/api/v1/registry/breaks/{id}/replace-lot', {
    meta: { notify: false },
    onSuccess: done,
  });
  const mutation = replace ? replaceLot : record;
  const ready =
    deviceGsrn && productionStart && productionEnd && issueDate && issuingBody && issuingCountry && note.trim();
  const submit = () => {
    const receipt: Receipt = {
      holderEntityId,
      registryAccountId: brk.registryAccountId ?? '',
      issuanceRef: brk.issuanceRef ?? '',
      range,
      deviceGsrn,
      productionPeriod: { start: productionStart, endExclusive: productionEnd },
      issueDate,
      issuingBody,
      issuingCountry,
      acquisitionSource: source,
      costBasisPerMwh: cost.trim() ? decimal(cost) : undefined,
      costCurrency: cost.trim() ? 'EUR' : undefined,
    } as Receipt;
    mutation.mutate({ params: { path: { id: brk.id } }, body: { receipt, note: note.trim() } });
  };
  return (
    <Modal
      opened
      onClose={onClose}
      title={replace ? t('reconciliation.actions.replaceLot') : t('reconciliation.actions.recordReceipt')}
      size="lg"
    >
      <Stack>
        <ProblemAlert error={mutation.error} />
        <Text size="sm" c="dimmed">
          {replace ? t('reconciliation.replaceHint') : t('reconciliation.receiptHint')}
        </Text>
        <Text size="sm">
          {t('reconciliation.certificates', { issuance: brk.issuanceRef, start: range.start, end: range.end })}
        </Text>
        <Group grow>
          <TextInput
            label={t('reconciliation.deviceGsrn')}
            value={deviceGsrn}
            onChange={(e) => setDeviceGsrn(e.currentTarget.value.trim())}
            required
            data-testid="receipt-device"
          />
          <Select
            label={t('reconciliation.acquisitionSource')}
            data={(['ISSUANCE', 'TRANSFER_IN', 'OPENING_BALANCE'] as const).map((value) => ({
              value,
              label: t(`reconciliation.sources.${value}`),
            }))}
            value={source}
            allowDeselect={false}
            onChange={(value) => value && setSource(value)}
          />
        </Group>
        <Group grow>
          <TextInput
            type="date"
            label={t('reconciliation.productionStart')}
            value={productionStart}
            onChange={(e) => setProductionStart(e.currentTarget.value)}
            required
          />
          <TextInput
            type="date"
            label={t('reconciliation.productionEnd')}
            value={productionEnd}
            onChange={(e) => setProductionEnd(e.currentTarget.value)}
            required
          />
          <TextInput
            type="date"
            label={t('reconciliation.issueDate')}
            value={issueDate}
            onChange={(e) => setIssueDate(e.currentTarget.value)}
            required
          />
        </Group>
        <Group grow>
          <TextInput
            label={t('reconciliation.issuingBody')}
            value={issuingBody}
            onChange={(e) => setIssuingBody(e.currentTarget.value)}
            required
          />
          <TextInput
            label={t('reconciliation.issuingCountry')}
            value={issuingCountry}
            maxLength={2}
            onChange={(e) => setIssuingCountry(e.currentTarget.value.toUpperCase())}
            required
          />
          <TextInput
            label={t('reconciliation.costBasis')}
            inputMode="decimal"
            value={cost}
            onChange={(e) => setCost(e.currentTarget.value)}
          />
        </Group>
        <Textarea
          label={t('reconciliation.note')}
          value={note}
          onChange={(e) => setNote(e.currentTarget.value)}
          required
          autosize
        />
        <Button disabled={!ready} loading={mutation.isPending} onClick={submit}>
          {replace ? t('reconciliation.actions.replaceLot') : t('reconciliation.actions.recordReceipt')}
        </Button>
      </Stack>
    </Modal>
  );
}

/** Fields of our lot that may be corrected in place (support, expiry, cost); the rest needs a replacement. */
function CorrectLotModal({ brk, lot, onClose }: { brk: Break; lot: Lot; onClose: () => void }) {
  const { t } = useTranslation();
  const invalidate = useInvalidateReconciliation();
  const [expiry, setExpiry] = useState(lot.expiryDate);
  const [production, setProduction] = useState(Boolean(lot.supportProduction));
  const [investment, setInvestment] = useState(Boolean(lot.supportInvestment));
  const [reason, setReason] = useState('');
  const correct = $api.useMutation('post', '/api/v1/registry/breaks/{id}/correct-lot', {
    meta: { notify: false },
    onSuccess: () => {
      notifySuccess(t('reconciliation.resolved'));
      void invalidate();
      onClose();
    },
  });
  return (
    <Modal opened onClose={onClose} title={t('reconciliation.actions.correctLot')}>
      <Stack>
        <ProblemAlert error={correct.error} />
        <TextInput
          type="date"
          label={t('reconciliation.expiryDate')}
          value={expiry}
          onChange={(e) => setExpiry(e.currentTarget.value)}
        />
        <Checkbox
          label={t('reconciliation.supportProduction')}
          checked={production}
          onChange={(e) => setProduction(e.currentTarget.checked)}
        />
        <Checkbox
          label={t('reconciliation.supportInvestment')}
          checked={investment}
          onChange={(e) => setInvestment(e.currentTarget.checked)}
        />
        <Textarea
          label={t('common.reason')}
          value={reason}
          onChange={(e) => setReason(e.currentTarget.value)}
          required
          autosize
        />
        <Button
          disabled={!reason.trim()}
          loading={correct.isPending}
          onClick={() =>
            correct.mutate({
              params: { path: { id: brk.id } },
              body: {
                expiryDate: expiry,
                supportProduction: production,
                supportInvestment: investment,
                reason: reason.trim(),
              },
            })
          }
        >
          {t('reconciliation.actions.correctLot')}
        </Button>
      </Stack>
    </Modal>
  );
}

/** One break: registry against our books, its resolutions by type, acceptance with four eyes, and its tasks. */
export function BreakDetailPage() {
  const { t } = useTranslation();
  const { breakId = '' } = useParams();
  const invalidate = useInvalidateReconciliation();
  const [dialog, setDialog] = useState<Dialog>(null);
  const path = { params: { path: { id: breakId } } };
  const brk = $api.useQuery('get', '/api/v1/inventory/breaks/{id}', path);
  const data = brk.data;
  const lot = $api.useQuery(
    'get',
    '/api/v1/inventory/lots/{id}',
    { params: { path: { id: data?.lotId ?? '' } } },
    { enabled: Boolean(data?.lotId) },
  );
  const account = $api.useQuery(
    'get',
    '/api/v1/reference/registry-accounts/{id}',
    { params: { path: { id: data?.registryAccountId ?? '' } } },
    { enabled: Boolean(data?.registryAccountId) },
  );
  const tasks = $api.useQuery('get', '/api/v1/workflow/tasks', {
    params: { query: { subjectRef: breakId, status: 'OPEN' } },
  });
  const done = (message: string) => () => {
    notifySuccess(message);
    setDialog(null);
    void invalidate();
  };
  const take = $api.useMutation('post', '/api/v1/registry/breaks/{id}/assign', {
    onSuccess: done(t('reconciliation.taken')),
  });
  const confirmInstruction = $api.useMutation('post', '/api/v1/registry/breaks/{id}/confirm-instruction', {
    meta: { notify: false },
    onSuccess: done(t('reconciliation.resolved')),
  });
  const withdrawLot = $api.useMutation('post', '/api/v1/registry/breaks/{id}/withdraw-lot', {
    meta: { notify: false },
    onSuccess: done(t('reconciliation.resolved')),
  });
  const correctHolding = $api.useMutation('post', '/api/v1/registry/breaks/{id}/correct-holding', {
    meta: { notify: false },
    onSuccess: done(t('reconciliation.resolved')),
  });
  const accept = $api.useMutation('post', '/api/v1/registry/breaks/{id}/accept', {
    meta: { notify: false },
    onSuccess: done(t('reconciliation.acceptanceRequested')),
  });
  const actions = data ? breakActions(data, lot.data?.status) : null;
  const holder = account.data?.holderType === 'LEGAL_ENTITY' ? account.data.holderId : (lot.data?.holderEntityId ?? '');
  if (!data || !actions) {
    return (
      <QueryState isPending={false} error={brk.error}>
        <Stack gap="lg">
          <CardSkeleton height={90} />
          <CardSkeleton height={200} />
        </Stack>
      </QueryState>
    );
  }
  const title = t(`reconciliation.types.${data.type}`);
  const accountLabel = account.data ? `${account.data.registryCode} ${account.data.accountNumber}` : '';
  const mismatched = mismatches(data.details);
  const otherFacts = facts(data.details);
  const age = ageInDays(data, dayjs().format('YYYY-MM-DD'));
  return (
    <Stack gap="lg">
      <PageHeader
        breadcrumbs={[{ label: t('reconciliation.title'), to: '/reconciliation' }, { label: title }]}
        title={title}
        badge={<BreakStatusBadge status={data.status} testId="break-status" />}
        description={[breakSubject(data), accountLabel].filter(Boolean).join(' · ')}
        actions={
          <>
            {(tasks.data?.items ?? []).map((task) => (
              <Button
                key={task.id}
                component={Link}
                to={`/inbox/${task.id}`}
                variant={actions.take ? 'light' : 'filled'}
                leftSection={<IconInbox size={16} />}
                data-testid={`task-${task.type}`}
              >
                {t(`reconciliation.tasks.${task.type}`, { defaultValue: task.type })}
              </Button>
            ))}
            {actions.take && (
              <Can op="break_assign">
                <Button
                  leftSection={<IconHandGrab size={16} />}
                  loading={take.isPending}
                  onClick={() => take.mutate({ ...path, body: {} })}
                  data-testid="break-take"
                >
                  {t('reconciliation.take')}
                </Button>
              </Can>
            )}
          </>
        }
      />
      <DetailLayout
        summary={[
          {
            label: t('reconciliation.lot'),
            value: data.lotId ? (
              <Anchor component={Link} to={`/inventory/lots/${data.lotId}`} size="sm">
                {lot.data ? lot.data.issuanceRef : t('registry.openLot')}
              </Anchor>
            ) : (
              t('reconciliation.noLot')
            ),
          },
          ...(lot.data
            ? [{ label: t('reconciliation.lotStatus'), value: <StatusBadge status={lot.data.status} /> }]
            : []),
          {
            label: t('reconciliation.age'),
            value: age === null ? '—' : t('reconciliation.ageValue', { count: age }),
          },
          { label: t('reconciliation.firstSeen'), value: formatDate(data.firstSeenOn) || '—' },
          { label: t('reconciliation.lastSeen'), value: formatDate(data.lastSeenOn) || '—' },
          { label: t('reconciliation.assignee'), value: data.assignee ?? '—' },
          ...(data.type === 'BALANCE_MISMATCH'
            ? [
                { label: t('reconciliation.registryQuantity'), value: data.registryQuantity ?? '—' },
                { label: t('reconciliation.internalQuantity'), value: data.internalQuantity ?? '—' },
              ]
            : []),
        ]}
        aside={
          <LifecycleTimeline
            title={t('reconciliation.lifecycle.title')}
            steps={breakSteps(data, (key) => t(`reconciliation.lifecycle.${key}`))}
            testId="break-lifecycle"
          />
        }
      >
        {data.resolution && (
          <Alert color="gray" title={t('reconciliation.resolutionTitle')} data-testid="break-resolution">
            {data.resolutionAction ? `${t(`reconciliation.actionNames.${data.resolutionAction}`)}: ` : ''}
            {data.resolution}
          </Alert>
        )}
        {(actions.accept || actions.awaitingAcceptance) && (
          <Paper withBorder radius="lg" p="lg">
            <Title order={3} size="h5">
              {t('reconciliation.resolve')}
            </Title>
            <Text size="sm" c="dimmed" mb="md">
              {t(`reconciliation.typeHints.${data.type}`)}
            </Text>
            {actions.awaitingAcceptance ? (
              <Alert color="yellow" data-testid="break-pending-acceptance">
                {t('reconciliation.awaitingAcceptance', { by: data.acceptanceRequestedBy ?? '' })}
              </Alert>
            ) : (
              <Can op="break_accept">
                <Group gap="xs">
                  {actions.confirmInstruction && (
                    <Button onClick={() => setDialog('confirmInstruction')}>
                      {t('reconciliation.actions.confirmInstruction')}
                    </Button>
                  )}
                  {actions.recordReceipt && (
                    <Button onClick={() => setDialog('recordReceipt')}>
                      {t('reconciliation.actions.recordReceipt')}
                    </Button>
                  )}
                  {actions.replaceLot && (
                    <Button onClick={() => setDialog('replaceLot')}>{t('reconciliation.actions.replaceLot')}</Button>
                  )}
                  {actions.correctHolding && (
                    <Button onClick={() => setDialog('correctHolding')}>
                      {t('reconciliation.actions.correctHolding')}
                    </Button>
                  )}
                  {actions.correctLot && (
                    <Button variant="default" onClick={() => setDialog('correctLot')}>
                      {t('reconciliation.actions.correctLot')}
                    </Button>
                  )}
                  <Button variant="default" onClick={() => setDialog('accept')} data-testid="break-accept">
                    {t('reconciliation.actions.accept')}
                  </Button>
                  {actions.withdrawLot && (
                    <Button
                      variant="light"
                      color="red"
                      onClick={() => setDialog('withdrawLot')}
                      data-testid="break-withdraw"
                    >
                      {t('reconciliation.actions.withdrawLot')}
                    </Button>
                  )}
                </Group>
              </Can>
            )}
          </Paper>
        )}
        <Paper withBorder radius="lg" p="lg">
          <Title order={3} size="h5">
            {t('reconciliation.detailsTitle')}
          </Title>
          <Text size="sm" c="dimmed" mb="sm">
            {t('reconciliation.detailsHint')}
          </Text>
          {mismatched.length > 0 && (
            <Table verticalSpacing="sm" mb="sm">
              <Table.Thead>
                <Table.Tr>
                  <Table.Th>{t('reconciliation.field')}</Table.Th>
                  <Table.Th>{t('reconciliation.registryValue')}</Table.Th>
                  <Table.Th>{t('reconciliation.ourValue')}</Table.Th>
                </Table.Tr>
              </Table.Thead>
              <Table.Tbody>
                {mismatched.map((row) => (
                  <Table.Tr key={row.field}>
                    <Table.Td c="dimmed">{row.field}</Table.Td>
                    <Table.Td fw={600}>{row.registry}</Table.Td>
                    <Table.Td>{row.internal}</Table.Td>
                  </Table.Tr>
                ))}
              </Table.Tbody>
            </Table>
          )}
          {otherFacts.length > 0 ? (
            <FactGrid facts={otherFacts.map(([key, value]) => ({ label: key, value }))} cols={3} />
          ) : (
            mismatched.length === 0 && (
              <Text size="sm" c="dimmed">
                {t('reconciliation.noDetails')}
              </Text>
            )
          )}
        </Paper>
      </DetailLayout>
      {dialog === 'confirmInstruction' && (
        <NoteModal
          title={t('reconciliation.actions.confirmInstruction')}
          hint={t('reconciliation.confirmInstructionHint')}
          action={t('reconciliation.actions.confirmInstruction')}
          withReference
          pending={confirmInstruction.isPending}
          error={confirmInstruction.error}
          onSubmit={(note, reference) =>
            confirmInstruction.mutate({ ...path, body: { note, externalRef: reference || undefined } })
          }
          onClose={() => setDialog(null)}
        />
      )}
      {dialog === 'withdrawLot' && (
        <NoteModal
          title={t('reconciliation.actions.withdrawLot')}
          hint={t('reconciliation.withdrawHint')}
          action={t('reconciliation.actions.withdrawLot')}
          pending={withdrawLot.isPending}
          error={withdrawLot.error}
          onSubmit={(note) => withdrawLot.mutate({ ...path, body: { note } })}
          onClose={() => setDialog(null)}
        />
      )}
      {dialog === 'correctHolding' && (
        <NoteModal
          title={t('reconciliation.actions.correctHolding')}
          hint={t('reconciliation.correctHoldingHint')}
          action={t('reconciliation.actions.correctHolding')}
          pending={correctHolding.isPending}
          error={correctHolding.error}
          onSubmit={(note) => correctHolding.mutate({ ...path, body: { note } })}
          onClose={() => setDialog(null)}
        />
      )}
      {dialog === 'accept' && (
        <NoteModal
          title={t('reconciliation.actions.accept')}
          hint={t('reconciliation.acceptHint')}
          action={t('risk.sendForApproval')}
          pending={accept.isPending}
          error={accept.error}
          onSubmit={(note) => accept.mutate({ ...path, body: { note } })}
          onClose={() => setDialog(null)}
        />
      )}
      {(dialog === 'recordReceipt' || dialog === 'replaceLot') && (
        <ReceiptModal
          brk={data}
          holderEntityId={holder}
          lot={dialog === 'replaceLot' ? lot.data : undefined}
          replace={dialog === 'replaceLot'}
          onClose={() => setDialog(null)}
        />
      )}
      {dialog === 'correctLot' && lot.data && (
        <CorrectLotModal brk={data} lot={lot.data} onClose={() => setDialog(null)} />
      )}
    </Stack>
  );
}
