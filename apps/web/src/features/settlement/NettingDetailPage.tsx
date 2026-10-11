import {
  Alert,
  Anchor,
  Button,
  Card,
  Group,
  Modal,
  Select,
  SimpleGrid,
  Stack,
  Table,
  Text,
  TextInput,
  Title,
} from '@mantine/core';
import { IconArrowLeft, IconCheck, IconFileTypePdf, IconScale, IconX } from '@tabler/icons-react';
import { useState, type ReactNode } from 'react';
import { useTranslation } from 'react-i18next';
import { Link, useParams } from 'react-router';
import { $api } from '@/shared/api/client';
import { download } from '@/shared/api/download';
import { Can } from '@/shared/auth/Can';
import { useSession } from '@/shared/auth/SessionContext';
import { notifySuccess } from '@/shared/components/notify';
import { ProblemAlert } from '@/shared/components/ProblemAlert';
import { QueryState } from '@/shared/components/QueryState';
import { formatDate, formatMoney } from '@/shared/format/format';
import { useCounterparties } from '@/features/trades/hooks';
import { useInvalidateSettlement } from './hooks';
import {
  NETTING_STATUS_COLORS,
  nettingActions,
  nettingDirection,
  settlementPayments,
  type Netting,
} from './model/payments';
import { ReasonModal } from './SettlementModals';
import { StatusBadge } from './SettlementPage';

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

/** The counterparty's agreement, recorded by someone other than the preparer, with their reference. */
function AgreeModal({ statement, onClose }: { statement: Netting; onClose: () => void }) {
  const { t } = useTranslation();
  const invalidate = useInvalidateSettlement();
  const [reference, setReference] = useState('');
  const agree = $api.useMutation('post', '/api/v1/settlement/netting/{id}/agree', {
    meta: { notify: false },
    onSuccess: () => {
      notifySuccess(t('settlement.agreementRecorded'));
      void invalidate();
      onClose();
    },
  });
  return (
    <Modal opened onClose={onClose} title={t('settlement.recordAgreement')}>
      <Stack>
        <ProblemAlert error={agree.error} />
        <TextInput
          label={t('settlement.counterpartyRef')}
          description={t('settlement.counterpartyRefHint')}
          value={reference}
          onChange={(e) => setReference(e.currentTarget.value)}
          data-testid="agree-reference"
        />
        <Button
          loading={agree.isPending}
          data-testid="agree-submit"
          onClick={() =>
            agree.mutate({
              params: { path: { id: statement.id } },
              body: { counterpartyRef: reference.trim() || undefined },
            })
          }
        >
          {t('settlement.recordAgreement')}
        </Button>
      </Stack>
    </Modal>
  );
}

/** Settles an agreed statement: the invoices offset, and the net amount is paid by a recorded payment. */
function SettleModal({ statement, onClose }: { statement: Netting; onClose: () => void }) {
  const { t } = useTranslation();
  const invalidate = useInvalidateSettlement();
  const [paymentId, setPaymentId] = useState<string | null>(null);
  const actions = nettingActions(statement, '');
  const payments = $api.useQuery(
    'get',
    '/api/v1/settlement/payments',
    { params: { query: { legalEntityId: statement.legalEntityId, unappliedOnly: true } } },
    { enabled: actions.needsPayment },
  );
  const settle = $api.useMutation('post', '/api/v1/settlement/netting/{id}/settle', {
    meta: { notify: false },
    onSuccess: () => {
      notifySuccess(t('settlement.nettingSettled'));
      void invalidate();
      onClose();
    },
  });
  const candidates = settlementPayments(payments.data ?? [], statement);
  return (
    <Modal opened onClose={onClose} title={t('settlement.settleNetting')}>
      <Stack>
        <ProblemAlert error={settle.error} />
        {actions.needsPayment ? (
          <Select
            label={t('settlement.netPayment')}
            description={t('settlement.netPaymentHint', {
              amount: formatMoney(statement.netAmount, statement.currency),
            })}
            data={candidates.map((payment) => ({
              value: payment.id,
              label: `${formatDate(payment.valueDate)} · ${payment.bankReference} · ${formatMoney(
                payment.unappliedAmount,
                payment.currency,
              )}`,
            }))}
            value={paymentId}
            onChange={setPaymentId}
            nothingFoundMessage={t('settlement.noNetPayment')}
            data-testid="settle-payment"
          />
        ) : (
          <Text size="sm">{t('settlement.nothingToPay')}</Text>
        )}
        <Button
          disabled={actions.needsPayment && !paymentId}
          loading={settle.isPending}
          data-testid="settle-submit"
          onClick={() =>
            settle.mutate({ params: { path: { id: statement.id } }, body: { paymentId: paymentId ?? undefined } })
          }
        >
          {t('settlement.settle')}
        </Button>
      </Stack>
    </Modal>
  );
}

/** One netting statement: the invoices it offsets, who prepared and agreed it, and settling it. */
export function NettingDetailPage() {
  const { t } = useTranslation();
  const { me } = useSession();
  const { nettingId = '' } = useParams();
  const counterparties = useCounterparties();
  const invalidate = useInvalidateSettlement();
  const [dialog, setDialog] = useState<'agree' | 'settle' | 'cancel' | null>(null);
  const statement = $api.useQuery('get', '/api/v1/settlement/netting/{id}', {
    params: { path: { id: nettingId } },
  });
  const data = statement.data;
  const invoices = $api.useQuery(
    'get',
    '/api/v1/settlement/invoices',
    { params: { query: { legalEntityId: data?.legalEntityId, counterpartyId: data?.counterpartyId } } },
    { enabled: Boolean(data) },
  );
  const cancel = $api.useMutation('post', '/api/v1/settlement/netting/{id}/cancel', {
    meta: { notify: false },
    onSuccess: () => {
      notifySuccess(t('settlement.nettingCancelled'));
      setDialog(null);
      void invalidate();
    },
  });
  const actions = data ? nettingActions(data, me.userId) : null;
  const byId = new Map((invoices.data ?? []).map((invoice) => [invoice.id, invoice]));
  return (
    <Stack>
      <Anchor component={Link} to="/settlement?tab=netting" size="sm">
        <Group gap={4}>
          <IconArrowLeft size={14} />
          {t('settlement.backToNetting')}
        </Group>
      </Anchor>
      <QueryState isPending={statement.isPending} error={statement.error}>
        {data && actions && (
          <>
            <Card withBorder>
              <Group justify="space-between" align="flex-start">
                <Stack gap={4}>
                  <Group gap="xs">
                    <Title order={3}>
                      {t('settlement.nettingWith', {
                        counterparty: counterparties.byId.get(data.counterpartyId)?.name ?? data.counterpartyId,
                      })}
                    </Title>
                    <StatusBadge
                      label={data.status}
                      color={NETTING_STATUS_COLORS[data.status]}
                      testId="netting-status"
                    />
                  </Group>
                  <Text size="sm" data-testid="netting-net">
                    {t(`settlement.nettingDirections.${nettingDirection(data)}`, {
                      amount: formatMoney(data.netAmount, data.currency).replace('-', ''),
                    })}
                  </Text>
                </Stack>
                <Group gap="xs">
                  {actions.pdf && data.documentId && (
                    <Button
                      variant="light"
                      leftSection={<IconFileTypePdf size={16} />}
                      onClick={() => void download(`/api/v1/documents/${data.documentId}/content`, 'netting.pdf')}
                    >
                      {t('settlement.pdf')}
                    </Button>
                  )}
                  {actions.cancel && (
                    <Can op="payment_cancelNetting">
                      <Button
                        variant="subtle"
                        color="red"
                        leftSection={<IconX size={16} />}
                        onClick={() => setDialog('cancel')}
                      >
                        {t('settlement.cancelNetting')}
                      </Button>
                    </Can>
                  )}
                  {actions.agree && (
                    <Can op="payment_agree">
                      <Button
                        leftSection={<IconCheck size={16} />}
                        onClick={() => setDialog('agree')}
                        data-testid="netting-agree"
                      >
                        {t('settlement.recordAgreement')}
                      </Button>
                    </Can>
                  )}
                  {actions.settle && (
                    <Can op="payment_settle">
                      <Button
                        leftSection={<IconScale size={16} />}
                        onClick={() => setDialog('settle')}
                        data-testid="netting-settle"
                      >
                        {t('settlement.settle')}
                      </Button>
                    </Can>
                  )}
                </Group>
              </Group>
              {actions.ownStatement && (
                <Alert color="yellow" mt="md" data-testid="netting-own">
                  {t('settlement.ownNetting')}
                </Alert>
              )}
              <SimpleGrid cols={{ base: 2, md: 4 }} mt="md">
                <Field label={t('settlement.valueDate')}>{formatDate(data.valueDate)}</Field>
                <Field label={t('settlement.receivable')}>{formatMoney(data.receivableTotal, data.currency)}</Field>
                <Field label={t('settlement.payable')}>{formatMoney(data.payableTotal, data.currency)}</Field>
                <Field label={t('settlement.net')}>{formatMoney(data.netAmount, data.currency)}</Field>
                <Field label={t('settlement.preparedBy')}>{data.createdBy}</Field>
                <Field label={t('settlement.agreementRecordedBy')}>{data.agreedBy ?? '—'}</Field>
                <Field label={t('settlement.counterpartyRef')}>{data.counterpartyRef ?? '—'}</Field>
              </SimpleGrid>
            </Card>
            <Card withBorder>
              <Title order={5} mb="sm">
                {t('settlement.nettedInvoices')}
              </Title>
              <Table data-testid="netting-invoices">
                <Table.Thead>
                  <Table.Tr>
                    <Table.Th>{t('settlement.number')}</Table.Th>
                    <Table.Th>{t('settlement.kind')}</Table.Th>
                    <Table.Th>{t('settlement.dueDate')}</Table.Th>
                    <Table.Th ta="right">{t('settlement.nettedAmount')}</Table.Th>
                  </Table.Tr>
                </Table.Thead>
                <Table.Tbody>
                  {Object.entries(data.invoices).map(([invoiceId, amount]) => {
                    const invoice = byId.get(invoiceId);
                    return (
                      <Table.Tr key={invoiceId}>
                        <Table.Td>
                          <Anchor component={Link} to={`/settlement/invoices/${invoiceId}`} size="sm">
                            {invoice?.number ?? invoiceId.slice(0, 8)}
                          </Anchor>
                        </Table.Td>
                        <Table.Td>{invoice ? t(`settlement.kinds.${invoice.kind}`) : ''}</Table.Td>
                        <Table.Td>{formatDate(invoice?.dueDate)}</Table.Td>
                        <Table.Td ta="right">{formatMoney(amount, data.currency)}</Table.Td>
                      </Table.Tr>
                    );
                  })}
                </Table.Tbody>
              </Table>
            </Card>
            {dialog === 'agree' && <AgreeModal statement={data} onClose={() => setDialog(null)} />}
            {dialog === 'settle' && <SettleModal statement={data} onClose={() => setDialog(null)} />}
            {dialog === 'cancel' && (
              <ReasonModal
                title={t('settlement.cancelNetting')}
                hint={t('settlement.cancelNettingHint')}
                action={t('settlement.cancelNetting')}
                pending={cancel.isPending}
                error={cancel.error}
                onSubmit={(reason) => cancel.mutate({ params: { path: { id: data.id } }, body: { reason } })}
                onClose={() => setDialog(null)}
              />
            )}
          </>
        )}
      </QueryState>
    </Stack>
  );
}
