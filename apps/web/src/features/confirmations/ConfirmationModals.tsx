import { Button, FileButton, Group, Modal, Select, Stack, Text, TextInput, Textarea } from '@mantine/core';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useNavigate } from 'react-router';
import { $api } from '@/shared/api/client';
import { uploadText } from '@/shared/api/upload';
import { notifySuccess } from '@/shared/components/notify';
import { ProblemAlert } from '@/shared/components/ProblemAlert';
import { useCounterparties } from '@/features/trades/hooks';
import { enteredTerms, type Confirmation } from './model/confirmations';

export function useInvalidateConfirmations() {
  const queryClient = useQueryClient();
  return () =>
    queryClient.invalidateQueries({
      predicate: (q) =>
        typeof q.queryKey[1] === 'string' &&
        ['/api/v1/confirmations', '/api/v1/trades', '/api/v1/workflow'].some((prefix) =>
          (q.queryKey[1] as string).startsWith(prefix),
        ),
    });
}

/**
 * The counterparty's confirmation entered by hand. It starts from our terms; the fields counterparties most often
 * state differently are edited, and the server matches the result against ours.
 */
export function ReceiveConfirmationModal({ ours, onClose }: { ours: Confirmation; onClose: () => void }) {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const invalidate = useInvalidateConfirmations();
  const leg = ours.terms.legs[0];
  const [reference, setReference] = useState('');
  const [quantity, setQuantity] = useState(leg ? String(leg.quantity) : '');
  const [price, setPrice] = useState(leg ? String(leg.price) : '');
  const [amount, setAmount] = useState(leg ? String(leg.amount) : '');
  const [deliveryDate, setDeliveryDate] = useState(leg?.deliveries[0]?.deliveryDate ?? '');
  const receive = $api.useMutation('post', '/api/v1/confirmations/counterparty', {
    meta: { notify: false },
    onSuccess: (theirs) => {
      notifySuccess(t(`confirmations.received.${theirs.status}`, { defaultValue: t('confirmations.receivedAny') }));
      void invalidate();
      void navigate(`/confirmations/${theirs.id}`);
    },
  });
  return (
    <Modal opened onClose={onClose} title={t('confirmations.enterTheirs')} size="lg">
      <Stack>
        <ProblemAlert error={receive.error} />
        <Text size="sm" c="dimmed">
          {t('confirmations.enterHint')}
        </Text>
        <TextInput
          label={t('confirmations.theirReference')}
          value={reference}
          onChange={(e) => setReference(e.currentTarget.value)}
          required
          data-testid="theirs-reference"
        />
        <Group grow>
          <TextInput
            label={t('confirmations.quantity', { unit: leg?.unit ?? '' })}
            inputMode="decimal"
            value={quantity}
            onChange={(e) => setQuantity(e.currentTarget.value)}
          />
          <TextInput
            label={t('confirmations.price', { currency: leg?.currency ?? '' })}
            inputMode="decimal"
            value={price}
            onChange={(e) => setPrice(e.currentTarget.value)}
            data-testid="theirs-price"
          />
          <TextInput
            label={t('confirmations.amount', { currency: leg?.currency ?? '' })}
            inputMode="decimal"
            value={amount}
            onChange={(e) => setAmount(e.currentTarget.value)}
            data-testid="theirs-amount"
          />
        </Group>
        <TextInput
          type="date"
          label={t('confirmations.deliveryDate')}
          value={deliveryDate}
          onChange={(e) => setDeliveryDate(e.currentTarget.value)}
          w={200}
        />
        <Button
          disabled={!reference.trim() || !quantity.trim() || !price.trim() || !amount.trim()}
          loading={receive.isPending}
          data-testid="theirs-submit"
          onClick={() =>
            receive.mutate({
              body: {
                terms: enteredTerms(ours.terms, { quantity, price, amount, deliveryDate }),
                reference: reference.trim(),
                counterpartyId: ours.counterpartyId ?? undefined,
                source: 'MANUAL',
              },
            })
          }
        >
          {t('confirmations.receiveAndMatch')}
        </Button>
      </Stack>
    </Modal>
  );
}

/** A counterparty's CSV confirmations: one line per delivery, grouped by `confirmation_ref`. */
export function UploadConfirmationsModal({ onClose }: { onClose: () => void }) {
  const { t } = useTranslation();
  const counterparties = useCounterparties();
  const invalidate = useInvalidateConfirmations();
  const [counterpartyId, setCounterpartyId] = useState<string | null>(null);
  const [text, setText] = useState('');
  const upload = useMutation({
    mutationFn: () =>
      uploadText<Confirmation[]>(
        '/api/v1/confirmations/counterparty/csv',
        { counterpartyId: counterpartyId ?? '' },
        text,
      ),
    meta: { notify: false },
    onSuccess: (received) => {
      notifySuccess(t('confirmations.uploaded', { count: received.length }));
      void invalidate();
      onClose();
    },
  });
  return (
    <Modal opened onClose={onClose} title={t('confirmations.upload')} size="lg">
      <Stack>
        <ProblemAlert error={upload.error} />
        <Text size="sm" c="dimmed">
          {t('confirmations.uploadHint')}
        </Text>
        <Select
          label={t('trades.columns.counterparty')}
          data={counterparties.list.map((c) => ({ value: c.id, label: `${c.code} · ${c.name}` }))}
          value={counterpartyId}
          onChange={setCounterpartyId}
          searchable
          required
        />
        <Textarea
          label={t('confirmations.csv')}
          value={text}
          onChange={(e) => setText(e.currentTarget.value)}
          autosize
          minRows={6}
          maxRows={14}
          styles={{ input: { fontFamily: 'monospace' } }}
        />
        <Group justify="space-between">
          <FileButton accept=".csv,text/csv,text/plain" onChange={(file) => void file?.text().then(setText)}>
            {(props) => (
              <Button variant="subtle" {...props}>
                {t('settlement.chooseFile')}
              </Button>
            )}
          </FileButton>
          <Button disabled={!counterpartyId || !text.trim()} loading={upload.isPending} onClick={() => upload.mutate()}>
            {t('reconciliation.import')}
          </Button>
        </Group>
      </Stack>
    </Modal>
  );
}
