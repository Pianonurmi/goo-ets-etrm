import { Anchor, Button, Group, List, Paper, SimpleGrid, Stack, Table, Text, Title } from '@mantine/core';
import { IconFileTypePdf, IconInbox, IconReceiptRefund, IconSend, IconTrash } from '@tabler/icons-react';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link, useNavigate, useParams } from 'react-router';
import { $api } from '@/shared/api/client';
import { download } from '@/shared/api/download';
import type { Schema } from '@/shared/api/types';
import { Can } from '@/shared/auth/Can';
import { ScrollTable } from '@/shared/components/ScrollTable';
import { DetailLayout } from '@/shared/components/DetailLayout';
import { Money } from '@/shared/components/Figures';
import { LifecycleTimeline } from '@/shared/components/LifecycleTimeline';
import { notifySuccess } from '@/shared/components/notify';
import { PageHeader } from '@/shared/components/PageHeader';
import { QueryState } from '@/shared/components/QueryState';
import { ReasonModal } from '@/shared/components/ReasonModal';
import { CardSkeleton, TableSkeleton } from '@/shared/components/Skeletons';
import { StatusBadge } from '@/shared/components/StatusBadge';
import { formatDate, formatDateTime, formatDecimal, formatMoney, formatQuantity } from '@/shared/format/format';
import { useCounterparties } from '@/features/trades/hooks';
import { useInvalidateSettlement } from './hooks';
import { invoiceSteps } from './model/lifecycle';
import { invoiceActions, paymentStatus, type Invoice } from './model/settlement';

type Party = Schema<'PartySnapshot'>;

const NOWRAP = { whiteSpace: 'nowrap' } as const;

function PartyCard({ title, party, fallback }: { title: string; party: Party | null | undefined; fallback: string }) {
  const { t } = useTranslation();
  return (
    <Paper withBorder radius="lg" p="lg">
      <Text size="xs" c="dimmed" fw={500}>
        {title}
      </Text>
      <Text fw={600} mt={4}>
        {party?.name ?? fallback}
      </Text>
      {party ? (
        <Stack gap={0} mt={2}>
          <Text size="sm" c="dimmed">
            {party.address ?? party.country}
          </Text>
          <Text size="sm" c="dimmed">
            {t('settlement.vatNumber')}: {party.vatNumber ?? '—'}
            {party.lei && ` · ${t('settlement.lei')}: ${party.lei}`}
          </Text>
        </Stack>
      ) : (
        <Text size="xs" c="dimmed">
          {t('settlement.frozenAtIssue')}
        </Text>
      )}
    </Paper>
  );
}

function Allocations({ invoice }: { invoice: Invoice }) {
  const { t } = useTranslation();
  const allocations = $api.useQuery('get', '/api/v1/settlement/invoices/{id}/allocations', {
    params: { path: { id: invoice.id } },
  });
  return (
    <Paper withBorder radius="lg" p="lg">
      <Group justify="space-between" mb="sm">
        <Title order={3} size="h5">
          {t('settlement.allocations')}
        </Title>
        <Text size="sm" c="dimmed" className="etrm-figure" data-testid="invoice-paid">
          {t('settlement.paidOf', {
            paid: formatMoney(invoice.paidAmount, invoice.currency),
            gross: formatMoney(invoice.grossTotal, invoice.currency),
          })}
        </Text>
      </Group>
      <QueryState isPending={false} error={allocations.error}>
        {allocations.isPending ? (
          <TableSkeleton rows={2} />
        ) : (allocations.data ?? []).length === 0 ? (
          <Text size="sm" c="dimmed">
            {t('settlement.noAllocations')}
          </Text>
        ) : (
          <Table>
            <Table.Thead>
              <Table.Tr>
                <Table.Th>{t('settlement.allocatedAt')}</Table.Th>
                <Table.Th>{t('settlement.kind')}</Table.Th>
                <Table.Th>{t('settlement.allocatedBy')}</Table.Th>
                <Table.Th ta="right">{t('settlement.amount')}</Table.Th>
              </Table.Tr>
            </Table.Thead>
            <Table.Tbody className="etrm-figure">
              {(allocations.data ?? []).map((allocation) => (
                <Table.Tr key={allocation.id}>
                  <Table.Td>{formatDateTime(allocation.allocatedAt)}</Table.Td>
                  <Table.Td>{t(`settlement.allocationKinds.${allocation.kind}`)}</Table.Td>
                  <Table.Td>{allocation.allocatedBy}</Table.Td>
                  <Table.Td ta="right">
                    <Money value={allocation.amount} currency={invoice.currency} />
                  </Table.Td>
                </Table.Tr>
              ))}
            </Table.Tbody>
          </Table>
        )}
      </QueryState>
    </Paper>
  );
}

function Lines({ invoice }: { invoice: Invoice }) {
  const { t } = useTranslation();
  const currency = invoice.currency;
  return (
    <Paper withBorder radius="lg" p="lg">
      <Title order={3} size="h5" mb="sm">
        {t('settlement.lines')}
      </Title>
      <ScrollTable label={t('settlement.lines')} minWidth={620}>
        <Table verticalSpacing="sm" data-testid="invoice-lines">
          <Table.Thead>
            <Table.Tr>
              <Table.Th>{t('settlement.lineDescription')}</Table.Th>
              <Table.Th ta="right">{t('settlement.quantity')}</Table.Th>
              <Table.Th ta="right">{t('settlement.price')}</Table.Th>
              <Table.Th ta="right">{t('settlement.netAmount')}</Table.Th>
              <Table.Th ta="right">{t('settlement.vatAmount')}</Table.Th>
            </Table.Tr>
          </Table.Thead>
          <Table.Tbody className="etrm-figure">
            {invoice.lines.map((line) => (
              <Table.Tr key={line.lineNo}>
                <Table.Td>
                  {line.description}
                  <Text size="xs" c="dimmed">
                    {[
                      t(`settlement.treatments.${line.vatTreatment}`),
                      `${t('settlement.vatRate')} ${formatDecimal(line.vatRate, 0, 2)} %`,
                      line.vatRuleCode,
                    ]
                      .filter(Boolean)
                      .join(' · ')}
                  </Text>
                </Table.Td>
                <Table.Td ta="right" style={NOWRAP}>
                  {formatQuantity(line.quantity, line.unit)}
                </Table.Td>
                <Table.Td ta="right">{formatDecimal(line.unitPrice, 2, 6)}</Table.Td>
                <Table.Td ta="right" style={NOWRAP}>
                  <Money value={line.netAmount} currency={currency} />
                </Table.Td>
                <Table.Td ta="right" style={NOWRAP}>
                  <Money value={line.vatAmount} currency={currency} />
                  {Number(line.selfAssessedVat) !== 0 && (
                    <Text size="xs" c="dimmed">
                      {t('settlement.selfAssessed', { amount: formatMoney(line.selfAssessedVat, currency) })}
                    </Text>
                  )}
                </Table.Td>
              </Table.Tr>
            ))}
          </Table.Tbody>
          <Table.Tfoot className="etrm-figure">
            <Table.Tr>
              <Table.Td colSpan={4} ta="right" c="dimmed">
                {t('settlement.netAmount')}
              </Table.Td>
              <Table.Td ta="right" style={NOWRAP}>
                <Money value={invoice.netTotal} currency={currency} />
              </Table.Td>
            </Table.Tr>
            <Table.Tr>
              <Table.Td colSpan={4} ta="right" c="dimmed">
                {t('settlement.vatAmount')}
              </Table.Td>
              <Table.Td ta="right" style={NOWRAP}>
                <Money value={invoice.vatTotal} currency={currency} />
              </Table.Td>
            </Table.Tr>
            <Table.Tr>
              <Table.Th colSpan={4} ta="right">
                {t('settlement.grossAmount')}
              </Table.Th>
              <Table.Th ta="right" style={NOWRAP} data-testid="invoice-gross">
                <Money value={invoice.grossTotal} currency={currency} />
              </Table.Th>
            </Table.Tr>
          </Table.Tfoot>
        </Table>
      </ScrollTable>
      {invoice.notes.length > 0 && (
        <List size="sm" mt="md" c="dimmed">
          {invoice.notes.map((note) => (
            <List.Item key={note}>{note}</List.Item>
          ))}
        </List>
      )}
      {invoice.bankAccount && (
        <Text size="sm" mt="md" c="dimmed">
          {t('settlement.payInto', {
            name: invoice.bankAccount.accountName,
            iban: invoice.bankAccount.iban,
            bic: invoice.bankAccount.bic,
          })}
        </Text>
      )}
    </Paper>
  );
}

/** One invoice: what is owed and when, parties, lines with their VAT treatment, payments and its lifecycle. */
export function InvoiceDetailPage() {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const { invoiceId = '' } = useParams();
  const counterparties = useCounterparties();
  const invalidate = useInvalidateSettlement();
  const [reason, setReason] = useState<'discard' | 'credit' | null>(null);
  const invoice = $api.useQuery('get', '/api/v1/settlement/invoices/{id}', { params: { path: { id: invoiceId } } });
  const pending = invoice.data?.status === 'PENDING_APPROVAL';
  const task = $api.useQuery(
    'get',
    '/api/v1/workflow/tasks',
    { params: { query: { subjectRef: invoiceId, type: ['INVOICE_APPROVAL'], status: 'OPEN' } } },
    { enabled: pending },
  );
  const entity = $api.useQuery(
    'get',
    '/api/v1/reference/legal-entities/{id}',
    { params: { path: { id: invoice.data?.legalEntityId ?? '' } } },
    { enabled: Boolean(invoice.data) },
  );
  const submit = $api.useMutation('post', '/api/v1/settlement/invoices/{id}/submit', {
    onSuccess: () => {
      notifySuccess(t('settlement.submitted'));
      void invalidate();
    },
  });
  const discard = $api.useMutation('post', '/api/v1/settlement/invoices/{id}/discard', {
    meta: { notify: false },
    onSuccess: () => {
      notifySuccess(t('settlement.discarded'));
      setReason(null);
      void invalidate();
    },
  });
  const credit = $api.useMutation('post', '/api/v1/settlement/invoices/{id}/credit-note', {
    meta: { notify: false },
    onSuccess: (note) => {
      notifySuccess(t('settlement.creditNoteDrafted'));
      setReason(null);
      void invalidate();
      void navigate(`/settlement/invoices/${note.id}`);
    },
  });
  const data = invoice.data;
  const breadcrumbs = [
    { label: t('settlement.title'), to: '/settlement' },
    { label: t('settlement.tabs.invoices'), to: '/settlement?tab=invoices' },
  ];
  if (!data) {
    return (
      <Stack gap={0}>
        <PageHeader breadcrumbs={breadcrumbs} title={t('settlement.invoice')} />
        <QueryState isPending={false} error={invoice.error}>
          <CardSkeleton height={320} />
        </QueryState>
      </Stack>
    );
  }
  const actions = invoiceActions(data);
  const counterpartyName = counterparties.byId.get(data.counterpartyId)?.name ?? data.counterpartyId;
  const entityName = entity.data?.name ?? '';
  const receivable = data.direction === 'RECEIVABLE';
  const path = { params: { path: { id: invoiceId } } };
  const payment = paymentStatus(data);
  const approvalTask = actions.awaitingApproval ? task.data?.items[0] : undefined;
  return (
    <Stack gap={0}>
      <PageHeader
        breadcrumbs={breadcrumbs}
        title={<span data-testid="invoice-number">{data.number ?? t('settlement.draft')}</span>}
        badge={
          <Group gap={6} wrap="nowrap">
            <StatusBadge status={data.status} data-testid="invoice-status" />
            {data.status === 'ISSUED' && <StatusBadge status={payment} data-testid="invoice-payment-status" />}
            {data.credited && <StatusBadge status="CREDITED" label={t('settlement.credited')} />}
          </Group>
        }
        description={`${t(`settlement.kinds.${data.kind}`)} · ${counterpartyName}`}
        actions={
          <>
            {actions.discard && (
              <Can op="settlement_discard">
                <Button
                  variant="subtle"
                  color="red"
                  leftSection={<IconTrash size={16} />}
                  onClick={() => setReason('discard')}
                  data-testid="invoice-discard"
                >
                  {t('settlement.discard')}
                </Button>
              </Can>
            )}
            {actions.creditNote && (
              <Can op="payment_creditNote">
                <Button
                  variant="default"
                  leftSection={<IconReceiptRefund size={16} />}
                  onClick={() => setReason('credit')}
                  data-testid="invoice-credit"
                >
                  {t('settlement.creditNote')}
                </Button>
              </Can>
            )}
            {actions.pdf && data.documentId && (
              <Button
                variant="default"
                leftSection={<IconFileTypePdf size={16} />}
                onClick={() =>
                  void download(`/api/v1/documents/${data.documentId}/content`, `${data.number ?? 'invoice'}.pdf`)
                }
                data-testid="invoice-pdf"
              >
                {t('settlement.pdf')}
              </Button>
            )}
            {approvalTask && (
              <Button
                component={Link}
                to={`/inbox/${approvalTask.id}`}
                leftSection={<IconInbox size={16} />}
                data-testid="invoice-task"
              >
                {t('settlement.approvalTask')}
              </Button>
            )}
            {actions.submit && (
              <Can op="settlement_submit">
                <Button
                  leftSection={<IconSend size={16} />}
                  loading={submit.isPending}
                  onClick={() => submit.mutate(path)}
                  data-testid="invoice-submit"
                >
                  {t('settlement.submit')}
                </Button>
              </Can>
            )}
          </>
        }
      />
      <DetailLayout
        summary={[
          {
            label: t('settlement.grossAmount'),
            value: <Money value={data.grossTotal} currency={data.currency} fw={600} size="lg" />,
          },
          {
            label: t('settlement.outstanding'),
            value: data.status === 'ISSUED' ? <Money value={data.outstanding} currency={data.currency} /> : '—',
          },
          { label: t('settlement.dueDate'), value: formatDate(data.dueDate) || '—' },
          { label: t('settlement.invoiceDate'), value: formatDate(data.invoiceDate) },
          { label: t('settlement.netAmount'), value: <Money value={data.netTotal} currency={data.currency} /> },
          { label: t('settlement.vatAmount'), value: <Money value={data.vatTotal} currency={data.currency} /> },
          { label: t('settlement.taxPointDate'), value: formatDate(data.taxPointDate) },
          data.originalInvoiceId
            ? {
                label: t('settlement.original'),
                value: (
                  <Anchor component={Link} to={`/settlement/invoices/${data.originalInvoiceId}`} size="sm">
                    {t('settlement.openOriginal')}
                  </Anchor>
                ),
              }
            : { label: t('settlement.supplierRef'), value: data.counterpartyInvoiceRef ?? '—' },
        ]}
        aside={
          <LifecycleTimeline
            title={t('settlement.lifecycle.title')}
            steps={invoiceSteps(data, t)}
            testId="invoice-lifecycle"
          />
        }
      >
        <SimpleGrid cols={{ base: 1, sm: 2 }}>
          <PartyCard
            title={t('settlement.supplier')}
            party={data.supplier}
            fallback={receivable ? entityName : counterpartyName}
          />
          <PartyCard
            title={t('settlement.customer')}
            party={data.customer}
            fallback={receivable ? counterpartyName : entityName}
          />
        </SimpleGrid>
        <Lines invoice={data} />
        {data.status === 'ISSUED' && <Allocations invoice={data} />}
      </DetailLayout>
      {reason === 'discard' && (
        <ReasonModal
          title={t('settlement.discardTitle')}
          hint={t('settlement.discardHint')}
          action={t('settlement.discard')}
          pending={discard.isPending}
          error={discard.error}
          onSubmit={(text) => discard.mutate({ ...path, body: { reason: text } })}
          onClose={() => setReason(null)}
        />
      )}
      {reason === 'credit' && (
        <ReasonModal
          title={t('settlement.creditNoteTitle', { number: data.number ?? '' })}
          hint={t('settlement.creditNoteHint')}
          action={t('settlement.draftCreditNote')}
          pending={credit.isPending}
          error={credit.error}
          onSubmit={(text) => credit.mutate({ ...path, body: { reason: text } })}
          onClose={() => setReason(null)}
        />
      )}
    </Stack>
  );
}
