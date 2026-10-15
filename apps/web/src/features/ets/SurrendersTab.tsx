import { Alert, Button, Group, Modal, NumberInput, Select, Stack, Table, Text } from '@mantine/core';
import { IconSend } from '@tabler/icons-react';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { $api } from '@/shared/api/client';
import { Can } from '@/shared/auth/Can';
import { notifySuccess } from '@/shared/components/notify';
import { ProblemAlert } from '@/shared/components/ProblemAlert';
import { QueryState } from '@/shared/components/QueryState';
import { StatusBadge } from '@/shared/components/StatusBadge';
import { formatDateTime } from '@/shared/format/format';
import { tonnes, useInvalidateEts } from './EtsPage';
import { type Installation } from './shared';

export function SurrendersTab({ installation, year }: { installation: Installation; year: number }) {
  const { t } = useTranslation();
  const invalidate = useInvalidateEts();
  const [requesting, setRequesting] = useState(false);
  const [quantity, setQuantity] = useState<number | ''>('');
  const [product, setProduct] = useState<string | null>(installation.eligibleProducts[0] ?? null);
  const surrenders = $api.useQuery('get', '/api/v1/ets/installations/{id}/surrenders', {
    params: { path: { id: installation.id } },
  });
  const request = $api.useMutation('post', '/api/v1/ets/installations/{id}/surrenders', {
    meta: { notify: false },
    onSuccess: () => {
      notifySuccess(t('ets.surrenderRequested'));
      void invalidate();
      setRequesting(false);
    },
  });
  return (
    <Stack>
      <Group justify="flex-end">
        <Can op="etsCompliance_requestSurrender">
          <Button variant="default" leftSection={<IconSend size={16} />} onClick={() => setRequesting(true)}>
            {t('ets.requestSurrender')}
          </Button>
        </Can>
      </Group>
      <QueryState isPending={surrenders.isPending} error={surrenders.error}>
        {(surrenders.data ?? []).length === 0 ? (
          <Alert color="gray">{t('ets.noSurrenders')}</Alert>
        ) : (
          <Table withTableBorder striped>
            <Table.Thead>
              <Table.Tr>
                <Table.Th>{t('positions.complianceYear')}</Table.Th>
                <Table.Th>{t('trades.form.allowanceProduct')}</Table.Th>
                <Table.Th ta="right">{t('ets.quantity')}</Table.Th>
                <Table.Th>{t('allocation.status')}</Table.Th>
                <Table.Th>{t('risk.requestedBy')}</Table.Th>
                <Table.Th>{t('ets.requestedAt')}</Table.Th>
              </Table.Tr>
            </Table.Thead>
            <Table.Tbody>
              {(surrenders.data ?? []).map((row) => (
                <Table.Tr key={row.id}>
                  <Table.Td>{row.complianceYear}</Table.Td>
                  <Table.Td>{row.productCode}</Table.Td>
                  <Table.Td ta="right">{tonnes(row.quantity)}</Table.Td>
                  <Table.Td>
                    <StatusBadge status={row.status} />
                  </Table.Td>
                  <Table.Td>{row.requestedBy}</Table.Td>
                  <Table.Td>{formatDateTime(row.requestedAt)}</Table.Td>
                </Table.Tr>
              ))}
            </Table.Tbody>
          </Table>
        )}
      </QueryState>
      <Modal opened={requesting} onClose={() => setRequesting(false)} title={t('ets.requestSurrender')}>
        <Stack>
          <ProblemAlert error={request.error} />
          <Text size="sm" c="dimmed">
            {t('ets.surrenderHint', { year })}
          </Text>
          <Group grow>
            <Select
              label={t('trades.form.allowanceProduct')}
              data={installation.eligibleProducts}
              value={product}
              onChange={setProduct}
            />
            <NumberInput
              label={t('ets.quantity')}
              placeholder={t('ets.allOutstanding')}
              value={quantity}
              onChange={(v) => setQuantity(v === '' ? '' : Number(v))}
              min={1}
              allowDecimal={false}
            />
          </Group>
          <Button
            loading={request.isPending}
            onClick={() =>
              request.mutate({
                params: { path: { id: installation.id } },
                body: {
                  complianceYear: year,
                  productCode: product ?? undefined,
                  quantity: quantity === '' ? undefined : quantity,
                },
              })
            }
          >
            {t('ets.requestSurrender')}
          </Button>
        </Stack>
      </Modal>
    </Stack>
  );
}
