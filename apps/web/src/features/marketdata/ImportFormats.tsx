import { Badge, Button, Group, Stack, Table, Text, Title } from '@mantine/core';
import { IconPencil, IconPlus } from '@tabler/icons-react';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { $api } from '@/shared/api/client';
import { Can, useCan } from '@/shared/auth/Can';
import { JsonEditorModal } from '@/shared/components/JsonEditorModal';
import { notifySuccess } from '@/shared/components/notify';
import { QueryState } from '@/shared/components/QueryState';
import { StatusBadge } from '@/shared/components/StatusBadge';
import { importFormatJson, toUpsertImportFormat, type ImportFormat } from './model/marketData';
import { useRefreshMarketData } from './InstrumentActions';

/** Edits one format as JSON; a new one also gets its code. */
function FormatEditor({ format, onClose }: { format: ImportFormat | null; onClose: () => void }) {
  const { t } = useTranslation();
  const refresh = useRefreshMarketData();
  const [code, setCode] = useState(format?.code ?? '');
  const save = $api.useMutation('put', '/api/v1/market-data/import-formats', {
    meta: { notify: false },
    onSuccess: (saved) => {
      notifySuccess(t('marketData.formatSaved', { code: saved.code, version: saved.version }));
      void refresh();
      onClose();
    },
  });
  return (
    <JsonEditorModal
      title={format ? t('marketData.formatTitle', { code: format.code }) : t('marketData.formatTitleNew')}
      hint={t('marketData.formatHint')}
      initial={importFormatJson(format ?? undefined)}
      code={code}
      onCodeChange={format ? undefined : setCode}
      codeLabel={t('marketData.formatCode')}
      pending={save.isPending}
      error={save.error}
      onSubmit={(json) => save.mutate({ body: toUpsertImportFormat(format?.code ?? code, json) })}
      onClose={onClose}
    />
  );
}

/** The import formats files are read with: a list, and an editor for a new or changed format. */
export function ImportFormats() {
  const { t } = useTranslation();
  const formats = $api.useQuery('get', '/api/v1/market-data/import-formats', {});
  const canEdit = useCan('marketData_upsertImportFormat');
  const [editing, setEditing] = useState<ImportFormat | 'new' | null>(null);
  return (
    <Stack>
      <Group justify="space-between">
        <Title order={5}>{t('marketData.formats')}</Title>
        <Can op="marketData_upsertImportFormat">
          <Button
            size="xs"
            variant="light"
            leftSection={<IconPlus size={14} />}
            onClick={() => setEditing('new')}
            data-testid="new-format"
          >
            {t('marketData.newFormat')}
          </Button>
        </Can>
      </Group>
      <QueryState isPending={formats.isPending} error={formats.error}>
        {(formats.data ?? []).length === 0 ? (
          <Text size="sm" c="dimmed">
            {t('marketData.noFormats')}
          </Text>
        ) : (
          <Table withTableBorder striped data-testid="formats-table">
            <Table.Thead>
              <Table.Tr>
                <Table.Th>{t('marketData.code')}</Table.Th>
                <Table.Th>{t('marketData.kind')}</Table.Th>
                <Table.Th>{t('marketData.source')}</Table.Th>
                <Table.Th>{t('marketData.descriptionColumn')}</Table.Th>
                <Table.Th>{t('marketData.version')}</Table.Th>
                <Table.Th>{t('marketData.active')}</Table.Th>
                <Table.Th />
              </Table.Tr>
            </Table.Thead>
            <Table.Tbody>
              {(formats.data ?? []).map((format) => (
                <Table.Tr key={format.code} data-testid={`format-row-${format.code}`}>
                  <Table.Td>
                    <Text size="sm" ff="monospace">
                      {format.code}
                    </Text>
                  </Table.Td>
                  <Table.Td>
                    <Badge variant="outline" color="gray">
                      {format.kind}
                    </Badge>
                  </Table.Td>
                  <Table.Td>{format.source}</Table.Td>
                  <Table.Td>{format.description}</Table.Td>
                  <Table.Td data-testid={`format-version-${format.code}`}>v{format.version}</Table.Td>
                  <Table.Td>
                    <StatusBadge status={format.active ? 'ACTIVE' : 'INACTIVE'} />
                  </Table.Td>
                  <Table.Td>
                    {canEdit && (
                      <Button
                        size="xs"
                        variant="subtle"
                        leftSection={<IconPencil size={14} />}
                        onClick={() => setEditing(format)}
                        data-testid={`format-edit-${format.code}`}
                      >
                        {t('marketData.editFormat')}
                      </Button>
                    )}
                  </Table.Td>
                </Table.Tr>
              ))}
            </Table.Tbody>
          </Table>
        )}
      </QueryState>
      {editing && <FormatEditor format={editing === 'new' ? null : editing} onClose={() => setEditing(null)} />}
    </Stack>
  );
}
