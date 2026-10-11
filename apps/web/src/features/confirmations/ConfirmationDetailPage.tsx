import { Alert, Anchor, Badge, Button, Group, Paper, Stack, Table, Text, Title } from '@mantine/core';
import { IconAlertTriangle, IconFileTypePdf, IconInbox } from '@tabler/icons-react';
import { useTranslation } from 'react-i18next';
import { Link, useParams } from 'react-router';
import { $api } from '@/shared/api/client';
import { download } from '@/shared/api/download';
import { ScrollTable } from '@/shared/components/ScrollTable';
import { DetailLayout } from '@/shared/components/DetailLayout';
import { PageHeader } from '@/shared/components/PageHeader';
import { QueryState } from '@/shared/components/QueryState';
import { CardSkeleton } from '@/shared/components/Skeletons';
import { formatDate, formatDateTime } from '@/shared/format/format';
import { useCounterparties } from '@/features/trades/hooks';
import { ConfirmationStatusBadge } from './ConfirmationsPage';
import { differenceFields, headerDifferences, oursFor, termRows } from './model/confirmations';

/** One confirmation: its terms against ours field by field, the matcher's verdict, and the break task. */
export function ConfirmationDetailPage() {
  const { t } = useTranslation();
  const { confirmationId = '' } = useParams();
  const counterparties = useCounterparties();
  const confirmation = $api.useQuery('get', '/api/v1/confirmations/{id}', {
    params: { path: { id: confirmationId } },
  });
  const data = confirmation.data;
  const all = $api.useQuery(
    'get',
    '/api/v1/trades/{tradeId}/confirmations',
    { params: { path: { tradeId: data?.tradeId ?? '' } } },
    { enabled: Boolean(data) },
  );
  const task = $api.useQuery(
    'get',
    '/api/v1/workflow/tasks',
    { params: { query: { subjectRef: confirmationId, type: ['CONFIRMATION_BREAK'], status: 'OPEN' } } },
    { enabled: data?.status === 'MISMATCHED' },
  );
  const theirs = data?.direction === 'THEIRS';
  const ours = data && theirs ? oursFor(data, all.data ?? []) : data;
  const rows = data ? termRows(theirs ? ours?.terms : undefined, data.terms, data.differences) : [];
  const breakTask = task.data?.items[0];
  if (!data) {
    return (
      <QueryState isPending={false} error={confirmation.error}>
        <Stack gap="lg">
          <CardSkeleton height={90} />
          <CardSkeleton height={320} />
        </Stack>
      </QueryState>
    );
  }
  const counterparty = data.counterpartyId ? (counterparties.byId.get(data.counterpartyId)?.name ?? '') : '';
  const title = data.reference ?? data.tradeNumber;
  const differing = differenceFields(data.differences).length;
  return (
    <Stack gap="lg">
      <PageHeader
        breadcrumbs={[{ label: t('confirmations.title'), to: '/confirmations' }, { label: title }]}
        title={title}
        badge={
          <Group gap={6} wrap="nowrap">
            <ConfirmationStatusBadge status={data.status} testId="confirmation-status" />
            <Badge variant="default" radius="xl" tt="none">
              {t(`confirmations.directions.${data.direction}`)}
            </Badge>
          </Group>
        }
        description={
          <>
            <Anchor component={Link} to={`/trades/${data.tradeId}`} size="sm" fw={600} data-testid="confirmation-trade">
              {data.tradeNumber}
            </Anchor>
            {` · ${t('confirmations.tradeVersion', { version: data.tradeVersion })}${counterparty ? ` · ${counterparty}` : ''}`}
          </>
        }
        actions={
          <>
            {ours?.documentId && (
              <Button
                variant="default"
                leftSection={<IconFileTypePdf size={16} />}
                onClick={() => void download(`/api/v1/documents/${ours.documentId}/content`, `${data.tradeNumber}.pdf`)}
              >
                {t('confirmations.ourPdf')}
              </Button>
            )}
            {breakTask && (
              <Button
                component={Link}
                to={`/inbox/${breakTask.id}`}
                leftSection={<IconInbox size={16} />}
                data-testid="confirmation-task"
              >
                {t('confirmations.breakTask')}
              </Button>
            )}
          </>
        }
      />
      <DetailLayout
        summary={[
          {
            label: t('confirmations.differences'),
            value: (
              <Text span size="sm" fw={600} c={differing > 0 ? 'red' : 'teal'}>
                {differing > 0 ? t('confirmations.fieldsDiffer', { count: differing }) : t('confirmations.allMatch')}
              </Text>
            ),
          },
          { label: t('confirmations.tradeDate'), value: formatDate(data.terms.tradeDate) },
          {
            label: t('confirmations.source'),
            value: t(`trades.sources.${data.source}`, { defaultValue: data.source }),
          },
          { label: t('confirmations.receivedBy'), value: `${data.createdBy} · ${formatDateTime(data.createdAt)}` },
          {
            label: t('confirmations.decidedBy'),
            value: data.decidedBy ? `${data.decidedBy}${data.note ? `: ${data.note}` : ''}` : '—',
          },
        ]}
      >
        {data.status === 'MISMATCHED' && (
          <Alert color="red" icon={<IconAlertTriangle size={18} />} data-testid="confirmation-mismatch">
            {t('confirmations.mismatchHint')}
          </Alert>
        )}
        {headerDifferences(data.differences).length > 0 && (
          <Alert color="orange">
            {headerDifferences(data.differences)
              .map((difference) => `${difference.field}: ${difference.ours ?? '—'} / ${difference.theirs ?? '—'}`)
              .join(' · ')}
          </Alert>
        )}
        <Paper withBorder radius="lg" p="lg">
          <Title order={3} size="h5" mb={4}>
            {theirs ? t('confirmations.comparison') : t('confirmations.terms')}
          </Title>
          <Text size="sm" c="dimmed" mb="md">
            {theirs ? t('confirmations.comparisonHint') : t('confirmations.termsHint')}
          </Text>
          <ScrollTable label={theirs ? t('confirmations.comparison') : t('confirmations.terms')} minWidth={640}>
            <Table verticalSpacing="sm" data-testid="confirmation-terms">
              <Table.Thead>
                <Table.Tr>
                  <Table.Th w="22%">{t('confirmations.field')}</Table.Th>
                  {theirs && <Table.Th>{t('confirmations.ours')}</Table.Th>}
                  <Table.Th>{theirs ? t('confirmations.theirs') : t('confirmations.value')}</Table.Th>
                  {theirs && <Table.Th w={120} />}
                </Table.Tr>
              </Table.Thead>
              <Table.Tbody>
                {rows.map((row) => (
                  <Table.Tr
                    key={row.key}
                    data-testid={`term-${row.key}`}
                    bg={row.severity === 'MISMATCH' ? 'var(--mantine-color-red-light)' : undefined}
                  >
                    <Table.Td>
                      <Text size="sm" c="dimmed">
                        {t(`confirmations.fields.${row.key}`, { defaultValue: row.key })}
                      </Text>
                    </Table.Td>
                    {theirs && (
                      <Table.Td>
                        <Text size="sm" className="etrm-figure">
                          {row.ours}
                        </Text>
                      </Table.Td>
                    )}
                    <Table.Td>
                      <Text
                        size="sm"
                        className="etrm-figure"
                        c={row.severity === 'MISMATCH' ? 'red' : undefined}
                        fw={row.severity ? 600 : undefined}
                      >
                        {row.theirs}
                      </Text>
                    </Table.Td>
                    {theirs && (
                      <Table.Td ta="right">
                        {row.severity && (
                          <Badge color={row.severity === 'MISMATCH' ? 'red' : 'gray'} variant="light" radius="xl">
                            {row.severity}
                          </Badge>
                        )}
                      </Table.Td>
                    )}
                  </Table.Tr>
                ))}
              </Table.Tbody>
            </Table>
          </ScrollTable>
        </Paper>
      </DetailLayout>
    </Stack>
  );
}
