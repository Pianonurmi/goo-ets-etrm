import { Badge, Code, Drawer, Stack, Text, Timeline } from '@mantine/core';
import { useTranslation } from 'react-i18next';
import { $api } from '@/shared/api/client';
import { QueryState } from '@/shared/components/QueryState';
import { formatDateTime } from '@/shared/format/format';
import { changedFields } from './model/history';

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
  const versions = history.data ?? [];
  return (
    <Drawer opened onClose={onClose} position="right" size="lg" title={t('admin.historyOf', { name: title })}>
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
                  <Text size="xs" c="dimmed">
                    {formatDateTime(entry.recordedFrom)} · {entry.actor ?? t('admin.system')}
                  </Text>
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
    </Drawer>
  );
}
