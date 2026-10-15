import { Button, Group, Modal, NumberInput, Select, Stack, TextInput } from '@mantine/core';
import dayjs from 'dayjs';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { $api } from '@/shared/api/client';
import type { Schema } from '@/shared/api/types';
import { notifySuccess } from '@/shared/components/notify';
import { ProblemAlert } from '@/shared/components/ProblemAlert';
import { useInvalidateEts } from './EtsPage';

export type EmissionKind = Schema<'RecordEmissions'>['kind'];

export function RecordEmissionsModal({
  onClose,
  installationId,
  year,
}: {
  onClose: () => void;
  installationId: string;
  year: number;
}) {
  const { t } = useTranslation();
  const invalidate = useInvalidateEts();
  const [kind, setKind] = useState<Exclude<EmissionKind, 'VERIFIED'>>('MONTHLY_ESTIMATE');
  const [month, setMonth] = useState<number>(Math.max(1, dayjs().month()));
  const [quantity, setQuantity] = useState<number | ''>('');
  const [note, setNote] = useState('');
  const record = $api.useMutation('post', '/api/v1/ets/installations/{id}/emissions', {
    meta: { notify: false },
    onSuccess: () => {
      notifySuccess(t('ets.emissionsRecorded'));
      void invalidate();
      onClose();
    },
  });
  return (
    <Modal opened onClose={onClose} title={t('ets.recordEmissions')}>
      <Stack>
        <ProblemAlert error={record.error} />
        <Group grow>
          <Select
            label={t('ets.kind')}
            data={[
              { value: 'MONTHLY_ESTIMATE', label: t('ets.emissionKinds.MONTHLY_ESTIMATE') },
              { value: 'FORECAST', label: t('ets.emissionKinds.FORECAST') },
            ]}
            value={kind}
            allowDeselect={false}
            onChange={(value) => value && setKind(value)}
          />
          {kind === 'MONTHLY_ESTIMATE' && (
            <NumberInput label={t('ets.month')} value={month} onChange={(v) => setMonth(Number(v))} min={1} max={12} />
          )}
        </Group>
        <NumberInput
          label={t('ets.quantity')}
          value={quantity}
          onChange={(v) => setQuantity(v === '' ? '' : Number(v))}
          min={0}
          allowDecimal={false}
          suffix=" t"
          data-testid="emissions-quantity"
        />
        <TextInput label={t('marketData.note')} value={note} onChange={(e) => setNote(e.currentTarget.value)} />
        <Button
          disabled={quantity === ''}
          loading={record.isPending}
          onClick={() =>
            record.mutate({
              params: { path: { id: installationId } },
              body: {
                complianceYear: year,
                kind,
                periodMonth: kind === 'MONTHLY_ESTIMATE' ? month : undefined,
                quantityTco2e: Number(quantity),
                note: note || undefined,
              },
            })
          }
          data-testid="record-emissions-submit"
        >
          {t('ets.recordEmissions')}
        </Button>
      </Stack>
    </Modal>
  );
}
