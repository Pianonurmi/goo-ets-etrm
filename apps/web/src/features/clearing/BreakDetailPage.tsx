import { Alert, Anchor, Button, Paper, Stack, Table, Text, Title } from '@mantine/core';
import { IconInbox } from '@tabler/icons-react';
import { useTranslation } from 'react-i18next';
import { Link, useParams } from 'react-router';
import { $api } from '@/shared/api/client';
import { DetailLayout } from '@/shared/components/DetailLayout';
import { LifecycleTimeline } from '@/shared/components/LifecycleTimeline';
import { PageHeader } from '@/shared/components/PageHeader';
import { QueryState } from '@/shared/components/QueryState';
import { ScrollTable } from '@/shared/components/ScrollTable';
import { CardSkeleton } from '@/shared/components/Skeletons';
import { StatusBadge } from '@/shared/components/StatusBadge';
import { formatDate, formatDateTime } from '@/shared/format/format';
import { DifferenceFigure, useDifferenceLabel } from './components';
import { accountLabel, useClearingAccounts, useClearingHouses } from './hooks';
import { breakSteps, differenceRows } from './model/clearing';

/** One break: our margin run against the member's statement of the day, contract by contract. */
export function BreakDetailPage() {
  const { t } = useTranslation();
  const { breakId = '' } = useParams();
  const differenceLabel = useDifferenceLabel();
  const detail = $api.useQuery('get', '/api/v1/clearing/breaks/{breakId}', { params: { path: { breakId } } });
  const data = detail.data;
  const accounts = useClearingAccounts(data?.legalEntityId);
  const { byId } = useClearingHouses();
  const task = $api.useQuery(
    'get',
    '/api/v1/workflow/tasks',
    { params: { query: { subjectRef: breakId, type: ['CLEARING_BREAK'], status: 'OPEN' } } },
    { enabled: data?.status === 'OPEN' },
  );
  if (!data) {
    return (
      <QueryState isPending={false} error={detail.error}>
        <Stack gap="lg">
          <CardSkeleton height={90} />
          <CardSkeleton height={240} />
        </Stack>
      </QueryState>
    );
  }
  const account = (accounts.data ?? []).find((candidate) => candidate.id === data.accountId);
  const accountText = account ? accountLabel(account, byId.get(account.clearingHouseId)?.name) : '';
  const rows = differenceRows(data);
  const openTask = task.data?.items[0];
  const title = t('clearing.breakTitle', { date: formatDate(data.businessDate) });
  const accountLink = `account=${data.accountId}`;
  return (
    <Stack gap="lg" data-testid="clearing-break-detail">
      <PageHeader
        breadcrumbs={[{ label: t('clearing.title'), to: '/clearing?tab=breaks' }, { label: title }]}
        title={title}
        badge={<StatusBadge status={data.status} data-testid="clearing-break-status" />}
        description={accountText || undefined}
        actions={
          openTask && (
            <Button
              component={Link}
              to={`/inbox/${openTask.id}`}
              leftSection={<IconInbox size={16} />}
              data-testid="clearing-break-task"
            >
              {t('clearing.breakTask')}
            </Button>
          )
        }
      />
      <DetailLayout
        summary={[
          { label: t('clearing.account'), value: accountText || '—' },
          { label: t('clearing.businessDate'), value: formatDate(data.businessDate) },
          {
            label: t('clearing.statement'),
            value: (
              <Anchor
                component={Link}
                to={`/clearing?tab=statements&${accountLink}&statement=${data.statementId}`}
                size="sm"
                data-testid="clearing-break-statement"
              >
                {t('clearing.openStatement')}
              </Anchor>
            ),
          },
          {
            label: t('clearing.run'),
            value: data.runId ? (
              <Anchor
                component={Link}
                to={`/clearing?tab=margin&${accountLink}&run=${data.runId}`}
                size="sm"
                data-testid="clearing-break-run"
              >
                {t('clearing.openRun')}
              </Anchor>
            ) : (
              t('clearing.noRun')
            ),
          },
          { label: t('clearing.openedBy'), value: `${data.openedBy} · ${formatDateTime(data.openedAt)}` },
          {
            label: t('clearing.closedBy'),
            value: data.closedBy ? `${data.closedBy} · ${formatDateTime(data.closedAt)}` : '—',
          },
        ]}
        aside={
          <LifecycleTimeline
            title={t('clearing.lifecycle.title')}
            steps={breakSteps(data, (key) => t(`clearing.lifecycle.break.${key}`))}
            testId="clearing-break-lifecycle"
          />
        }
      >
        {data.resolution && (
          <Alert color="gray" title={t('clearing.resolution')} data-testid="clearing-break-resolution">
            {data.resolution}
          </Alert>
        )}
        {data.status === 'OPEN' && (
          <Alert color="blue" data-testid="clearing-break-next">
            {openTask ? t('clearing.breakNextTask') : t('clearing.breakNext')}
          </Alert>
        )}
        <Paper withBorder radius="lg" p="lg">
          <Title order={3} size="h5">
            {t('clearing.differencesTitle')}
          </Title>
          <Text size="sm" c="dimmed" mb="sm">
            {t('clearing.differencesHint')}
          </Text>
          {rows.length === 0 ? (
            <Text size="sm" c="dimmed">
              {t('clearing.noDifferences')}
            </Text>
          ) : (
            <ScrollTable label={t('clearing.differencesTitle')} minWidth={520}>
              <Table verticalSpacing="sm" data-testid="clearing-break-differences">
                <Table.Thead>
                  <Table.Tr>
                    <Table.Th>{t('clearing.contract')}</Table.Th>
                    <Table.Th>{t('clearing.differenceKind')}</Table.Th>
                    <Table.Th ta="right">{t('clearing.ours')}</Table.Th>
                    <Table.Th ta="right">{t('clearing.theirs')}</Table.Th>
                  </Table.Tr>
                </Table.Thead>
                <Table.Tbody>
                  {rows.map((row) => (
                    <Table.Tr key={row.key} data-testid={`clearing-difference-${row.kind}`}>
                      <Table.Td>{row.contractCode ?? '—'}</Table.Td>
                      <Table.Td>
                        <Text size="sm" fw={500}>
                          {differenceLabel(row.kind)}
                        </Text>
                        <Text size="xs" c="dimmed">
                          {t(`clearing.differenceHints.${row.kind}`)}
                        </Text>
                      </Table.Td>
                      <Table.Td ta="right">
                        <DifferenceFigure kind={row.kind} value={row.ours} currency={account?.currency} />
                      </Table.Td>
                      <Table.Td ta="right" fw={600}>
                        <DifferenceFigure kind={row.kind} value={row.theirs} currency={account?.currency} />
                      </Table.Td>
                    </Table.Tr>
                  ))}
                </Table.Tbody>
              </Table>
            </ScrollTable>
          )}
        </Paper>
      </DetailLayout>
    </Stack>
  );
}
