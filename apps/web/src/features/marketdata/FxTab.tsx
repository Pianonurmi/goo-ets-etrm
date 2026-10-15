import { Button, Group, Stack, Table, TextInput } from '@mantine/core';
import { IconCloudDownload } from '@tabler/icons-react';
import dayjs from 'dayjs';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { $api } from '@/shared/api/client';
import { decimal } from '@/shared/api/types';
import { Can } from '@/shared/auth/Can';
import { notifySuccess } from '@/shared/components/notify';
import { QueryState } from '@/shared/components/QueryState';
import { formatDecimal } from '@/shared/format/format';
import { useRefreshMarketData } from './hooks';

export function FxTab() {
  const { t } = useTranslation();
  const refresh = useRefreshMarketData();
  const [date, setDate] = useState(dayjs().subtract(1, 'day').format('YYYY-MM-DD'));
  const [base, setBase] = useState('EUR');
  const [form, setForm] = useState({ quote: 'SEK', rate: '' });
  const rates = $api.useQuery('get', '/api/v1/market-data/fx-rates', { params: { query: { date, base } } });
  const record = $api.useMutation('put', '/api/v1/market-data/fx-rates', {
    onSuccess: () => {
      notifySuccess(t('marketData.rateRecorded'));
      void refresh();
    },
  });
  const ecb = $api.useMutation('post', '/api/v1/market-data/ecb/pull', {
    onSuccess: (result) => {
      notifySuccess(t('marketData.ecbDone', { accepted: result.accepted }));
      void refresh();
    },
  });
  return (
    <Stack>
      <Group align="flex-end">
        <TextInput
          type="date"
          label={t('marketData.date')}
          value={date}
          onChange={(e) => setDate(e.currentTarget.value)}
        />
        <TextInput
          label={t('marketData.base')}
          value={base}
          onChange={(e) => setBase(e.currentTarget.value.toUpperCase())}
          w={90}
          maxLength={3}
        />
        <Can op="marketData_pullEcb">
          <Button
            variant="light"
            leftSection={<IconCloudDownload size={16} />}
            loading={ecb.isPending}
            onClick={() => ecb.mutate({})}
          >
            {t('marketData.pullEcb')}
          </Button>
        </Can>
      </Group>
      <QueryState isPending={rates.isPending} error={rates.error}>
        <Table withTableBorder striped maw={520}>
          <Table.Thead>
            <Table.Tr>
              <Table.Th>{t('marketData.pair')}</Table.Th>
              <Table.Th ta="right">{t('marketData.rate')}</Table.Th>
              <Table.Th>{t('marketData.source')}</Table.Th>
            </Table.Tr>
          </Table.Thead>
          <Table.Tbody>
            {(rates.data ?? []).map((rate) => (
              <Table.Tr key={`${rate.base}${rate.quote}${rate.source}`}>
                <Table.Td>
                  {rate.base}/{rate.quote}
                </Table.Td>
                <Table.Td ta="right">{formatDecimal(rate.rate, 4, 8)}</Table.Td>
                <Table.Td>{rate.source}</Table.Td>
              </Table.Tr>
            ))}
          </Table.Tbody>
        </Table>
      </QueryState>
      <Can op="marketData_recordFxRate">
        <Group align="flex-end">
          <TextInput
            label={t('marketData.quoteCurrency')}
            value={form.quote}
            onChange={(e) => setForm({ ...form, quote: e.currentTarget.value.toUpperCase() })}
            w={110}
            maxLength={3}
          />
          <TextInput
            label={t('marketData.rate')}
            inputMode="decimal"
            value={form.rate}
            onChange={(e) => setForm({ ...form, rate: e.currentTarget.value })}
            w={140}
          />
          <Button
            variant="light"
            disabled={!form.rate.trim()}
            loading={record.isPending}
            onClick={() =>
              record.mutate({ body: { date, base, quote: form.quote, rate: decimal(form.rate), source: 'MANUAL' } })
            }
          >
            {t('marketData.recordRate')}
          </Button>
        </Group>
      </Can>
    </Stack>
  );
}
