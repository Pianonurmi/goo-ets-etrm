import { Money } from '@/shared/components/Figures';
import { Anchor, Button, Checkbox, Group, Modal, Select, Stack, Table, Text, TextInput, Textarea } from '@mantine/core';
import dayjs from 'dayjs';
import { useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link } from 'react-router';
import { $api } from '@/shared/api/client';
import { decimal } from '@/shared/api/types';
import { notifySuccess } from '@/shared/components/notify';
import { ProblemAlert } from '@/shared/components/ProblemAlert';
import { formatDate, formatQuantity } from '@/shared/format/format';
import { useCounterparties } from '@/features/trades/hooks';
import { useInvalidateSettlement } from './hooks';
import { purchaseCandidates, type Invoice, type Item } from './model/settlement';

const today = () => dayjs().format('YYYY-MM-DD');

function counterpartyOptions(list: { id: string; code: string; name: string }[]) {
  return list.map((c) => ({ value: c.id, label: `${c.code} · ${c.name}` }));
}

/** Drafts sales invoices for open items delivered up to a date; the drafts are listed with links. */
export function InvoiceRunModal({ entityId, onClose }: { entityId: string; onClose: () => void }) {
  const { t } = useTranslation();
  const counterparties = useCounterparties();
  const invalidate = useInvalidateSettlement();
  const [upTo, setUpTo] = useState(today());
  const [invoiceDate, setInvoiceDate] = useState('');
  const [counterpartyId, setCounterpartyId] = useState<string | null>(null);
  const [drafts, setDrafts] = useState<Invoice[] | null>(null);
  const run = $api.useMutation('post', '/api/v1/settlement/invoice-runs', {
    meta: { notify: false },
    onSuccess: (created) => {
      setDrafts(created);
      void invalidate();
    },
  });
  return (
    <Modal opened onClose={onClose} title={t('settlement.runInvoices')} size="lg">
      <Stack>
        <ProblemAlert error={run.error} />
        <Text size="sm" c="dimmed">
          {t('settlement.runHint')}
        </Text>
        <Group grow>
          <TextInput
            type="date"
            label={t('settlement.upTo')}
            value={upTo}
            onChange={(e) => setUpTo(e.currentTarget.value)}
            required
            data-testid="run-up-to"
          />
          <TextInput
            type="date"
            label={t('settlement.invoiceDate')}
            description={t('settlement.invoiceDateHint')}
            value={invoiceDate}
            onChange={(e) => setInvoiceDate(e.currentTarget.value)}
          />
        </Group>
        <Select
          label={t('trades.columns.counterparty')}
          placeholder={t('settlement.allCounterparties')}
          data={counterpartyOptions(counterparties.list)}
          value={counterpartyId}
          onChange={setCounterpartyId}
          searchable
          clearable
          data-testid="run-counterparty"
        />
        <Button
          onClick={() =>
            run.mutate({
              body: {
                legalEntityId: entityId,
                upTo,
                counterpartyId: counterpartyId ?? undefined,
                invoiceDate: invoiceDate || undefined,
              },
            })
          }
          disabled={!upTo}
          loading={run.isPending}
          data-testid="run-submit"
        >
          {t('settlement.draftInvoices')}
        </Button>
        {drafts && (
          <Stack gap="xs" data-testid="run-result">
            <Text size="sm" fw={500}>
              {t('settlement.draftsCreated', { count: drafts.length })}
            </Text>
            {drafts.length > 0 && (
              <Table>
                <Table.Tbody>
                  {drafts.map((draft) => (
                    <Table.Tr key={draft.id}>
                      <Table.Td>
                        <Anchor component={Link} to={`/settlement/invoices/${draft.id}`} size="sm">
                          {counterparties.byId.get(draft.counterpartyId)?.name ?? draft.counterpartyId}
                        </Anchor>
                      </Table.Td>
                      <Table.Td>{t('settlement.lineCount', { count: draft.lines.length })}</Table.Td>
                      <Table.Td ta="right">
                        <Money value={draft.grossTotal} currency={draft.currency} />
                      </Table.Td>
                    </Table.Tr>
                  ))}
                </Table.Tbody>
              </Table>
            )}
          </Stack>
        )}
      </Stack>
    </Modal>
  );
}

/** A supplier's invoice, matched to our open purchases from that supplier; it is a draft until approved. */
export function PurchaseInvoiceModal({
  entityId,
  onClose,
  onRegistered,
}: {
  entityId: string;
  onClose: () => void;
  onRegistered: (invoice: Invoice) => void;
}) {
  const { t } = useTranslation();
  const counterparties = useCounterparties();
  const invalidate = useInvalidateSettlement();
  const [counterpartyId, setCounterpartyId] = useState<string | null>(null);
  const [reference, setReference] = useState('');
  const [invoiceDate, setInvoiceDate] = useState(today());
  const [currency, setCurrency] = useState('EUR');
  const [net, setNet] = useState('');
  const [vat, setVat] = useState('0');
  const [selected, setSelected] = useState<Set<string> | null>(null);
  const open = $api.useQuery('get', '/api/v1/settlement/items', {
    params: { query: { legalEntityId: entityId, status: ['OPEN'] } },
  });
  const candidates = useMemo(
    () => purchaseCandidates(open.data ?? [], counterpartyId, currency),
    [open.data, counterpartyId, currency],
  );
  const chosen = selected ?? new Set(candidates.map((item) => item.id));
  const register = $api.useMutation('post', '/api/v1/settlement/purchase-invoices', {
    meta: { notify: false },
    onSuccess: (invoice) => {
      notifySuccess(t('settlement.purchaseRegistered'));
      void invalidate();
      onRegistered(invoice);
    },
  });
  const toggle = (id: string, on: boolean) => {
    const next = new Set(chosen);
    if (on) next.add(id);
    else next.delete(id);
    setSelected(next);
  };
  const ready = counterpartyId && reference.trim() && invoiceDate && net.trim() && vat.trim() && chosen.size > 0;
  return (
    <Modal opened onClose={onClose} title={t('settlement.registerPurchase')} size="xl">
      <Stack>
        <ProblemAlert error={register.error} />
        <Text size="sm" c="dimmed">
          {t('settlement.purchaseHint')}
        </Text>
        <Group grow>
          <Select
            label={t('settlement.supplier')}
            data={counterpartyOptions(counterparties.list)}
            value={counterpartyId}
            onChange={(value) => {
              setCounterpartyId(value);
              setSelected(null);
            }}
            searchable
            required
            data-testid="purchase-supplier"
          />
          <TextInput
            label={t('settlement.supplierRef')}
            value={reference}
            onChange={(e) => setReference(e.currentTarget.value)}
            required
            data-testid="purchase-reference"
          />
        </Group>
        <Group grow>
          <TextInput
            type="date"
            label={t('settlement.invoiceDate')}
            value={invoiceDate}
            onChange={(e) => setInvoiceDate(e.currentTarget.value)}
            required
          />
          <TextInput
            label={t('trades.form.currency')}
            value={currency}
            maxLength={3}
            onChange={(e) => {
              setCurrency(e.currentTarget.value.toUpperCase());
              setSelected(null);
            }}
          />
          <TextInput
            label={t('settlement.netAmount')}
            inputMode="decimal"
            value={net}
            onChange={(e) => setNet(e.currentTarget.value)}
            required
            data-testid="purchase-net"
          />
          <TextInput
            label={t('settlement.vatAmount')}
            inputMode="decimal"
            value={vat}
            onChange={(e) => setVat(e.currentTarget.value)}
            required
            data-testid="purchase-vat"
          />
        </Group>
        <Text size="sm" fw={500}>
          {t('settlement.matchedDeliveries')}
        </Text>
        {candidates.length === 0 ? (
          <Text size="sm" c="dimmed" data-testid="purchase-no-items">
            {counterpartyId ? t('settlement.noOpenPurchases') : t('settlement.chooseSupplier')}
          </Text>
        ) : (
          <Table>
            <Table.Thead>
              <Table.Tr>
                <Table.Th />
                <Table.Th>{t('settlement.trade')}</Table.Th>
                <Table.Th>{t('settlement.deliveredOn')}</Table.Th>
                <Table.Th ta="right">{t('settlement.quantity')}</Table.Th>
                <Table.Th ta="right">{t('settlement.netAmount')}</Table.Th>
                <Table.Th ta="right">{t('settlement.vatAmount')}</Table.Th>
              </Table.Tr>
            </Table.Thead>
            <Table.Tbody>
              {candidates.map((item) => (
                <Table.Tr key={item.id}>
                  <Table.Td>
                    <Checkbox
                      aria-label={item.tradeNumber}
                      checked={chosen.has(item.id)}
                      onChange={(e) => toggle(item.id, e.currentTarget.checked)}
                    />
                  </Table.Td>
                  <Table.Td>{item.tradeNumber}</Table.Td>
                  <Table.Td>{formatDate(item.deliveredOn)}</Table.Td>
                  <Table.Td ta="right">{formatQuantity(item.quantity, item.unit)}</Table.Td>
                  <Table.Td ta="right">
                    <Money value={item.netAmount} currency={item.currency} />
                  </Table.Td>
                  <Table.Td ta="right">
                    <Money value={item.vatAmount} currency={item.currency} />
                  </Table.Td>
                </Table.Tr>
              ))}
            </Table.Tbody>
          </Table>
        )}
        <Button
          disabled={!ready}
          loading={register.isPending}
          data-testid="purchase-submit"
          onClick={() =>
            counterpartyId &&
            register.mutate({
              body: {
                legalEntityId: entityId,
                counterpartyId,
                supplierInvoiceRef: reference.trim(),
                invoiceDate,
                currency,
                netAmount: decimal(net),
                vatAmount: decimal(vat),
                itemIds: [...chosen],
              },
            })
          }
        >
          {t('settlement.register')}
        </Button>
      </Stack>
    </Modal>
  );
}

/** The price of an index or formula delivery, fixed by hand; its items are evaluated again. */
export function FixPriceModal({ item, onClose }: { item: Item; onClose: () => void }) {
  const { t } = useTranslation();
  const invalidate = useInvalidateSettlement();
  const [price, setPrice] = useState('');
  const [note, setNote] = useState('');
  const fix = $api.useMutation('post', '/api/v1/settlement/price-fixings', {
    meta: { notify: false },
    onSuccess: () => {
      notifySuccess(t('settlement.priceFixed'));
      void invalidate();
      onClose();
    },
  });
  return (
    <Modal opened onClose={onClose} title={t('settlement.fixPriceOf', { trade: item.tradeNumber })}>
      <Stack>
        <ProblemAlert error={fix.error} />
        <Text size="sm">{item.problem}</Text>
        <TextInput
          label={t('settlement.unitPrice', { currency: item.currency, unit: item.unit })}
          inputMode="decimal"
          value={price}
          onChange={(e) => setPrice(e.currentTarget.value)}
          required
          data-testid="fix-price"
        />
        <Textarea label={t('settlement.note')} value={note} onChange={(e) => setNote(e.currentTarget.value)} autosize />
        <Button
          disabled={!price.trim()}
          loading={fix.isPending}
          onClick={() =>
            fix.mutate({ body: { scheduleId: item.scheduleId, price: decimal(price), note: note.trim() || undefined } })
          }
        >
          {t('settlement.fixPrice')}
        </Button>
      </Stack>
    </Modal>
  );
}
