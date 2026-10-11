import {
  Alert,
  Anchor,
  Button,
  Card,
  Checkbox,
  Group,
  Modal,
  Select,
  SimpleGrid,
  Stack,
  Table,
  Text,
  TextInput,
  Textarea,
  Title,
} from '@mantine/core';
import { IconArrowLeft, IconHandGrab, IconInbox } from '@tabler/icons-react';
import { useState, type ReactNode } from 'react';
import { useTranslation } from 'react-i18next';
import { Link, useParams } from 'react-router';
import { $api } from '@/shared/api/client';
import { decimal, type Body, type Schema } from '@/shared/api/types';
import { Can } from '@/shared/auth/Can';
import { notifySuccess } from '@/shared/components/notify';
import { ProblemAlert } from '@/shared/components/ProblemAlert';
import { QueryState } from '@/shared/components/QueryState';
import { formatDate, formatDateTime } from '@/shared/format/format';
import { breakActions, breakSubject, facts, mismatches, type Break } from './model/breaks';
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

function Field({ label, children }: { label: string; children: ReactNode }) {
  return (
    <Stack gap={0}>
      <Text size="xs" c="dimmed">
        {label}
      </Text>
      <Text size="sm" component="div">
        {children}
      </Text>
    </Stack>
  );
}

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
  return (
    <Stack>
      <Anchor component={Link} to="/reconciliation" size="sm">
        <Group gap={4}>
          <IconArrowLeft size={14} />
          {t('reconciliation.back')}
        </Group>
      </Anchor>
      <QueryState isPending={brk.isPending} error={brk.error}>
        {data && actions && (
          <>
            <Card withBorder>
              <Group justify="space-between" align="flex-start">
                <Stack gap={4}>
                  <Group gap="xs">
                    <Title order={3}>{t(`reconciliation.types.${data.type}`)}</Title>
                    <BreakStatusBadge status={data.status} testId="break-status" />
                  </Group>
                  <Text size="sm" c="dimmed">
                    {breakSubject(data)} ·{' '}
                    {account.data ? `${account.data.registryCode} ${account.data.accountNumber}` : ''}
                  </Text>
                </Stack>
                <Group gap="xs">
                  {(tasks.data?.items ?? []).map((task) => (
                    <Button
                      key={task.id}
                      component={Link}
                      to={`/inbox/${task.id}`}
                      variant="light"
                      leftSection={<IconInbox size={16} />}
                      data-testid={`task-${task.type}`}
                    >
                      {t(`reconciliation.tasks.${task.type}`, { defaultValue: task.type })}
                    </Button>
                  ))}
                  {actions.take && (
                    <Can op="break_assign">
                      <Button
                        variant="light"
                        leftSection={<IconHandGrab size={16} />}
                        loading={take.isPending}
                        onClick={() => take.mutate({ ...path, body: {} })}
                        data-testid="break-take"
                      >
                        {t('reconciliation.take')}
                      </Button>
                    </Can>
                  )}
                </Group>
              </Group>
              <SimpleGrid cols={{ base: 2, md: 4 }} mt="md">
                <Field label={t('reconciliation.firstSeen')}>{formatDate(data.firstSeenOn)}</Field>
                <Field label={t('reconciliation.lastSeen')}>{formatDate(data.lastSeenOn)}</Field>
                <Field label={t('reconciliation.assignee')}>{data.assignee ?? '—'}</Field>
                <Field label={t('reconciliation.lot')}>
                  {data.lotId ? (
                    <Anchor component={Link} to={`/inventory/lots/${data.lotId}`} size="sm">
                      {lot.data ? `${lot.data.issuanceRef} · ${lot.data.status}` : t('registry.openLot')}
                    </Anchor>
                  ) : (
                    t('reconciliation.noLot')
                  )}
                </Field>
                {data.type === 'BALANCE_MISMATCH' && (
                  <>
                    <Field label={t('reconciliation.registryQuantity')}>{data.registryQuantity}</Field>
                    <Field label={t('reconciliation.internalQuantity')}>{data.internalQuantity}</Field>
                  </>
                )}
                {data.acceptanceRequestedBy && (
                  <Field label={t('reconciliation.acceptanceRequestedBy')}>{data.acceptanceRequestedBy}</Field>
                )}
                {data.resolvedBy && (
                  <Field label={t('reconciliation.resolvedBy')}>
                    {data.resolvedBy} · {formatDateTime(data.resolvedAt)}
                  </Field>
                )}
              </SimpleGrid>
              {data.resolution && (
                <Alert mt="md" color="gray" data-testid="break-resolution">
                  {data.resolutionAction ? `${t(`reconciliation.actionNames.${data.resolutionAction}`)}: ` : ''}
                  {data.resolution}
                </Alert>
              )}
            </Card>
            {mismatches(data.details).length > 0 && (
              <Card withBorder>
                <Title order={5} mb="sm">
                  {t('reconciliation.mismatches')}
                </Title>
                <Table>
                  <Table.Thead>
                    <Table.Tr>
                      <Table.Th>{t('reconciliation.field')}</Table.Th>
                      <Table.Th>{t('reconciliation.registryValue')}</Table.Th>
                      <Table.Th>{t('reconciliation.ourValue')}</Table.Th>
                    </Table.Tr>
                  </Table.Thead>
                  <Table.Tbody>
                    {mismatches(data.details).map((row) => (
                      <Table.Tr key={row.field}>
                        <Table.Td>{row.field}</Table.Td>
                        <Table.Td>{row.registry}</Table.Td>
                        <Table.Td>{row.internal}</Table.Td>
                      </Table.Tr>
                    ))}
                  </Table.Tbody>
                </Table>
              </Card>
            )}
            {facts(data.details).length > 0 && (
              <Text size="xs" c="dimmed">
                {facts(data.details)
                  .map(([key, value]) => `${key}: ${value}`)
                  .join(' · ')}
              </Text>
            )}
            {(actions.accept || actions.awaitingAcceptance) && (
              <Card withBorder>
                <Title order={5} mb="xs">
                  {t('reconciliation.resolve')}
                </Title>
                <Text size="sm" c="dimmed" mb="sm">
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
                      {actions.withdrawLot && (
                        <Button color="orange" onClick={() => setDialog('withdrawLot')} data-testid="break-withdraw">
                          {t('reconciliation.actions.withdrawLot')}
                        </Button>
                      )}
                      {actions.recordReceipt && (
                        <Button onClick={() => setDialog('recordReceipt')}>
                          {t('reconciliation.actions.recordReceipt')}
                        </Button>
                      )}
                      {actions.replaceLot && (
                        <Button onClick={() => setDialog('replaceLot')}>
                          {t('reconciliation.actions.replaceLot')}
                        </Button>
                      )}
                      {actions.correctLot && (
                        <Button variant="light" onClick={() => setDialog('correctLot')}>
                          {t('reconciliation.actions.correctLot')}
                        </Button>
                      )}
                      {actions.correctHolding && (
                        <Button onClick={() => setDialog('correctHolding')}>
                          {t('reconciliation.actions.correctHolding')}
                        </Button>
                      )}
                      <Button
                        variant="light"
                        color="grape"
                        onClick={() => setDialog('accept')}
                        data-testid="break-accept"
                      >
                        {t('reconciliation.actions.accept')}
                      </Button>
                    </Group>
                  </Can>
                )}
              </Card>
            )}
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
          </>
        )}
      </QueryState>
    </Stack>
  );
}
