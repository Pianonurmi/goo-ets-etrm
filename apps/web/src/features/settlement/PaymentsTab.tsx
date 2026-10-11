import {
  Button,
  FileButton,
  Group,
  Modal,
  MultiSelect,
  Select,
  Stack,
  Switch,
  Text,
  TextInput,
  Textarea,
} from '@mantine/core';
import { IconCash, IconFileImport, IconLink } from '@tabler/icons-react';
import { useMutation } from '@tanstack/react-query';
import type { ColDef, ICellRendererParams } from 'ag-grid-community';
import dayjs from 'dayjs';
import { useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useSearchParams } from 'react-router';
import { $api } from '@/shared/api/client';
import { decimal } from '@/shared/api/types';
import { uploadText } from '@/shared/api/upload';
import { Can } from '@/shared/auth/Can';
import { notifySuccess } from '@/shared/components/notify';
import { ProblemAlert } from '@/shared/components/ProblemAlert';
import { QueryState } from '@/shared/components/QueryState';
import { formatMoney } from '@/shared/format/format';
import { DataGrid } from '@/shared/grid/DataGrid';
import { useCounterparties } from '@/features/trades/hooks';
import { useInvalidateSettlement } from './hooks';
import { allocatableInvoices, csvLineCount, sign, type Payment, type PaymentDirection } from './model/payments';

function invoiceLabel(invoice: { number?: string | null; outstanding: number; currency: string }): string {
  return `${invoice.number ?? '—'} · ${formatMoney(invoice.outstanding, invoice.currency)}`;
}

/** Issued invoices of the entity with something still to pay, for choosing what a payment settles. */
function useOpenInvoices(entityId: string) {
  return $api.useQuery('get', '/api/v1/settlement/invoices', {
    params: { query: { legalEntityId: entityId, status: ['ISSUED'] } },
  });
}

/** A bank payment; applied to the chosen invoices, else to invoice numbers found in the remittance text. */
function RecordPaymentModal({ entityId, onClose }: { entityId: string; onClose: () => void }) {
  const { t } = useTranslation();
  const counterparties = useCounterparties();
  const invalidate = useInvalidateSettlement();
  const invoices = useOpenInvoices(entityId);
  const [direction, setDirection] = useState<PaymentDirection>('INCOMING');
  const [counterpartyId, setCounterpartyId] = useState<string | null>(null);
  const [amount, setAmount] = useState('');
  const [currency, setCurrency] = useState('EUR');
  const [valueDate, setValueDate] = useState(dayjs().format('YYYY-MM-DD'));
  const [bankReference, setBankReference] = useState('');
  const [remittance, setRemittance] = useState('');
  const [invoiceIds, setInvoiceIds] = useState<string[]>([]);
  const record = $api.useMutation('post', '/api/v1/settlement/payments', {
    meta: { notify: false },
    onSuccess: (payment) => {
      notifySuccess(
        sign(payment.unappliedAmount) > 0
          ? t('settlement.paymentRecordedUnapplied', { amount: formatMoney(payment.unappliedAmount, payment.currency) })
          : t('settlement.paymentRecorded'),
      );
      void invalidate();
      onClose();
    },
  });
  const candidates = useMemo(
    () =>
      allocatableInvoices(invoices.data ?? [], {
        direction,
        currency: currency.toUpperCase(),
        counterpartyId,
      } as Payment),
    [invoices.data, direction, currency, counterpartyId],
  );
  const ready = amount.trim() && currency.trim().length === 3 && valueDate && bankReference.trim();
  return (
    <Modal opened onClose={onClose} title={t('settlement.recordPayment')} size="lg">
      <Stack>
        <ProblemAlert error={record.error} />
        <Text size="sm" c="dimmed">
          {t('settlement.recordPaymentHint')}
        </Text>
        <Group grow>
          <Select
            label={t('settlement.direction')}
            data={[
              { value: 'INCOMING', label: t('settlement.paymentDirections.INCOMING') },
              { value: 'OUTGOING', label: t('settlement.paymentDirections.OUTGOING') },
            ]}
            value={direction}
            allowDeselect={false}
            onChange={(value) => value && setDirection(value)}
          />
          <Select
            label={t('trades.columns.counterparty')}
            data={counterparties.list.map((c) => ({ value: c.id, label: `${c.code} · ${c.name}` }))}
            value={counterpartyId}
            onChange={setCounterpartyId}
            searchable
            clearable
            data-testid="payment-counterparty"
          />
        </Group>
        <Group grow>
          <TextInput
            label={t('settlement.amount')}
            inputMode="decimal"
            value={amount}
            onChange={(e) => setAmount(e.currentTarget.value)}
            required
            data-testid="payment-amount"
          />
          <TextInput
            label={t('trades.form.currency')}
            value={currency}
            maxLength={3}
            onChange={(e) => setCurrency(e.currentTarget.value.toUpperCase())}
          />
          <TextInput
            type="date"
            label={t('settlement.valueDate')}
            value={valueDate}
            onChange={(e) => setValueDate(e.currentTarget.value)}
            required
          />
        </Group>
        <TextInput
          label={t('settlement.bankReference')}
          description={t('settlement.bankReferenceHint')}
          value={bankReference}
          onChange={(e) => setBankReference(e.currentTarget.value)}
          required
          data-testid="payment-reference"
        />
        <TextInput
          label={t('settlement.remittance')}
          value={remittance}
          onChange={(e) => setRemittance(e.currentTarget.value)}
          data-testid="payment-remittance"
        />
        <MultiSelect
          label={t('settlement.paysInvoices')}
          description={t('settlement.paysInvoicesHint')}
          data={candidates.map((invoice) => ({ value: invoice.id, label: invoiceLabel(invoice) }))}
          value={invoiceIds}
          onChange={setInvoiceIds}
          searchable
          clearable
          data-testid="payment-invoices"
        />
        <Button
          disabled={!ready}
          loading={record.isPending}
          data-testid="payment-submit"
          onClick={() =>
            record.mutate({
              body: {
                legalEntityId: entityId,
                direction,
                counterpartyId: counterpartyId ?? undefined,
                amount: decimal(amount),
                currency,
                valueDate,
                bankReference: bankReference.trim(),
                remittanceInfo: remittance.trim() || undefined,
                invoiceIds,
              },
            })
          }
        >
          {t('settlement.record')}
        </Button>
      </Stack>
    </Modal>
  );
}

/** Bank statement lines pasted or read from a file; lines imported before are skipped by the server. */
function ImportPaymentsModal({ entityId, onClose }: { entityId: string; onClose: () => void }) {
  const { t } = useTranslation();
  const invalidate = useInvalidateSettlement();
  const [text, setText] = useState('');
  const upload = useMutation({
    mutationFn: () => uploadText<Payment[]>('/api/v1/settlement/payments/csv', { legalEntityId: entityId }, text),
    meta: { notify: false },
    onSuccess: (payments) => {
      notifySuccess(t('settlement.paymentsImported', { count: payments.length }));
      void invalidate();
      onClose();
    },
  });
  const lines = csvLineCount(text);
  return (
    <Modal opened onClose={onClose} title={t('settlement.importPayments')} size="lg">
      <Stack>
        <ProblemAlert error={upload.error} />
        <Text size="sm" c="dimmed">
          {t('settlement.importPaymentsHint')}
        </Text>
        <Textarea
          label={t('settlement.statementLines')}
          value={text}
          onChange={(e) => setText(e.currentTarget.value)}
          autosize
          minRows={6}
          maxRows={14}
          styles={{ input: { fontFamily: 'monospace' } }}
          data-testid="payments-csv"
        />
        <Group justify="space-between">
          <FileButton accept=".csv,text/csv,text/plain" onChange={(file) => void file?.text().then(setText)}>
            {(props) => (
              <Button variant="subtle" {...props}>
                {t('settlement.chooseFile')}
              </Button>
            )}
          </FileButton>
          <Text size="sm" c="dimmed">
            {t('settlement.linesToImport', { count: lines })}
          </Text>
        </Group>
        <Button
          disabled={lines === 0}
          loading={upload.isPending}
          onClick={() => upload.mutate()}
          data-testid="payments-import"
        >
          {t('settlement.import')}
        </Button>
      </Stack>
    </Modal>
  );
}

/** Applies a payment's unapplied cash to one invoice, all of it or a part. */
function AllocateModal({ entityId, payment, onClose }: { entityId: string; payment: Payment; onClose: () => void }) {
  const { t } = useTranslation();
  const invalidate = useInvalidateSettlement();
  const invoices = useOpenInvoices(entityId);
  const [invoiceId, setInvoiceId] = useState<string | null>(null);
  const [amount, setAmount] = useState('');
  const allocate = $api.useMutation('post', '/api/v1/settlement/payments/{id}/allocations', {
    meta: { notify: false },
    onSuccess: () => {
      notifySuccess(t('settlement.allocated'));
      void invalidate();
      onClose();
    },
  });
  const candidates = allocatableInvoices(invoices.data ?? [], payment);
  return (
    <Modal opened onClose={onClose} title={t('settlement.allocatePayment', { reference: payment.bankReference })}>
      <Stack>
        <ProblemAlert error={allocate.error} />
        <Text size="sm">
          {t('settlement.unappliedCash', { amount: formatMoney(payment.unappliedAmount, payment.currency) })}
        </Text>
        <Select
          label={t('settlement.invoice')}
          data={candidates.map((invoice) => ({ value: invoice.id, label: invoiceLabel(invoice) }))}
          value={invoiceId}
          onChange={setInvoiceId}
          nothingFoundMessage={t('settlement.noOpenInvoices')}
          searchable
          data-testid="allocate-invoice"
        />
        <TextInput
          label={t('settlement.amount')}
          description={t('settlement.allocateAmountHint')}
          inputMode="decimal"
          value={amount}
          onChange={(e) => setAmount(e.currentTarget.value)}
        />
        <Button
          disabled={!invoiceId}
          loading={allocate.isPending}
          data-testid="allocate-submit"
          onClick={() =>
            invoiceId &&
            allocate.mutate({
              params: { path: { id: payment.id } },
              body: { invoiceId, amount: amount.trim() ? decimal(amount) : undefined },
            })
          }
        >
          {t('settlement.allocate')}
        </Button>
      </Stack>
    </Modal>
  );
}

/** Payments received and made, with the cash not yet applied to an invoice. */
export function PaymentsTab({ entityId }: { entityId: string }) {
  const { t } = useTranslation();
  const [params, setParams] = useSearchParams();
  const counterparties = useCounterparties();
  const unappliedOnly = params.get('unapplied') === 'true';
  const [recording, setRecording] = useState(false);
  const [importing, setImporting] = useState(false);
  const [allocating, setAllocating] = useState<Payment | null>(null);
  const payments = $api.useQuery('get', '/api/v1/settlement/payments', {
    params: { query: { legalEntityId: entityId, unappliedOnly } },
  });
  const columns = useMemo<ColDef<Payment>[]>(
    () => [
      { field: 'valueDate', headerName: t('settlement.valueDate'), width: 120, sort: 'desc' },
      {
        field: 'direction',
        headerName: t('settlement.direction'),
        width: 130,
        valueFormatter: (p) => t(`settlement.paymentDirections.${String(p.value)}`),
      },
      {
        colId: 'counterparty',
        headerName: t('trades.columns.counterparty'),
        width: 210,
        valueGetter: (p) =>
          p.data?.counterpartyId ? (counterparties.byId.get(p.data.counterpartyId)?.name ?? '') : '',
      },
      {
        field: 'amount',
        headerName: t('settlement.amount'),
        width: 140,
        type: 'rightAligned',
        valueFormatter: (p) => (p.data ? formatMoney(p.data.amount, p.data.currency) : ''),
      },
      {
        field: 'unappliedAmount',
        headerName: t('settlement.unapplied'),
        width: 140,
        type: 'rightAligned',
        valueFormatter: (p) => (p.data ? formatMoney(p.data.unappliedAmount, p.data.currency) : ''),
      },
      { field: 'bankReference', headerName: t('settlement.bankReference'), width: 180 },
      { field: 'remittanceInfo', headerName: t('settlement.remittance'), flex: 1, minWidth: 200 },
      { field: 'source', headerName: t('settlement.source'), width: 100 },
      { field: 'createdBy', headerName: t('settlement.createdBy'), width: 120 },
      {
        colId: 'actions',
        headerName: '',
        width: 130,
        pinned: 'right',
        sortable: false,
        filter: false,
        cellRenderer: (p: ICellRendererParams<Payment>) =>
          p.data &&
          sign(p.data.unappliedAmount) > 0 && (
            <Can op="payment_allocate">
              <Button
                size="compact-xs"
                variant="light"
                leftSection={<IconLink size={12} />}
                onClick={() => p.data && setAllocating(p.data)}
              >
                {t('settlement.allocate')}
              </Button>
            </Can>
          ),
      },
    ],
    [t, counterparties.byId, setAllocating],
  );
  return (
    <Stack>
      <Group justify="space-between">
        <Switch
          label={t('settlement.unappliedOnly')}
          checked={unappliedOnly}
          onChange={(e) => {
            const next = new URLSearchParams(params);
            if (e.currentTarget.checked) next.set('unapplied', 'true');
            else next.delete('unapplied');
            setParams(next, { replace: true });
          }}
        />
        <Group gap="xs">
          <Can op="payment_importCsv">
            <Button variant="light" leftSection={<IconFileImport size={16} />} onClick={() => setImporting(true)}>
              {t('settlement.importPayments')}
            </Button>
          </Can>
          <Can op="payment_record">
            <Button
              leftSection={<IconCash size={16} />}
              onClick={() => setRecording(true)}
              data-testid="record-payment"
            >
              {t('settlement.recordPayment')}
            </Button>
          </Can>
        </Group>
      </Group>
      <QueryState isPending={payments.isPending} error={payments.error}>
        <DataGrid<Payment> rowData={payments.data ?? []} columnDefs={columns} rowId={(row) => row.id} height={460} />
      </QueryState>
      {recording && <RecordPaymentModal entityId={entityId} onClose={() => setRecording(false)} />}
      {importing && <ImportPaymentsModal entityId={entityId} onClose={() => setImporting(false)} />}
      {allocating && <AllocateModal entityId={entityId} payment={allocating} onClose={() => setAllocating(null)} />}
    </Stack>
  );
}
