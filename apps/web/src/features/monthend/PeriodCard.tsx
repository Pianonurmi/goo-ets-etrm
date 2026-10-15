import { Button, Card, Group, List, Stack, Text, ThemeIcon, Title } from '@mantine/core';
import { IconAlertTriangle, IconCheck, IconLock, IconLockOpen } from '@tabler/icons-react';
import { useTranslation } from 'react-i18next';
import { $api } from '@/shared/api/client';
import { Can, useCan } from '@/shared/auth/Can';
import { notifySuccess } from '@/shared/components/notify';
import { ProblemAlert } from '@/shared/components/ProblemAlert';
import { QueryState } from '@/shared/components/QueryState';
import { formatDateTime } from '@/shared/format/format';
import { useInvalidateMonthEnd } from './hooks';
import { type Period, PeriodStatusBadge } from './shared';

export function PeriodCard({ entityId, period }: { entityId: string; period: string }) {
  const { t } = useTranslation();
  const invalidate = useInvalidateMonthEnd();
  const canSeeBlockers = useCan('ledger_hardCloseBlockers');
  const periods = $api.useQuery('get', '/api/v1/ledger/periods', { params: { query: { legalEntityId: entityId } } });
  const blockers = $api.useQuery(
    'get',
    '/api/v1/ledger/periods/{period}/hard-close-blockers',
    { params: { path: { period }, query: { legalEntityId: entityId } } },
    { enabled: canSeeBlockers },
  );
  const onDone = (view: Period) => {
    notifySuccess(t('monthEnd.periodNow', { period: view.period, status: view.status.replace(/_/g, ' ') }));
    void invalidate();
  };
  const close = $api.useMutation('post', '/api/v1/ledger/periods/{period}/close', {
    meta: { notify: false },
    onSuccess: onDone,
  });
  const reopen = $api.useMutation('post', '/api/v1/ledger/periods/{period}/reopen', {
    meta: { notify: false },
    onSuccess: onDone,
  });
  const current = (periods.data ?? []).find((p) => p.period === period);
  const status = current?.status ?? 'OPEN';
  const pathParams = { path: { period }, query: { legalEntityId: entityId } };
  return (
    <Card withBorder>
      <Group justify="space-between" mb="sm">
        <Group gap="xs">
          <Title order={4}>{t('monthEnd.period', { period })}</Title>
          <PeriodStatusBadge status={status} />
        </Group>
        <Group gap="xs">
          {status === 'OPEN' && (
            <Can op="ledger_closePeriod">
              <Button
                variant="light"
                leftSection={<IconLock size={16} />}
                loading={close.isPending}
                onClick={() => close.mutate({ params: { ...pathParams, query: { ...pathParams.query, hard: false } } })}
                data-testid="soft-close"
              >
                {t('monthEnd.softClose')}
              </Button>
            </Can>
          )}
          {status !== 'CLOSED' && (
            <Can op="ledger_closePeriod">
              <Button
                color="red"
                variant="light"
                leftSection={<IconLock size={16} />}
                disabled={(blockers.data ?? []).length > 0}
                loading={close.isPending}
                onClick={() => close.mutate({ params: { ...pathParams, query: { ...pathParams.query, hard: true } } })}
              >
                {t('monthEnd.hardClose')}
              </Button>
            </Can>
          )}
          {status === 'SOFT_CLOSED' && (
            <Can op="ledger_reopenPeriod">
              <Button
                variant="default"
                leftSection={<IconLockOpen size={16} />}
                loading={reopen.isPending}
                onClick={() => reopen.mutate({ params: pathParams })}
              >
                {t('monthEnd.reopen')}
              </Button>
            </Can>
          )}
        </Group>
      </Group>
      {current?.closedBy && (
        <Text size="sm" c="dimmed" mb="sm">
          {t('monthEnd.closedBy', { who: current.closedBy, at: formatDateTime(current.closedAt) })}
        </Text>
      )}
      <ProblemAlert error={close.error ?? reopen.error} />
      {canSeeBlockers && (
        <Stack gap="xs" data-testid="hard-close-checklist">
          <Text size="sm" fw={600}>
            {t('monthEnd.checklist')}
          </Text>
          <QueryState isPending={blockers.isPending} error={blockers.error}>
            {status === 'CLOSED' ? (
              <Text size="sm" c="dimmed">
                {t('monthEnd.hardClosed')}
              </Text>
            ) : (blockers.data ?? []).length === 0 ? (
              <Group gap="xs">
                <ThemeIcon color="teal" size="sm" radius="xl">
                  <IconCheck size={12} />
                </ThemeIcon>
                <Text size="sm">{t('monthEnd.readyToClose')}</Text>
              </Group>
            ) : (
              <List
                spacing={4}
                size="sm"
                icon={
                  <ThemeIcon color="orange" size="sm" radius="xl">
                    <IconAlertTriangle size={12} />
                  </ThemeIcon>
                }
              >
                {(blockers.data ?? []).map((blocker) => (
                  <List.Item key={blocker}>{blocker}</List.Item>
                ))}
              </List>
            )}
          </QueryState>
        </Stack>
      )}
    </Card>
  );
}
