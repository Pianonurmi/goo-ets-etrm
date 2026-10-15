import { Alert, Anchor, Button, Modal, Paper, Select, Stack, Table, Text, TextInput, Title } from '@mantine/core';
import { IconCheck, IconFileTypePdf, IconScale, IconX } from '@tabler/icons-react';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link, useParams } from 'react-router';
import { $api } from '@/shared/api/client';
import { download } from '@/shared/api/download';
import { Can } from '@/shared/auth/Can';
import { useSession } from '@/shared/auth/SessionContext';
import { DetailLayout } from '@/shared/components/DetailLayout';
import { Money } from '@/shared/components/Figures';
import { LifecycleTimeline } from '@/shared/components/LifecycleTimeline';
import { notifySuccess } from '@/shared/components/notify';
import { PageHeader } from '@/shared/components/PageHeader';
import { ProblemAlert } from '@/shared/components/ProblemAlert';
import { QueryState } from '@/shared/components/QueryState';
import { ReasonModal } from '@/shared/components/ReasonModal';
import { CardSkeleton } from '@/shared/components/Skeletons';
import { StatusBadge } from '@/shared/components/StatusBadge';
import { formatDate, formatMoney } from '@/shared/format/format';
import { useCounterparties } from '@/features/trades/hooks';
import { useInvalidateSettlement } from './hooks';
import { nettingSteps } from './model/lifecycle';
import { nettingActions, nettingDirection, settlementPayments, type Netting } from './model/payments';

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

/** One netting statement: the net amount and who pays, the invoices it offsets, its lifecycle, and settling it. */
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
  const breadcrumbs = [
    { label: t('settlement.title'), to: '/settlement' },
    { label: t('settlement.tabs.netting'), to: '/settlement?tab=netting' },
  ];
  if (!data) {
    return (
      <Stack gap={0}>
        <PageHeader breadcrumbs={breadcrumbs} title={t('settlement.tabs.netting')} />
        <QueryState isPending={false} error={statement.error}>
          <CardSkeleton height={260} />
        </QueryState>
      </Stack>
    );
  }
  const actions = nettingActions(data, me.userId);
  const byId = new Map((invoices.data ?? []).map((invoice) => [invoice.id, invoice]));
  return (
    <Stack gap={0}>
      <PageHeader
        breadcrumbs={breadcrumbs}
        title={t('settlement.nettingWith', {
          counterparty: counterparties.byId.get(data.counterpartyId)?.name ?? data.counterpartyId,
        })}
        badge={<StatusBadge status={data.status} data-testid="netting-status" />}
        description={
          <span data-testid="netting-net">
            {t(`settlement.nettingDirections.${nettingDirection(data)}`, {
              amount: formatMoney(data.netAmount, data.currency).replace('-', ''),
            })}
          </span>
        }
        actions={
          <>
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
            {actions.pdf && data.documentId && (
              <Button
                variant="default"
                leftSection={<IconFileTypePdf size={16} />}
                onClick={() => void download(`/api/v1/documents/${data.documentId}/content`, 'netting.pdf')}
              >
                {t('settlement.pdf')}
              </Button>
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
          </>
        }
      />
      <DetailLayout
        summary={[
          {
            label: t('settlement.net'),
            value: <Money value={data.netAmount} currency={data.currency} signed fw={600} size="lg" />,
          },
          { label: t('settlement.receivable'), value: <Money value={data.receivableTotal} currency={data.currency} /> },
          { label: t('settlement.payable'), value: <Money value={data.payableTotal} currency={data.currency} /> },
          { label: t('settlement.valueDate'), value: formatDate(data.valueDate) },
        ]}
        aside={
          <LifecycleTimeline
            title={t('settlement.lifecycle.title')}
            steps={nettingSteps(data, t)}
            testId="netting-lifecycle"
          />
        }
      >
        {actions.ownStatement && (
          <Alert color="yellow" data-testid="netting-own">
            {t('settlement.ownNetting')}
          </Alert>
        )}
        <Paper withBorder radius="lg" p="lg">
          <Title order={3} size="h5" mb="sm">
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
            <Table.Tbody className="etrm-figure">
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
                    <Table.Td ta="right">
                      <Money value={amount} currency={data.currency} />
                    </Table.Td>
                  </Table.Tr>
                );
              })}
            </Table.Tbody>
          </Table>
        </Paper>
      </DetailLayout>
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
    </Stack>
  );
}
