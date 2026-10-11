import { Button, Card, Group, Stack, Text, TextInput, Title } from '@mantine/core';
import { IconArrowRight } from '@tabler/icons-react';
import dayjs from 'dayjs';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link } from 'react-router';
import { $api } from '@/shared/api/client';
import { asProblemError } from '@/shared/api/problem';
import { FactGrid } from '@/shared/components/DetailLayout';
import { EmptyState } from '@/shared/components/EmptyState';
import { Money } from '@/shared/components/Figures';
import { QueryState } from '@/shared/components/QueryState';
import { StatusBadge } from '@/shared/components/StatusBadge';
import { formatDate, formatDateTime } from '@/shared/format/format';

/** The official run that applies on a date: the one of that date or the latest before it (what accounting uses). */
export function OfficialRun({ entityId }: { entityId: string }) {
  const { t } = useTranslation();
  const [date, setDate] = useState(dayjs().format('YYYY-MM-DD'));
  const official = $api.useQuery(
    'get',
    '/api/v1/valuation/official',
    { params: { query: { legalEntityId: entityId, date } } },
    { enabled: Boolean(date), retry: false },
  );
  const missing = Boolean(official.error) && asProblemError(official.error).status === 404;
  const run = official.data;
  return (
    <Stack>
      <Group align="flex-end">
        <TextInput
          type="date"
          label={t('valuation.officialAsOf')}
          description={t('valuation.officialHint')}
          value={date}
          onChange={(e) => setDate(e.currentTarget.value)}
          data-testid="official-date"
        />
      </Group>
      {missing ? (
        <EmptyState
          title={t('valuation.noOfficial')}
          description={t('valuation.noOfficialHint')}
          testId="official-none"
        />
      ) : (
        <QueryState isPending={official.isPending} error={official.error}>
          {run && (
            <Card withBorder data-testid="official-run">
              <Group justify="space-between" mb="md">
                <Group gap="xs">
                  <Title order={4} data-testid="official-run-title">
                    {t('valuation.officialTitle', { kind: run.kind, date: formatDate(run.valuationDate) })}
                  </Title>
                  <StatusBadge status={run.status} data-testid="official-run-status" />
                </Group>
                <Button
                  component={Link}
                  to={`/valuation/runs/${run.id}`}
                  variant="light"
                  rightSection={<IconArrowRight size={16} />}
                  data-testid="official-run-open"
                >
                  {t('valuation.openRun')}
                </Button>
              </Group>
              <FactGrid
                facts={[
                  {
                    label: t('valuation.date'),
                    value: formatDate(run.valuationDate),
                    testId: 'official-run-date',
                  },
                  {
                    label: t('valuation.totalMtm'),
                    value: <Money value={run.totalMtmBase} currency={run.baseCurrency} signed />,
                  },
                  { label: t('valuation.exceptions'), value: run.exceptions },
                  {
                    label: t('curves.signedOffBy'),
                    value: run.signedOffBy ? `${run.signedOffBy} · ${formatDateTime(run.signedOffAt)}` : '—',
                  },
                ]}
              />
              {run.signOffNote && (
                <Text size="sm" c="dimmed" mt="sm">
                  {run.signOffNote}
                </Text>
              )}
            </Card>
          )}
        </QueryState>
      )}
    </Stack>
  );
}
