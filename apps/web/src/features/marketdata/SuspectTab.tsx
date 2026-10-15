import { Button, Group, Table, Text, TextInput } from '@mantine/core';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { $api } from '@/shared/api/client';
import { Can } from '@/shared/auth/Can';
import { QueryState } from '@/shared/components/QueryState';
import { formatDecimal } from '@/shared/format/format';
import { useRefreshMarketData } from './hooks';

export function SuspectTab() {
  const { t } = useTranslation();
  const refresh = useRefreshMarketData();
  const suspect = $api.useQuery('get', '/api/v1/market-data/quotes/suspect', {});
  const acknowledge = $api.useMutation('post', '/api/v1/market-data/quotes/{id}/acknowledge', {
    onSuccess: () => void refresh(),
  });
  const [notes, setNotes] = useState<Record<string, string>>({});
  return (
    <QueryState isPending={suspect.isPending} error={suspect.error}>
      {(suspect.data ?? []).length === 0 ? (
        <Text c="dimmed" size="sm">
          {t('marketData.noSuspect')}
        </Text>
      ) : (
        <Table withTableBorder striped>
          <Table.Thead>
            <Table.Tr>
              <Table.Th>{t('marketData.instrument')}</Table.Th>
              <Table.Th>{t('marketData.date')}</Table.Th>
              <Table.Th ta="right">{t('marketData.value')}</Table.Th>
              <Table.Th>{t('marketData.note')}</Table.Th>
              <Table.Th />
            </Table.Tr>
          </Table.Thead>
          <Table.Tbody>
            {(suspect.data ?? []).map((quote) => (
              <Table.Tr key={quote.id}>
                <Table.Td>{quote.instrumentCode}</Table.Td>
                <Table.Td>{quote.quoteDate}</Table.Td>
                <Table.Td ta="right">
                  {formatDecimal(quote.value, 2, 6)} {quote.currency}
                </Table.Td>
                <Table.Td>{quote.qualityNote}</Table.Td>
                <Table.Td>
                  <Can op="marketData_acknowledge">
                    <Group gap="xs" wrap="nowrap">
                      <TextInput
                        size="xs"
                        placeholder={t('marketData.why')}
                        value={notes[quote.id] ?? ''}
                        onChange={(e) => setNotes({ ...notes, [quote.id]: e.currentTarget.value })}
                      />
                      <Button
                        size="xs"
                        variant="light"
                        disabled={!notes[quote.id]?.trim()}
                        onClick={() =>
                          acknowledge.mutate({
                            params: { path: { id: quote.id } },
                            body: { note: notes[quote.id]?.trim() ?? '' },
                          })
                        }
                      >
                        {t('marketData.acknowledge')}
                      </Button>
                    </Group>
                  </Can>
                </Table.Td>
              </Table.Tr>
            ))}
          </Table.Tbody>
        </Table>
      )}
    </QueryState>
  );
}
