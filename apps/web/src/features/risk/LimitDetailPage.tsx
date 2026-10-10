import { Anchor, Badge, Button, Card, Group, SimpleGrid, Stack, Table, Text, Title } from '@mantine/core';
import { IconArrowLeft, IconInbox, IconPencil } from '@tabler/icons-react';
import { useState, type ReactNode } from 'react';
import { useTranslation } from 'react-i18next';
import { Link, useParams } from 'react-router';
import { $api } from '@/shared/api/client';
import { Can } from '@/shared/auth/Can';
import { QueryState } from '@/shared/components/QueryState';
import { formatDate, formatDateTime, formatDecimal } from '@/shared/format/format';
import { limitUnit, scopeLabel } from './model/limits';
import { RequestLimitModal } from './RequestLimitModal';
import { LimitStatusBadge, UtilisationCell, useScopeNames } from './RiskPage';

function Field({ label, children }: { label: string; children: ReactNode }) {
  return (
    <Stack gap={0}>
      <Text size="xs" c="dimmed">
        {label}
      </Text>
      <Text size="sm" component="div">
        {children}
      </Text>
    </Stack>
  );
}

/** One limit: terms, latest utilisation, breaches, and the approval task or a change request. */
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
  const own = (breaches.data ?? []).filter((breach) => breach.limitId === limitId);
  const unit = limit.data ? limitUnit(limit.data) : '';
  return (
    <Stack>
      <Anchor component={Link} to="/risk" size="sm">
        <Group gap={4}>
          <IconArrowLeft size={14} />
          {t('risk.back')}
        </Group>
      </Anchor>
      <QueryState isPending={limit.isPending} error={limit.error}>
        {limit.data && (
          <Card withBorder>
            <Group justify="space-between">
              <Group gap="xs">
                <Title order={3}>{t(`risk.types.${limit.data.type}`)}</Title>
                <LimitStatusBadge status={limit.data.status} testId="limit-status" />
              </Group>
              <Group>
                {task.data?.items[0] && (
                  <Button
                    component={Link}
                    to={`/inbox/${task.data.items[0].id}`}
                    variant="light"
                    leftSection={<IconInbox size={16} />}
                  >
                    {t('risk.approvalTask')}
                  </Button>
                )}
                {limit.data.status === 'ACTIVE' && entityId && (
                  <Can op="risk_requestLimit">
                    <Button
                      variant="light"
                      leftSection={<IconPencil size={16} />}
                      onClick={() => setChanging(true)}
                      data-testid="change-limit"
                    >
                      {t('risk.changeLimit')}
                    </Button>
                  </Can>
                )}
              </Group>
            </Group>
            <SimpleGrid cols={{ base: 2, md: 4 }} mt="md">
              <Field label={t('risk.scope')}>{scopeLabel(limit.data.scope, names) || t('risk.wholeEntity')}</Field>
              <Field label={t('risk.amount')}>
                {formatDecimal(limit.data.amount, 0, 2)} {unit}
              </Field>
              <Field label={t('risk.warningPct')}>{limit.data.warningPct} %</Field>
              <Field label={t('risk.validity')}>
                {formatDate(limit.data.validFrom)} – {limit.data.validTo ? formatDate(limit.data.validTo) : '∞'}
              </Field>
              <Field label={t('risk.reason')}>{limit.data.reason}</Field>
              <Field label={t('risk.requestedBy')}>
                {limit.data.requestedBy} · {formatDateTime(limit.data.requestedAt)}
              </Field>
              <Field label={t('curves.signedOffBy')}>
                {limit.data.approvedBy
                  ? `${limit.data.approvedBy} · ${formatDateTime(limit.data.approvedAt)}`
                  : t('curves.notSignedOff')}
              </Field>
              {limit.data.replaces && (
                <Field label={t('risk.replaces')}>
                  <Anchor component={Link} to={`/risk/limits/${limit.data.replaces}`} size="sm">
                    {limit.data.replaces.slice(0, 8)}
                  </Anchor>
                </Field>
              )}
            </SimpleGrid>
          </Card>
        )}
      </QueryState>
      {limit.data?.status === 'ACTIVE' && (
        <Card withBorder>
          <Title order={5} mb="sm">
            {t('risk.utilisation')}
          </Title>
          <Group gap="xl">
            <UtilisationCell value={utilisation.data} />
            {utilisation.data && (
              <>
                <Text size="sm" data-testid="utilisation-value">
                  {t('risk.valueOf', {
                    value: formatDecimal(utilisation.data.value, 0, 2),
                    amount: formatDecimal(limit.data.amount, 0, 2),
                    unit,
                  })}
                </Text>
                <Text size="sm" c="dimmed">
                  {t('risk.measuredWhen', { at: formatDateTime(utilisation.data.measuredAt) })}
                </Text>
              </>
            )}
          </Group>
          {utilisation.data?.detail && (
            <Text size="xs" c="dimmed" mt="xs">
              {utilisation.data.detail}
            </Text>
          )}
        </Card>
      )}
      <Card withBorder>
        <Title order={5} mb="sm">
          {t('risk.tabs.breaches')}
        </Title>
        {own.length === 0 ? (
          <Text size="sm" c="dimmed">
            {t('risk.noBreaches')}
          </Text>
        ) : (
          <Table striped>
            <Table.Thead>
              <Table.Tr>
                <Table.Th>{t('risk.openedAt')}</Table.Th>
                <Table.Th>{t('risk.closedAt')}</Table.Th>
                <Table.Th ta="right">{t('risk.firstValue')}</Table.Th>
                <Table.Th ta="right">{t('risk.peakValue')}</Table.Th>
                <Table.Th>{t('allocation.status')}</Table.Th>
              </Table.Tr>
            </Table.Thead>
            <Table.Tbody>
              {own.map((breach) => (
                <Table.Tr key={breach.id}>
                  <Table.Td>{formatDateTime(breach.openedAt)}</Table.Td>
                  <Table.Td>{formatDateTime(breach.closedAt)}</Table.Td>
                  <Table.Td ta="right">{formatDecimal(breach.firstValue, 0, 2)}</Table.Td>
                  <Table.Td ta="right">{formatDecimal(breach.peakValue, 0, 2)}</Table.Td>
                  <Table.Td>
                    <Badge color={breach.status === 'OPEN' ? 'red' : 'gray'} variant="light">
                      {breach.status}
                    </Badge>
                  </Table.Td>
                </Table.Tr>
              ))}
            </Table.Tbody>
          </Table>
        )}
      </Card>
      {changing && limit.data && entityId && (
        <RequestLimitModal opened onClose={() => setChanging(false)} entityId={entityId} replaces={limit.data} />
      )}
    </Stack>
  );
}
