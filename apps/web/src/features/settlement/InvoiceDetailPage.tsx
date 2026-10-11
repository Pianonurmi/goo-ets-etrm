import { Anchor, Badge, Button, Card, Group, List, SimpleGrid, Stack, Table, Text, Title } from '@mantine/core';
import { IconArrowLeft, IconFileTypePdf, IconInbox, IconReceiptRefund, IconSend, IconTrash } from '@tabler/icons-react';
import { useState, type ReactNode } from 'react';
import { useTranslation } from 'react-i18next';
import { Link, useNavigate, useParams } from 'react-router';
import { $api } from '@/shared/api/client';
import { download } from '@/shared/api/download';
import type { Schema } from '@/shared/api/types';
import { Can } from '@/shared/auth/Can';
import { notifySuccess } from '@/shared/components/notify';
import { QueryState } from '@/shared/components/QueryState';
import { formatDate, formatDateTime, formatDecimal, formatMoney, formatQuantity } from '@/shared/format/format';
import { useCounterparties } from '@/features/trades/hooks';
import { useInvalidateSettlement } from './hooks';
import {
  INVOICE_STATUS_COLORS,
  PAYMENT_STATUS_COLORS,
  invoiceActions,
  paymentStatus,
  type Invoice,
} from './model/settlement';
import { ReasonModal } from './SettlementModals';
import { StatusBadge } from './SettlementPage';

type Party = Schema<'PartySnapshot'>;

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

function PartyCard({ title, party, fallback }: { title: string; party: Party | null | undefined; fallback: string }) {
  const { t } = useTranslation();
  return (
    <Card withBorder>
      <Text size="xs" c="dimmed">
        {title}
      </Text>
      {party ? (
        <Stack gap={0} mt={4}>
          <Text fw={500}>{party.name}</Text>
          <Text size="sm">{party.address ?? party.country}</Text>
          <Text size="sm">
            {t('settlement.vatNumber')}: {party.vatNumber ?? '—'}
          </Text>
          {party.lei && (
            <Text size="sm">
              {t('settlement.lei')}: {party.lei}
            </Text>
          )}
        </Stack>
      ) : (
        <Stack gap={0} mt={4}>
          <Text fw={500}>{fallback}</Text>
          <Text size="xs" c="dimmed">
            {t('settlement.frozenAtIssue')}
          </Text>
        </Stack>
      )}
    </Card>
  );
}

function Allocations({ invoice }: { invoice: Invoice }) {
  const { t } = useTranslation();
  const allocations = $api.useQuery('get', '/api/v1/settlement/invoices/{id}/allocations', {
    params: { path: { id: invoice.id } },
  });
  return (
    <Card withBorder>
      <Group justify="space-between" mb="sm">
        <Title order={5}>{t('settlement.allocations')}</Title>
        <Text size="sm" data-testid="invoice-paid">
          {t('settlement.paidOf', {
            paid: formatMoney(invoice.paidAmount, invoice.currency),
            gross: formatMoney(invoice.grossTotal, invoice.currency),
          })}
        </Text>
      </Group>
      <QueryState isPending={allocations.isPending} error={allocations.error}>
        {(allocations.data ?? []).length === 0 ? (
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
            <Table.Tbody>
              {(allocations.data ?? []).map((allocation) => (
                <Table.Tr key={allocation.id}>
                  <Table.Td>{formatDateTime(allocation.allocatedAt)}</Table.Td>
                  <Table.Td>{t(`settlement.allocationKinds.${allocation.kind}`)}</Table.Td>
                  <Table.Td>{allocation.allocatedBy}</Table.Td>
                  <Table.Td ta="right">{formatMoney(allocation.amount, invoice.currency)}</Table.Td>
                </Table.Tr>
              ))}
            </Table.Tbody>
          </Table>
        )}
      </QueryState>
    </Card>
  );
}

/** One invoice: parties, lines with their VAT treatment, totals, legal notes, approval and payments. */
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
  const actions = data ? invoiceActions(data) : null;
  const counterpartyName = data ? (counterparties.byId.get(data.counterpartyId)?.name ?? data.counterpartyId) : '';
  const entityName = entity.data?.name ?? '';
  const receivable = data?.direction === 'RECEIVABLE';
  const path = { params: { path: { id: invoiceId } } };
  return (
    <Stack>
      <Anchor component={Link} to="/settlement?tab=invoices" size="sm">
        <Group gap={4}>
          <IconArrowLeft size={14} />
          {t('settlement.back')}
        </Group>
      </Anchor>
      <QueryState isPending={invoice.isPending} error={invoice.error}>
        {data && actions && (
          <>
            <Card withBorder>
              <Group justify="space-between" align="flex-start">
                <Stack gap={4}>
                  <Group gap="xs">
                    <Title order={3} data-testid="invoice-number">
                      {data.number ?? t('settlement.draft')}
                    </Title>
                    <StatusBadge
                      label={data.status}
                      color={INVOICE_STATUS_COLORS[data.status]}
                      testId="invoice-status"
                    />
                    {data.status === 'ISSUED' && (
                      <StatusBadge
                        label={paymentStatus(data)}
                        color={PAYMENT_STATUS_COLORS[paymentStatus(data)]}
                        testId="invoice-payment-status"
                      />
                    )}
                    {data.credited && (
                      <Badge color="grape" variant="light">
                        {t('settlement.credited')}
                      </Badge>
                    )}
                  </Group>
                  <Text size="sm" c="dimmed">
                    {t(`settlement.kinds.${data.kind}`)} · {counterpartyName}
                  </Text>
                </Stack>
                <Group gap="xs">
                  {actions.awaitingApproval && task.data?.items[0] && (
                    <Button
                      component={Link}
                      to={`/inbox/${task.data.items[0].id}`}
                      variant="light"
                      leftSection={<IconInbox size={16} />}
                      data-testid="invoice-task"
                    >
                      {t('settlement.approvalTask')}
                    </Button>
                  )}
                  {actions.pdf && data.documentId && (
                    <Button
                      variant="light"
                      leftSection={<IconFileTypePdf size={16} />}
                      onClick={() =>
                        void download(`/api/v1/documents/${data.documentId}/content`, `${data.number ?? 'invoice'}.pdf`)
                      }
                      data-testid="invoice-pdf"
                    >
                      {t('settlement.pdf')}
                    </Button>
                  )}
                  {actions.creditNote && (
                    <Can op="payment_creditNote">
                      <Button
                        variant="light"
                        color="grape"
                        leftSection={<IconReceiptRefund size={16} />}
                        onClick={() => setReason('credit')}
                        data-testid="invoice-credit"
                      >
                        {t('settlement.creditNote')}
                      </Button>
                    </Can>
                  )}
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
                </Group>
              </Group>
              <SimpleGrid cols={{ base: 2, md: 4 }} mt="md">
                <Field label={t('settlement.invoiceDate')}>{formatDate(data.invoiceDate)}</Field>
                <Field label={t('settlement.taxPointDate')}>{formatDate(data.taxPointDate)}</Field>
                <Field label={t('settlement.dueDate')}>{formatDate(data.dueDate) || '—'}</Field>
                <Field label={t('settlement.supplierRef')}>{data.counterpartyInvoiceRef ?? '—'}</Field>
                <Field label={t('settlement.createdBy')}>{data.createdBy}</Field>
                <Field label={t('settlement.submittedBy')}>{data.submittedBy ?? '—'}</Field>
                <Field label={t('settlement.approvedBy')}>
                  {data.approvedBy ? `${data.approvedBy} · ${formatDateTime(data.issuedAt)}` : '—'}
                </Field>
                {data.originalInvoiceId && (
                  <Field label={t('settlement.original')}>
                    <Anchor component={Link} to={`/settlement/invoices/${data.originalInvoiceId}`} size="sm">
                      {t('settlement.openOriginal')}
                    </Anchor>
                  </Field>
                )}
              </SimpleGrid>
            </Card>
            <SimpleGrid cols={{ base: 1, md: 2 }}>
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
            <Card withBorder>
              <Title order={5} mb="sm">
                {t('settlement.lines')}
              </Title>
              <Table striped data-testid="invoice-lines">
                <Table.Thead>
                  <Table.Tr>
                    <Table.Th>#</Table.Th>
                    <Table.Th>{t('settlement.lineDescription')}</Table.Th>
                    <Table.Th ta="right">{t('settlement.quantity')}</Table.Th>
                    <Table.Th ta="right">{t('settlement.price')}</Table.Th>
                    <Table.Th ta="right">{t('settlement.netAmount')}</Table.Th>
                    <Table.Th>{t('settlement.vatTreatment')}</Table.Th>
                    <Table.Th ta="right">{t('settlement.vatRate')}</Table.Th>
                    <Table.Th ta="right">{t('settlement.vatAmount')}</Table.Th>
                  </Table.Tr>
                </Table.Thead>
                <Table.Tbody>
                  {data.lines.map((line) => (
                    <Table.Tr key={line.lineNo}>
                      <Table.Td>{line.lineNo}</Table.Td>
                      <Table.Td>
                        {line.description}
                        {line.vatRuleCode && (
                          <Text size="xs" c="dimmed">
                            {line.vatRuleCode}
                          </Text>
                        )}
                      </Table.Td>
                      <Table.Td ta="right">{formatQuantity(line.quantity, line.unit)}</Table.Td>
                      <Table.Td ta="right">{formatDecimal(line.unitPrice, 2, 6)}</Table.Td>
                      <Table.Td ta="right">{formatMoney(line.netAmount, data.currency)}</Table.Td>
                      <Table.Td>{t(`settlement.treatments.${line.vatTreatment}`)}</Table.Td>
                      <Table.Td ta="right">{formatDecimal(line.vatRate, 0, 2)} %</Table.Td>
                      <Table.Td ta="right">
                        {formatMoney(line.vatAmount, data.currency)}
                        {Number(line.selfAssessedVat) !== 0 && (
                          <Text size="xs" c="dimmed">
                            {t('settlement.selfAssessed', { amount: formatMoney(line.selfAssessedVat, data.currency) })}
                          </Text>
                        )}
                      </Table.Td>
                    </Table.Tr>
                  ))}
                </Table.Tbody>
                <Table.Tfoot>
                  <Table.Tr>
                    <Table.Th colSpan={4}>{t('settlement.netAmount')}</Table.Th>
                    <Table.Th ta="right">{formatMoney(data.netTotal, data.currency)}</Table.Th>
                    <Table.Th colSpan={2}>{t('settlement.vatAmount')}</Table.Th>
                    <Table.Th ta="right">{formatMoney(data.vatTotal, data.currency)}</Table.Th>
                  </Table.Tr>
                  <Table.Tr>
                    <Table.Th colSpan={7}>{t('settlement.grossAmount')}</Table.Th>
                    <Table.Th ta="right" data-testid="invoice-gross">
                      {formatMoney(data.grossTotal, data.currency)}
                    </Table.Th>
                  </Table.Tr>
                </Table.Tfoot>
              </Table>
              {data.notes.length > 0 && (
                <List size="sm" mt="md">
                  {data.notes.map((note) => (
                    <List.Item key={note}>{note}</List.Item>
                  ))}
                </List>
              )}
              {data.bankAccount && (
                <Text size="sm" mt="md">
                  {t('settlement.payInto', {
                    name: data.bankAccount.accountName,
                    iban: data.bankAccount.iban,
                    bic: data.bankAccount.bic,
                  })}
                </Text>
              )}
            </Card>
            {data.status === 'ISSUED' && <Allocations invoice={data} />}
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
          </>
        )}
      </QueryState>
    </Stack>
  );
}
