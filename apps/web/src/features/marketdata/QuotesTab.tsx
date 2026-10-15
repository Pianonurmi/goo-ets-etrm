import { Button, Group, Select, Stack, TextInput } from '@mantine/core';
import { IconChartLine, IconPlus } from '@tabler/icons-react';
import type { ColDef, ICellRendererParams } from 'ag-grid-community';
import dayjs from 'dayjs';
import { useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { $api } from '@/shared/api/client';
import { Can } from '@/shared/auth/Can';
import { EmptyState } from '@/shared/components/EmptyState';
import { QueryState } from '@/shared/components/QueryState';
import { StatusBadge, statusLabel } from '@/shared/components/StatusBadge';
import { formatDateTime, formatDecimal } from '@/shared/format/format';
import { DataGrid } from '@/shared/grid/DataGrid';
import { useUrlState } from '@/shared/useUrlState';
import { RecordQuoteModal } from './RecordQuoteModal';
import { type Quote } from './shared';

export function QuotesTab() {
  const { t } = useTranslation();
  const { params, set: patchUrl } = useUrlState();
  const instrument = params.get('instrument') ?? '';
  const from = params.get('from') ?? dayjs().subtract(60, 'day').format('YYYY-MM-DD');
  const to = params.get('to') ?? dayjs().add(1, 'day').format('YYYY-MM-DD');
  const [recording, setRecording] = useState(false);
  const instruments = $api.useQuery('get', '/api/v1/market-data/instruments', {});
  const quotes = $api.useQuery(
    'get',
    '/api/v1/market-data/quotes',
    { params: { query: { instrument, from, to } } },
    { enabled: Boolean(instrument) },
  );
  const set = (key: string, value: string) => patchUrl({ [key]: value });
  const columns = useMemo<ColDef<Quote>[]>(
    () => [
      { field: 'quoteDate', headerName: t('marketData.date'), width: 130, sort: 'desc' },
      { field: 'quoteType', headerName: t('marketData.quoteType'), width: 130 },
      {
        field: 'value',
        headerName: t('marketData.value'),
        width: 130,
        type: 'rightAligned',
        valueFormatter: (p) => formatDecimal(p.value as number, 2, 6),
      },
      { field: 'currency', headerName: t('trades.form.currency'), width: 100 },
      { field: 'source', headerName: t('marketData.source'), width: 120 },
      {
        field: 'quality',
        headerName: t('marketData.quality'),
        width: 130,
        cellRenderer: (p: ICellRendererParams<Quote>) =>
          p.data ? (
            <StatusBadge status={p.data.quality === 'OK' ? 'OK' : 'WARNING'} label={statusLabel(p.data.quality)} />
          ) : null,
      },
      { field: 'qualityNote', headerName: t('marketData.note'), flex: 1 },
      { field: 'recordedBy', headerName: t('marketData.recordedBy'), width: 130 },
      {
        field: 'recordedAt',
        headerName: t('marketData.recordedAt'),
        width: 170,
        valueFormatter: (p) => formatDateTime(p.value as string),
      },
    ],
    [t],
  );
  return (
    <Stack>
      <Group align="flex-end">
        <Select
          label={t('marketData.instrument')}
          data={(instruments.data ?? []).map((i) => i.code)}
          searchable
          value={instrument || null}
          onChange={(code) => set('instrument', code ?? '')}
          w={300}
          data-testid="quote-instrument"
        />
        <TextInput
          type="date"
          label={t('trades.from')}
          value={from}
          onChange={(e) => set('from', e.currentTarget.value)}
        />
        <TextInput
          type="date"
          label={t('marketData.until')}
          value={to}
          onChange={(e) => set('to', e.currentTarget.value)}
        />
        <Can op="marketData_recordQuote">
          <Button leftSection={<IconPlus size={16} />} onClick={() => setRecording(true)}>
            {t('marketData.recordQuote')}
          </Button>
        </Can>
      </Group>
      {instrument ? (
        <QueryState isPending={quotes.isPending} error={quotes.error}>
          <DataGrid<Quote>
            rowData={quotes.data ?? []}
            columnDefs={columns}
            rowId={(q) => q.id}
            height={460}
            overlayNoRowsTemplate={t('common.empty')}
          />
        </QueryState>
      ) : (
        <EmptyState
          icon={<IconChartLine size={22} />}
          title={t('marketData.pickInstrument')}
          description={t('marketData.pickInstrumentHint')}
          testId="quotes-pick"
        />
      )}
      <RecordQuoteModal opened={recording} onClose={() => setRecording(false)} instruments={instruments.data ?? []} />
    </Stack>
  );
}
