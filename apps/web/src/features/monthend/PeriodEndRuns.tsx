import { Alert, Button, Card, Group, Stack, Table, Text, Title } from '@mantine/core';
import { IconPlayerPlay } from '@tabler/icons-react';
import { useTranslation } from 'react-i18next';
import { $api } from '@/shared/api/client';
import { Can } from '@/shared/auth/Can';
import { notifySuccess } from '@/shared/components/notify';
import { ProblemAlert } from '@/shared/components/ProblemAlert';
import { QueryState } from '@/shared/components/QueryState';
import { StatusBadge } from '@/shared/components/StatusBadge';
import { formatDateTime } from '@/shared/format/format';
import { useInvalidateMonthEnd } from './hooks';
import { amount } from './shared';

export function PeriodEndRuns({ entityId, period }: { entityId: string; period: string }) {
  const { t } = useTranslation();
  const invalidate = useInvalidateMonthEnd();
  const runs = $api.useQuery('get', '/api/v1/ledger/period-end/runs', {
    params: { query: { legalEntityId: entityId, period } },
  });
  const run = $api.useMutation('post', '/api/v1/ledger/period-end/runs', {
    meta: { notify: false },
    onSuccess: (result) => {
      notifySuccess(t('monthEnd.runDone', { seq: result.seq }));
      void invalidate();
    },
  });
  const latest = [...(runs.data ?? [])].sort((a, b) => b.seq - a.seq)[0];
  return (
    <Card withBorder>
      <Group justify="space-between" mb="sm">
        <Title order={5}>{t('monthEnd.periodEnd')}</Title>
        <Can op="periodEnd_run">
          <Button
            variant="light"
            leftSection={<IconPlayerPlay size={16} />}
            loading={run.isPending}
            onClick={() => run.mutate({ body: { legalEntityId: entityId, period } })}
          >
            {t('monthEnd.runPeriodEnd')}
          </Button>
        </Can>
      </Group>
      <ProblemAlert error={run.error} />
      <QueryState isPending={runs.isPending} error={runs.error}>
        {!latest ? (
          <Text size="sm" c="dimmed">
            {t('monthEnd.noRuns')}
          </Text>
        ) : (
          <Stack gap="xs" data-testid="period-end-run">
            <Text size="sm" c="dimmed">
              {t('monthEnd.runBy', {
                seq: latest.seq,
                who: latest.runBy,
                at: formatDateTime(latest.runAt),
                count: (runs.data ?? []).length,
              })}
            </Text>
            {latest.warnings.map((warning) => (
              <Alert key={warning} color="orange" py={6}>
                {warning}
              </Alert>
            ))}
            <Table withTableBorder verticalSpacing="sm">
              <Table.Thead>
                <Table.Tr>
                  <Table.Th>{t('monthEnd.component')}</Table.Th>
                  <Table.Th>{t('monthEnd.key')}</Table.Th>
                  <Table.Th ta="right">{t('monthEnd.required')}</Table.Th>
                  <Table.Th ta="right">{t('monthEnd.booked')}</Table.Th>
                  <Table.Th ta="right">{t('monthEnd.posted')}</Table.Th>
                </Table.Tr>
              </Table.Thead>
              <Table.Tbody className="etrm-figure">
                {latest.components.map((component) => (
                  <Table.Tr key={`${component.kind}-${component.key}`}>
                    <Table.Td>
                      {t(`monthEnd.kinds.${component.kind}`)}
                      {component.disclosedOnly && (
                        <StatusBadge ml="xs" size="xs" status="DISCLOSED" label={t('monthEnd.disclosedOnly')} />
                      )}
                    </Table.Td>
                    <Table.Td>{component.key}</Table.Td>
                    <Table.Td ta="right">{amount(component.required)}</Table.Td>
                    <Table.Td ta="right">{amount(component.booked)}</Table.Td>
                    <Table.Td ta="right">{amount(component.posted)}</Table.Td>
                  </Table.Tr>
                ))}
              </Table.Tbody>
            </Table>
          </Stack>
        )}
      </QueryState>
    </Card>
  );
}
