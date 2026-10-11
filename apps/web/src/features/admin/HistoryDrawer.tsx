import {
  Badge,
  Button,
  Card,
  Code,
  Drawer,
  Group,
  Stack,
  Table,
  Text,
  TextInput,
  Timeline,
  Title,
} from '@mantine/core';
import { IconClockSearch } from '@tabler/icons-react';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { $api } from '@/shared/api/client';
import { QueryState } from '@/shared/components/QueryState';
import { formatDateTime } from '@/shared/format/format';
import { changedFields } from './model/history';
import { knownAtInstant, snapshotRows } from './model/referenceEdits';

/** The record as it was known at an instant (system time), field by field. */
function AsOf({ table, id, knownAt }: { table: string; id: string; knownAt: string }) {
  const { t } = useTranslation();
  const snapshot = $api.useQuery('get', '/api/v1/reference/history/{table}/{id}/as-of', {
    params: { path: { table, id }, query: { knownAt } },
  });
  return (
    <Card withBorder data-testid="as-of-record">
      <Title order={6} mb="xs">
        {t('admin.recordAsOf', { at: formatDateTime(knownAt) })}
      </Title>
      <QueryState isPending={snapshot.isPending} error={snapshot.error}>
        {snapshot.data ? (
          <Table striped withRowBorders={false}>
            <Table.Tbody>
              {snapshotRows(snapshot.data).map((row) => (
                <Table.Tr key={row.field}>
                  <Table.Td w="40%">
                    <Code>{row.field}</Code>
                  </Table.Td>
                  <Table.Td>
                    <Text size="xs" style={{ wordBreak: 'break-word' }}>
                      {row.value}
                    </Text>
                  </Table.Td>
                </Table.Tr>
              ))}
            </Table.Tbody>
          </Table>
        ) : (
          <Text size="sm" c="dimmed" data-testid="as-of-none">
            {t('admin.noRecordAsOf')}
          </Text>
        )}
      </QueryState>
    </Card>
  );
}

/** Versions of one reference-data record (system-versioned history), each with the fields it changed. */
export function HistoryDrawer({
  table,
  id,
  title,
  onClose,
}: {
  table: string;
  id: string;
  title: string;
  onClose: () => void;
}) {
  const { t } = useTranslation();
  const history = $api.useQuery('get', '/api/v1/reference/history/{table}/{id}', { params: { path: { table, id } } });
  const [local, setLocal] = useState('');
  const [knownAt, setKnownAt] = useState<string | null>(null);
  const versions = history.data ?? [];
  return (
    <Drawer opened onClose={onClose} position="right" size="lg" title={t('admin.historyOf', { name: title })}>
      <Stack>
        <Group align="flex-end" gap="xs">
          <TextInput
            type="datetime-local"
            label={t('admin.asOf')}
            description={t('admin.asOfHint')}
            value={local}
            onChange={(e) => setLocal(e.currentTarget.value)}
            data-testid="as-of-input"
          />
          <Button
            variant="light"
            leftSection={<IconClockSearch size={16} />}
            disabled={!knownAtInstant(local)}
            onClick={() => setKnownAt(knownAtInstant(local))}
            data-testid="as-of-show"
          >
            {t('admin.showAsOf')}
          </Button>
        </Group>
        {knownAt && <AsOf table={table} id={id} knownAt={knownAt} />}
        <QueryState isPending={history.isPending} error={history.error}>
          {versions.length === 0 ? (
            <Text size="sm" c="dimmed">
              {t('ledger.noHistory')}
            </Text>
          ) : (
            <Timeline bulletSize={20} data-testid="history">
              {versions.map((entry, index) => {
                const changes = changedFields(versions[index - 1]?.data, entry.data);
                return (
                  <Timeline.Item
                    key={entry.version}
                    title={
                      <Text size="sm" fw={600}>
                        {t('admin.version', { version: entry.version })}{' '}
                        {!entry.recordedTo && (
                          <Badge size="xs" variant="light">
                            {t('admin.current')}
                          </Badge>
                        )}
                      </Text>
                    }
                  >
                    <Group gap="xs">
                      <Text size="xs" c="dimmed">
                        {formatDateTime(entry.recordedFrom)} · {entry.actor ?? t('admin.system')}
                      </Text>
                      <Button
                        size="compact-xs"
                        variant="subtle"
                        onClick={() => setKnownAt(entry.recordedFrom)}
                        data-testid={`as-of-version-${entry.version}`}
                      >
                        {t('admin.viewVersion')}
                      </Button>
                    </Group>
                    <Stack gap={2} mt={4}>
                      {changes.map((change) => (
                        <Text size="xs" key={change.field}>
                          <Code>{change.field}</Code> {change.before ?? '∅'} → {change.after ?? '∅'}
                        </Text>
                      ))}
                    </Stack>
                  </Timeline.Item>
                );
              })}
            </Timeline>
          )}
        </QueryState>
      </Stack>
    </Drawer>
  );
}
