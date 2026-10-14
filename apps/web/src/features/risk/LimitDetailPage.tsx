import { Anchor, Button, Group, Paper, Progress, Stack, Table, Text, Title } from '@mantine/core';
import { IconInbox, IconPencil } from '@tabler/icons-react';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link, useParams } from 'react-router';
import { $api } from '@/shared/api/client';
import { Can } from '@/shared/auth/Can';
import { DetailLayout } from '@/shared/components/DetailLayout';
import { LifecycleTimeline } from '@/shared/components/LifecycleTimeline';
import { PageHeader } from '@/shared/components/PageHeader';
import { QueryState } from '@/shared/components/QueryState';
import { CardSkeleton } from '@/shared/components/Skeletons';
import { StatusBadge, statusLabel } from '@/shared/components/StatusBadge';
import { formatDate, formatDateTime, formatDecimal } from '@/shared/format/format';
import { limitSteps, limitUnit, scopeLabel, utilisationBar, utilisationColor } from './model/limits';
import { RequestLimitModal } from './RequestLimitModal';
import { LimitStatusBadge, MeasurementFlags, useScopeNames } from './RiskPage';

/** One limit: its amount and latest utilisation, terms, breaches, lifecycle, and the approval task or a change. */
export function LimitDetailPage() {
  const { t } = useTranslation();
  const { limitId = '' } = useParams();
  const [changing, setChanging] = useState(false);
  const limit = $api.useQuery('get', '/api/v1/risk/limits/{id}', { params: { path: { id: limitId } } });
  const entityId = limit.data?.legalEntityId ?? undefined;
  const names = useScopeNames(entityId);
  const utilisation = $api.useQuery(
    'get',
    '/api/v1/risk/limits/{id}/utilisation',
    { params: { path: { id: limitId } } },
    { retry: false, enabled: limit.data?.status === 'ACTIVE' },
  );
  const breaches = $api.useQuery(
    'get',
    '/api/v1/risk/breaches',
    { params: { query: { legalEntityId: entityId, openOnly: false } } },
    { enabled: Boolean(limit.data) },
  );
  const task = $api.useQuery(
    'get',
    '/api/v1/workflow/tasks',
    { params: { query: { subjectRef: limitId, type: ['LIMIT_CHANGE'] } } },
    { enabled: limit.data?.status === 'PENDING_APPROVAL' },
  );
  const breadcrumbs = [
    { label: t('risk.title'), to: '/risk' },
    { label: t('risk.tabs.limits'), to: '/risk' },
  ];
  const data = limit.data;
  if (!data) {
    return (
      <Stack gap={0}>
        <PageHeader breadcrumbs={breadcrumbs} title={t('risk.limit')} />
        <QueryState isPending={false} error={limit.error}>
          <CardSkeleton height={260} />
        </QueryState>
      </Stack>
    );
  }
  const own = (breaches.data ?? []).filter((breach) => breach.limitId === limitId);
  const unit = limitUnit(data);
  const current = utilisation.data;
  const approvalTask = task.data?.items[0];
  return (
    <Stack gap={0}>
      <PageHeader
        breadcrumbs={breadcrumbs}
        title={t(`risk.types.${data.type}`)}
        badge={<LimitStatusBadge status={data.status} testId="limit-status" />}
        description={scopeLabel(data.scope, names) || t('risk.wholeEntity')}
        actions={
          <>
            {data.status === 'ACTIVE' && entityId && (
              <Can op="risk_requestLimit">
                <Button
                  variant="default"
                  leftSection={<IconPencil size={16} />}
                  onClick={() => setChanging(true)}
                  data-testid="change-limit"
                >
                  {t('risk.changeLimit')}
                </Button>
              </Can>
            )}
            {approvalTask && (
              <Button component={Link} to={`/inbox/${approvalTask.id}`} leftSection={<IconInbox size={16} />}>
                {t('risk.approvalTask')}
              </Button>
            )}
          </>
        }
      />
      <DetailLayout
        summary={[
          {
            label: t('risk.amount'),
            value: (
              <Text component="span" fw={600} size="lg">
                {formatDecimal(data.amount, 0, 2)} {unit}
              </Text>
            ),
          },
          {
            label: t('risk.utilisation'),
            value: current ? (
              <Group gap="xs" component="span">
                <Text component="span" fw={600} size="lg" c={utilisationColor(current.status)}>
                  {formatDecimal(current.utilisation, 0, 1)} %
                </Text>
                <MeasurementFlags value={current} />
              </Group>
            ) : (
              '—'
            ),
          },
          { label: t('risk.warningPct'), value: `${data.warningPct} %` },
          {
            label: t('risk.validity'),
            value: `${formatDate(data.validFrom)} – ${data.validTo ? formatDate(data.validTo) : '∞'}`,
          },
        ]}
        aside={
          <LifecycleTimeline title={t('risk.lifecycle.title')} steps={limitSteps(data, t)} testId="limit-lifecycle" />
        }
      >
        {data.status === 'ACTIVE' && (
          <Paper withBorder radius="lg" p="lg">
            <Title order={3} size="h5" mb="sm">
              {t('risk.utilisation')}
            </Title>
            {current ? (
              <Stack gap="xs">
                <Progress
                  value={utilisationBar(current.utilisation)}
                  color={utilisationColor(current.status)}
                  size="lg"
                  radius="xl"
                  aria-label={t('risk.utilisation')}
                />
                <Group justify="space-between">
                  <Text size="sm" className="etrm-figure" data-testid="utilisation-value">
                    {t('risk.valueOf', {
                      value: formatDecimal(current.value, 0, 2),
                      amount: formatDecimal(data.amount, 0, 2),
                      unit,
                    })}
                  </Text>
                  <Text size="sm" c="dimmed">
                    {t('risk.measuredWhen', { at: formatDateTime(current.measuredAt) })}
                  </Text>
                </Group>
                {current.detail && (
                  <Text size="xs" c="dimmed">
                    {current.detail}
                  </Text>
                )}
              </Stack>
            ) : (
              <Text size="sm" c="dimmed">
                {t('risk.notMeasured')}
              </Text>
            )}
          </Paper>
        )}
        <Paper withBorder radius="lg" p="lg">
          <Title order={3} size="h5" mb={4}>
            {t('risk.reason')}
          </Title>
          <Text size="sm">{data.reason}</Text>
          {data.replaces && (
            <Anchor component={Link} to={`/risk/limits/${data.replaces}`} size="sm" mt="xs" display="inline-block">
              {t('risk.replacesLink')}
            </Anchor>
          )}
        </Paper>
        <Paper withBorder radius="lg" p="lg">
          <Title order={3} size="h5" mb="sm">
            {t('risk.tabs.breaches')}
          </Title>
          {own.length === 0 ? (
            <Text size="sm" c="dimmed">
              {t('risk.noBreaches')}
            </Text>
          ) : (
            <Table>
              <Table.Thead>
                <Table.Tr>
                  <Table.Th>{t('risk.openedAt')}</Table.Th>
                  <Table.Th>{t('risk.closedAt')}</Table.Th>
                  <Table.Th ta="right">{t('risk.firstValue')}</Table.Th>
                  <Table.Th ta="right">{t('risk.peakValue')}</Table.Th>
                  <Table.Th>{t('allocation.status')}</Table.Th>
                </Table.Tr>
              </Table.Thead>
              <Table.Tbody className="etrm-figure">
                {own.map((breach) => (
                  <Table.Tr key={breach.id}>
                    <Table.Td>{formatDateTime(breach.openedAt)}</Table.Td>
                    <Table.Td>{formatDateTime(breach.closedAt) || '—'}</Table.Td>
                    <Table.Td ta="right">{formatDecimal(breach.firstValue, 0, 2)}</Table.Td>
                    <Table.Td ta="right">{formatDecimal(breach.peakValue, 0, 2)}</Table.Td>
                    <Table.Td>
                      <StatusBadge
                        status={breach.status === 'OPEN' ? 'BREACH' : 'CLOSED'}
                        label={statusLabel(breach.status)}
                      />
                    </Table.Td>
                  </Table.Tr>
                ))}
              </Table.Tbody>
            </Table>
          )}
        </Paper>
      </DetailLayout>
      {changing && entityId && (
        <RequestLimitModal opened onClose={() => setChanging(false)} entityId={entityId} replaces={data} />
      )}
    </Stack>
  );
}
