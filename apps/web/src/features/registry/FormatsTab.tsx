import { Button, Code, Group, Paper, Stack, Table, Text } from '@mantine/core';
import { IconPencil, IconPlus } from '@tabler/icons-react';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { $api } from '@/shared/api/client';
import { type Schema } from '@/shared/api/types';
import { Can } from '@/shared/auth/Can';
import { JsonEditorModal } from '@/shared/components/JsonEditorModal';
import { notifySuccess } from '@/shared/components/notify';
import { QueryState } from '@/shared/components/QueryState';
import { ScrollTable } from '@/shared/components/ScrollTable';
import { StatusBadge } from '@/shared/components/StatusBadge';
import { formatDateTime } from '@/shared/format/format';
import { NEW_FORMAT, formatKey, formatToUpsert, upsertBody } from './model/formats';
import { useInvalidateRegistry } from './TransferModal';

export type Format = Schema<'StatementFormatView'>;

/**
 * Edits a statement format as JSON: an existing one (keyed by registry, profile and kind), or a new profile from a
 * template. The server checks the column mapping against the kind and the date pattern.
 */
export function FormatEditor({ format, onClose }: { format: Format | null; onClose: () => void }) {
  const { t } = useTranslation();
  const invalidate = useInvalidateRegistry();
  const [code, setCode] = useState('');
  const save = $api.useMutation('put', '/api/v1/registry/statement-formats', {
    meta: { notify: false },
    onSuccess: (saved) => {
      notifySuccess(t('registry.formatSaved', { profile: saved.profileCode, version: saved.version }));
      void invalidate();
      onClose();
    },
  });
  return (
    <JsonEditorModal
      title={format ? t('registry.editFormat', { profile: format.profileCode }) : t('registry.newFormat')}
      hint={t('registry.formatEditorHint')}
      initial={format ? formatToUpsert(format) : NEW_FORMAT}
      {...(format ? {} : { code, onCodeChange: setCode, codeLabel: t('registry.profile') })}
      pending={save.isPending}
      error={save.error}
      onSubmit={(json) => save.mutate({ body: upsertBody(json, format ? undefined : code) })}
      onClose={onClose}
    />
  );
}

export function FormatsTab() {
  const { t } = useTranslation();
  const formats = $api.useQuery('get', '/api/v1/registry/statement-formats', {});
  const [editing, setEditing] = useState<Format | 'new' | null>(null);
  return (
    <Stack>
      <Group justify="space-between">
        <Text size="sm" c="dimmed">
          {t('registry.formatsHint')}
        </Text>
        <Can op="registry_upsertFormat">
          <Button
            variant="light"
            leftSection={<IconPlus size={16} />}
            onClick={() => setEditing('new')}
            data-testid="new-format"
          >
            {t('registry.newFormat')}
          </Button>
        </Can>
      </Group>
      <QueryState isPending={formats.isPending} error={formats.error}>
        <Paper withBorder radius="lg">
          <ScrollTable label={t('registry.tabs.formats')} minWidth={900}>
            <Table verticalSpacing="sm" highlightOnHover data-testid="formats-table">
              <Table.Thead>
                <Table.Tr>
                  <Table.Th>{t('registry.registry')}</Table.Th>
                  <Table.Th>{t('registry.profile')}</Table.Th>
                  <Table.Th>{t('registry.kind')}</Table.Th>
                  <Table.Th>{t('registry.layout')}</Table.Th>
                  <Table.Th>{t('registry.columns')}</Table.Th>
                  <Table.Th>{t('registry.updated')}</Table.Th>
                  <Table.Th />
                </Table.Tr>
              </Table.Thead>
              <Table.Tbody>
                {(formats.data ?? []).map((format) => (
                  <Table.Tr key={format.id} data-testid={`format-row-${formatKey(format)}`}>
                    <Table.Td fw={600}>{format.registryCode}</Table.Td>
                    <Table.Td>
                      {format.profileCode}
                      {!format.active && <StatusBadge ml="xs" status="INACTIVE" label={t('registry.inactive')} />}
                    </Table.Td>
                    <Table.Td>{t(`registry.statementKinds.${format.kind}`)}</Table.Td>
                    <Table.Td>
                      <Text size="xs">
                        {t('registry.layoutText', {
                          delimiter: format.delimiter,
                          skip: format.skipLines,
                          date: format.dateFormat,
                        })}
                      </Text>
                    </Table.Td>
                    <Table.Td>
                      <Stack gap={2}>
                        {Object.entries(format.columnMapping).map(([field, column]) => (
                          <Text size="xs" key={field}>
                            <Code>{column}</Code> → {field}
                          </Text>
                        ))}
                      </Stack>
                    </Table.Td>
                    <Table.Td>
                      <Text size="xs">
                        v{format.version} · {format.updatedBy} · {formatDateTime(format.updatedAt)}
                      </Text>
                    </Table.Td>
                    <Table.Td ta="right">
                      <Can op="registry_upsertFormat">
                        <Button
                          size="compact-xs"
                          variant="subtle"
                          leftSection={<IconPencil size={12} />}
                          onClick={() => setEditing(format)}
                          data-testid={`format-edit-${formatKey(format)}`}
                        >
                          {t('registry.edit')}
                        </Button>
                      </Can>
                    </Table.Td>
                  </Table.Tr>
                ))}
              </Table.Tbody>
            </Table>
          </ScrollTable>
        </Paper>
      </QueryState>
      {editing && <FormatEditor format={editing === 'new' ? null : editing} onClose={() => setEditing(null)} />}
    </Stack>
  );
}
