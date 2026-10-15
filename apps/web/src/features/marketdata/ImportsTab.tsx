import { Button, FileInput, Group, Select, Stack, Table, Text } from '@mantine/core';
import { IconUpload } from '@tabler/icons-react';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { $api, authorisedFetch } from '@/shared/api/client';
import { type Schema } from '@/shared/api/types';
import { Can } from '@/shared/auth/Can';
import { notifyProblem } from '@/shared/components/notify';
import { useRefreshMarketData } from './hooks';
import { ImportFormats } from './ImportFormats';

export function ImportsTab() {
  const { t } = useTranslation();
  const refresh = useRefreshMarketData();
  const formats = $api.useQuery('get', '/api/v1/market-data/import-formats', {});
  const [format, setFormat] = useState<string | null>(null);
  const [file, setFile] = useState<File | null>(null);
  const [result, setResult] = useState<Schema<'ImportResult'> | null>(null);
  const [busy, setBusy] = useState(false);
  const upload = async () => {
    if (!file || !format) return;
    setBusy(true);
    try {
      const data = new FormData();
      data.append('file', file);
      const response = await authorisedFetch(`/api/v1/market-data/imports?format=${encodeURIComponent(format)}`, {
        method: 'POST',
        body: data,
      });
      setResult((await response.json()) as Schema<'ImportResult'>);
      void refresh();
    } catch (error) {
      notifyProblem(error);
    } finally {
      setBusy(false);
    }
  };
  return (
    <Stack>
      <Stack maw={720}>
        <Group align="flex-end">
          <Select
            label={t('marketData.format')}
            data={(formats.data ?? []).map((f) => ({ value: f.code, label: `${f.code} · ${f.description}` }))}
            value={format}
            onChange={setFormat}
            w={320}
          />
          <FileInput
            label={t('marketData.file')}
            value={file}
            onChange={setFile}
            accept=".csv,text/csv"
            w={260}
            clearable
          />
          <Can op="marketData_importFile">
            <Button
              leftSection={<IconUpload size={16} />}
              disabled={!file || !format}
              loading={busy}
              onClick={() => void upload()}
            >
              {t('marketData.import')}
            </Button>
          </Can>
        </Group>
        {result && (
          <Table withTableBorder>
            <Table.Tbody>
              {(['rowsRead', 'accepted', 'suspect', 'rejected'] as const).map((key) => (
                <Table.Tr key={key}>
                  <Table.Td>{t(`marketData.result.${key}`)}</Table.Td>
                  <Table.Td>{result[key]}</Table.Td>
                </Table.Tr>
              ))}
              {result.duplicate && (
                <Table.Tr>
                  <Table.Td colSpan={2}>{t('marketData.duplicate')}</Table.Td>
                </Table.Tr>
              )}
              {result.errors.map((error) => (
                <Table.Tr key={`${error.lineNo}-${error.message}`}>
                  <Table.Td colSpan={2}>
                    <Text size="sm" c="red">
                      {t('marketData.lineError', { line: error.lineNo, message: error.message })}
                    </Text>
                  </Table.Td>
                </Table.Tr>
              ))}
            </Table.Tbody>
          </Table>
        )}
      </Stack>
      <ImportFormats />
    </Stack>
  );
}
