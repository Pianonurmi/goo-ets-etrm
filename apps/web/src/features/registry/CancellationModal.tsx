import { Button, Group, Modal, NumberInput, Select, Stack, Text, TextInput } from '@mantine/core';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useNavigate } from 'react-router';
import { $api } from '@/shared/api/client';
import type { Schema } from '@/shared/api/types';
import { notifySuccess } from '@/shared/components/notify';
import { ProblemAlert } from '@/shared/components/ProblemAlert';
import { newKey } from '@/shared/ids';
import { cancellationReady, emptyCancellation, toCancellation, type CancellationValues } from './model/allowances';
import { useInvalidateRegistry } from './TransferModal';

type Lot = Schema<'LotView'>;
type Purpose = CancellationValues['purpose'];
const PURPOSES: Purpose[] = ['DISCLOSURE', 'OWN_CONSUMPTION', 'VOLUNTARY', 'COMPLIANCE', 'ON_BEHALF_OF_THIRD_PARTY'];

/**
 * Cancels a lot's certificates in the registry for a beneficiary (disclosure to consumers, own consumption). The
 * instruction waits for a second person's approval in the inbox; the lot leaves our books when the registry confirms.
 */
export function CancellationModal({ lot, onClose }: { lot: Lot; onClose: () => void }) {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const invalidate = useInvalidateRegistry();
  const [values, setValues] = useState<CancellationValues>(() => emptyCancellation(lot));
  const [key] = useState(() => newKey('ui-cancellation'));
  const set = (changes: Partial<CancellationValues>) => setValues((current) => ({ ...current, ...changes }));
  const submit = $api.useMutation('post', '/api/v1/registry/instructions/cancellations', {
    meta: { notify: false },
    onSuccess: (instruction) => {
      notifySuccess(t('registry.cancellationSubmitted'));
      void invalidate();
      void navigate(`/registry/instructions/${instruction.id}`);
    },
  });
  return (
    <Modal opened onClose={onClose} title={t('registry.cancellation')} size="lg">
      <Stack>
        <Text size="sm" c="dimmed">
          {t('registry.cancellationHint')}
        </Text>
        <ProblemAlert error={submit.error} />
        <Text size="sm">
          {t('registry.transferOf', { issuance: lot.issuanceRef, start: lot.range.start, end: lot.range.end })}
        </Text>
        <Group grow>
          <Select
            label={t('inventory.purpose')}
            data={PURPOSES.map((purpose) => ({ value: purpose, label: t(`inventory.purposes.${purpose}`) }))}
            value={values.purpose}
            onChange={(value) => value && set({ purpose: value })}
            allowDeselect={false}
            data-testid="cancellation-purpose"
          />
          <NumberInput
            label={t('registry.quantityMwh')}
            description={t('registry.quantityHint', { max: lot.quantityMwh })}
            value={values.quantity}
            onChange={(value) => set({ quantity: typeof value === 'number' ? value : '' })}
            min={1}
            max={lot.quantityMwh}
            allowDecimal={false}
            data-testid="cancellation-quantity"
          />
        </Group>
        <Group grow>
          <TextInput
            label={t('inventory.beneficiary')}
            value={values.beneficiaryName}
            onChange={(e) => set({ beneficiaryName: e.currentTarget.value })}
            data-testid="cancellation-beneficiary"
          />
          <TextInput
            label={t('registry.beneficiaryId')}
            description={t('registry.beneficiaryIdHint')}
            value={values.beneficiaryId}
            onChange={(e) => set({ beneficiaryId: e.currentTarget.value })}
          />
        </Group>
        <Group grow>
          <TextInput
            label={t('inventory.consumptionCountry')}
            value={values.country}
            maxLength={2}
            onChange={(e) => set({ country: e.currentTarget.value.toUpperCase() })}
            required
            data-testid="cancellation-country"
          />
          <TextInput
            type="date"
            label={t('inventory.consumptionFrom')}
            value={values.from}
            onChange={(e) => set({ from: e.currentTarget.value })}
            required
            data-testid="cancellation-from"
          />
          <TextInput
            type="date"
            label={t('inventory.consumptionTo')}
            value={values.to}
            onChange={(e) => set({ to: e.currentTarget.value })}
            required
            data-testid="cancellation-to"
          />
        </Group>
        <Button
          color="violet"
          disabled={!cancellationReady(values, lot.quantityMwh)}
          loading={submit.isPending}
          onClick={() => submit.mutate({ body: toCancellation(lot.id, values, key) })}
          data-testid="cancellation-submit"
        >
          {t('risk.sendForApproval')}
        </Button>
      </Stack>
    </Modal>
  );
}
