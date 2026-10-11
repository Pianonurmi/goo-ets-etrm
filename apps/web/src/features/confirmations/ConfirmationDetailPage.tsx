import { Alert, Anchor, Badge, Button, Card, Group, SimpleGrid, Stack, Table, Text, Title } from '@mantine/core';
import { IconArrowLeft, IconFileTypePdf, IconInbox } from '@tabler/icons-react';
import type { ReactNode } from 'react';
import { useTranslation } from 'react-i18next';
import { Link, useParams } from 'react-router';
import { $api } from '@/shared/api/client';
import { download } from '@/shared/api/download';
import { QueryState } from '@/shared/components/QueryState';
import { formatDate, formatDateTime } from '@/shared/format/format';
import { useCounterparties } from '@/features/trades/hooks';
import { ConfirmationStatusBadge } from './ConfirmationsPage';
import { headerDifferences, oursFor, termRows } from './model/confirmations';

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
  return (
    <Stack>
      <Anchor component={Link} to="/confirmations" size="sm">
        <Group gap={4}>
          <IconArrowLeft size={14} />
          {t('confirmations.back')}
        </Group>
      </Anchor>
      <QueryState isPending={confirmation.isPending} error={confirmation.error}>
        {data && (
          <>
            <Card withBorder>
              <Group justify="space-between" align="flex-start">
                <Stack gap={4}>
                  <Group gap="xs">
                    <Title order={3}>{data.reference ?? data.tradeNumber}</Title>
                    <ConfirmationStatusBadge status={data.status} testId="confirmation-status" />
                    <Badge variant="outline" color="gray">
                      {t(`confirmations.directions.${data.direction}`)}
                    </Badge>
                  </Group>
                  <Text size="sm" c="dimmed">
                    <Anchor
                      component={Link}
                      to={`/trades/${data.tradeId}`}
                      size="sm"
                      underline="always"
                      data-testid="confirmation-trade"
                    >
                      {data.tradeNumber}
                    </Anchor>{' '}
                    · v{data.tradeVersion} ·{' '}
                    {data.counterpartyId ? (counterparties.byId.get(data.counterpartyId)?.name ?? '') : ''}
                  </Text>
                </Stack>
                <Group gap="xs">
                  {task.data?.items[0] && (
                    <Button
                      component={Link}
                      to={`/inbox/${task.data.items[0].id}`}
                      variant="light"
                      leftSection={<IconInbox size={16} />}
                      data-testid="confirmation-task"
                    >
                      {t('confirmations.breakTask')}
                    </Button>
                  )}
                  {ours?.documentId && (
                    <Button
                      variant="light"
                      leftSection={<IconFileTypePdf size={16} />}
                      onClick={() =>
                        void download(`/api/v1/documents/${ours.documentId}/content`, `${data.tradeNumber}.pdf`)
                      }
                    >
                      {t('confirmations.ourPdf')}
                    </Button>
                  )}
                </Group>
              </Group>
              {data.status === 'MISMATCHED' && (
                <Alert color="red" mt="md" data-testid="confirmation-mismatch">
                  {t('confirmations.mismatchHint')}
                </Alert>
              )}
              <SimpleGrid cols={{ base: 2, md: 4 }} mt="md">
                <Field label={t('confirmations.tradeDate')}>{formatDate(data.terms.tradeDate)}</Field>
                <Field label={t('confirmations.source')}>{data.source}</Field>
                <Field label={t('confirmations.receivedBy')}>
                  {data.createdBy} · {formatDateTime(data.createdAt)}
                </Field>
                <Field label={t('confirmations.decidedBy')}>
                  {data.decidedBy ? `${data.decidedBy}${data.note ? `: ${data.note}` : ''}` : '—'}
                </Field>
              </SimpleGrid>
            </Card>
            {headerDifferences(data.differences).length > 0 && (
              <Alert color="orange">
                {headerDifferences(data.differences)
                  .map((difference) => `${difference.field}: ${difference.ours ?? '—'} / ${difference.theirs ?? '—'}`)
                  .join(' · ')}
              </Alert>
            )}
            <Card withBorder>
              <Title order={5} mb="sm">
                {theirs ? t('confirmations.comparison') : t('confirmations.terms')}
              </Title>
              <Table striped data-testid="confirmation-terms">
                <Table.Thead>
                  <Table.Tr>
                    <Table.Th>{t('confirmations.field')}</Table.Th>
                    {theirs && <Table.Th>{t('confirmations.ours')}</Table.Th>}
                    <Table.Th>{theirs ? t('confirmations.theirs') : t('confirmations.value')}</Table.Th>
                    {theirs && <Table.Th />}
                  </Table.Tr>
                </Table.Thead>
                <Table.Tbody>
                  {rows.map((row) => (
                    <Table.Tr key={row.key} data-testid={`term-${row.key}`}>
                      <Table.Td>{t(`confirmations.fields.${row.key}`, { defaultValue: row.key })}</Table.Td>
                      {theirs && <Table.Td>{row.ours}</Table.Td>}
                      <Table.Td>
                        <Text
                          size="sm"
                          c={row.severity === 'MISMATCH' ? 'red' : undefined}
                          fw={row.severity ? 600 : undefined}
                        >
                          {row.theirs}
                        </Text>
                      </Table.Td>
                      {theirs && (
                        <Table.Td>
                          {row.severity && (
                            <Badge color={row.severity === 'MISMATCH' ? 'red' : 'gray'} variant="light">
                              {row.severity}
                            </Badge>
                          )}
                        </Table.Td>
                      )}
                    </Table.Tr>
                  ))}
                </Table.Tbody>
              </Table>
            </Card>
          </>
        )}
      </QueryState>
    </Stack>
  );
}
