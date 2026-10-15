import { Alert, Button, Group, Modal, NumberInput, Select, SimpleGrid, Stack, Text, TextInput } from '@mantine/core';
import { IconPencil } from '@tabler/icons-react';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { $api } from '@/shared/api/client';
import type { Schema } from '@/shared/api/types';
import { Can } from '@/shared/auth/Can';
import { notifySuccess } from '@/shared/components/notify';
import { ProblemAlert } from '@/shared/components/ProblemAlert';
import { Figure, tonnes, useInvalidateEts } from './EtsPage';

export type FreeAllocationStatus = NonNullable<Schema<'SetFreeAllocation'>['status']>;

export function FreeAllocationTab({ installationId, year }: { installationId: string; year: number }) {
  const { t } = useTranslation();
  const invalidate = useInvalidateEts();
  const [editing, setEditing] = useState(false);
  const allocation = $api.useQuery(
    'get',
    '/api/v1/ets/installations/{id}/free-allocation/{year}',
    { params: { path: { id: installationId, year } } },
    { retry: false },
  );
  const [allocated, setAllocated] = useState<number | ''>('');
  const [adjusted, setAdjusted] = useState<number | ''>('');
  const [status, setStatus] = useState<FreeAllocationStatus>('EXPECTED');
  const [note, setNote] = useState('');
  const save = $api.useMutation('put', '/api/v1/ets/installations/{id}/free-allocation/{year}', {
    meta: { notify: false },
    onSuccess: () => {
      notifySuccess(t('ets.allocationSaved'));
      void invalidate();
      setEditing(false);
    },
  });
  const open = () => {
    setAllocated(allocation.data?.allocated ?? '');
    setAdjusted(allocation.data?.adjusted ?? '');
    setStatus(allocation.data?.status ?? 'EXPECTED');
    setNote(allocation.data?.note ?? '');
    setEditing(true);
  };
  return (
    <Stack>
      <Group justify="flex-end">
        <Can op="etsCompliance_setFreeAllocation">
          <Button variant="default" leftSection={<IconPencil size={16} />} onClick={open}>
            {t('ets.setAllocation')}
          </Button>
        </Can>
      </Group>
      {allocation.isPending ? null : !allocation.data ? (
        <Alert color="gray">{t('ets.noAllocation', { year })}</Alert>
      ) : (
        <SimpleGrid cols={{ base: 2, md: 5 }}>
          <Figure label={t('ets.allocated')} value={tonnes(allocation.data.allocated)} />
          <Figure label={t('ets.adjusted')} value={tonnes(allocation.data.adjusted)} />
          <Figure label={t('ets.entitlement')} value={tonnes(allocation.data.entitlement)} />
          <Figure label={t('ets.received')} value={tonnes(allocation.data.received)} />
          <Figure label={t('ets.outstanding')} value={tonnes(allocation.data.outstanding)} />
        </SimpleGrid>
      )}
      {allocation.data?.note && (
        <Text size="sm" c="dimmed">
          {allocation.data.note}
        </Text>
      )}
      <Modal opened={editing} onClose={() => setEditing(false)} title={t('ets.setAllocation')}>
        <Stack>
          <ProblemAlert error={save.error} />
          <Group grow>
            <NumberInput
              label={t('ets.allocated')}
              value={allocated}
              onChange={(v) => setAllocated(v === '' ? '' : Number(v))}
              min={0}
              allowDecimal={false}
            />
            <NumberInput
              label={t('ets.adjusted')}
              description={t('ets.adjustedHint')}
              value={adjusted}
              onChange={(v) => setAdjusted(v === '' ? '' : Number(v))}
              min={0}
              allowDecimal={false}
            />
          </Group>
          <Select
            label={t('allocation.status')}
            data={(['EXPECTED', 'ISSUED', 'WITHHELD'] as const).map((value) => ({
              value,
              label: t(`ets.allocationStatus.${value}`),
            }))}
            value={status}
            allowDeselect={false}
            onChange={(value) => value && setStatus(value)}
          />
          <TextInput label={t('marketData.note')} value={note} onChange={(e) => setNote(e.currentTarget.value)} />
          <Button
            disabled={allocated === ''}
            loading={save.isPending}
            onClick={() =>
              save.mutate({
                params: { path: { id: installationId, year } },
                body: {
                  allocated: Number(allocated),
                  adjusted: adjusted === '' ? undefined : adjusted,
                  status,
                  note: note || undefined,
                },
              })
            }
          >
            {t('ets.save')}
          </Button>
        </Stack>
      </Modal>
    </Stack>
  );
}
