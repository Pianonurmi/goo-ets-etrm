import { LineChart } from '@mantine/charts';
import { Button, Group, Paper, SegmentedControl, Stack, Table, TextInput, Title } from '@mantine/core';
import { IconCamera } from '@tabler/icons-react';
import { useQueryClient } from '@tanstack/react-query';
import dayjs from 'dayjs';
import { useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { $api } from '@/shared/api/client';
import { Can } from '@/shared/auth/Can';
import { EmptyState } from '@/shared/components/EmptyState';
import { notifySuccess } from '@/shared/components/notify';
import { QueryState } from '@/shared/components/QueryState';
import { formatDate, formatDateTime, formatDecimal } from '@/shared/format/format';
import { snapshotFigures, snapshotSeries, type SnapshotKind } from './model/snapshots';

/** End-of-day position snapshots of the entity over a period, charted and listed; one can be taken now. */
export function SnapshotsTab({ entityId }: { entityId: string }) {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const [kind, setKind] = useState<SnapshotKind>('GOO');
  const [from, setFrom] = useState(dayjs().subtract(12, 'month').format('YYYY-MM-DD'));
  const [to, setTo] = useState(dayjs().format('YYYY-MM-DD'));
  const snapshots = $api.useQuery(
    'get',
    '/api/v1/positions/snapshots',
    { params: { query: { legalEntityId: entityId, kind, from, to } } },
    { enabled: Boolean(from && to) },
  );
  const take = $api.useMutation('post', '/api/v1/positions/snapshots', {
    onSuccess: (taken) => {
      notifySuccess(t('positions.snapshotsTaken', { date: formatDate(taken[0]?.asOfDate) }));
      void queryClient.invalidateQueries({
        predicate: (q) => typeof q.queryKey[1] === 'string' && q.queryKey[1].startsWith('/api/v1/positions/snapshots'),
      });
    },
  });
  const rows = useMemo(
    () => [...(snapshots.data ?? [])].sort((a, b) => b.asOfDate.localeCompare(a.asOfDate)),
    [snapshots.data],
  );
  const series = useMemo(() => snapshotSeries(snapshots.data ?? []), [snapshots.data]);
  const headings = snapshotFigures({ kind, data: {} });
  return (
    <Stack>
      <Group align="flex-end" justify="space-between">
        <Group align="flex-end">
          <SegmentedControl
            value={kind}
            onChange={(value) => setKind(value)}
            data={[
              { value: 'GOO', label: t('positions.goo') },
              { value: 'ETS', label: t('positions.ets') },
            ]}
            data-testid="snapshot-kind"
          />
          <TextInput
            type="date"
            label={t('positions.from')}
            value={from}
            onChange={(e) => setFrom(e.currentTarget.value)}
            data-testid="snapshot-from"
          />
          <TextInput
            type="date"
            label={t('positions.to')}
            value={to}
            onChange={(e) => setTo(e.currentTarget.value)}
            data-testid="snapshot-to"
          />
        </Group>
        <Can op="position_takeSnapshots">
          <Button
            leftSection={<IconCamera size={16} />}
            loading={take.isPending}
            onClick={() => take.mutate({ params: { query: { legalEntityId: entityId } } })}
            data-testid="take-snapshots"
          >
            {t('positions.takeSnapshots')}
          </Button>
        </Can>
      </Group>
      <QueryState isPending={snapshots.isPending} error={snapshots.error}>
        {rows.length === 0 ? (
          <EmptyState title={t('positions.noSnapshots')} description={t('positions.noSnapshotsHint')} />
        ) : (
          <Stack>
            {series.length > 1 && headings[0] && (
              <Paper withBorder radius="lg" p="lg">
                <Title order={3} size="h5" mb="sm">
                  {t(headings[0].label)} ({headings[0].unit})
                </Title>
                <LineChart
                  h={220}
                  data={series}
                  dataKey="date"
                  series={[{ name: 'value', label: t(headings[0].label), color: 'brand.6' }]}
                  curveType="linear"
                  data-testid="snapshot-chart"
                />
              </Paper>
            )}
            <Table withTableBorder highlightOnHover data-testid="snapshots-table">
              <Table.Thead>
                <Table.Tr>
                  <Table.Th>{t('positions.asOf')}</Table.Th>
                  {headings.map((heading) => (
                    <Table.Th key={heading.label} ta="right">
                      {t(heading.label)} ({heading.unit})
                    </Table.Th>
                  ))}
                  <Table.Th>{t('positions.takenAt')}</Table.Th>
                </Table.Tr>
              </Table.Thead>
              <Table.Tbody>
                {rows.map((snapshot) => (
                  <Table.Tr key={snapshot.id} data-testid={`snapshot-row-${snapshot.asOfDate}`}>
                    <Table.Td>{formatDate(snapshot.asOfDate)}</Table.Td>
                    {snapshotFigures(snapshot).map((figure) => (
                      <Table.Td key={figure.label} ta="right" className="etrm-figure">
                        {figure.value == null ? '—' : formatDecimal(figure.value)}
                      </Table.Td>
                    ))}
                    <Table.Td>{formatDateTime(snapshot.takenAt)}</Table.Td>
                  </Table.Tr>
                ))}
              </Table.Tbody>
            </Table>
          </Stack>
        )}
      </QueryState>
    </Stack>
  );
}
