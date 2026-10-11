import { Badge, Button, FileInput, Group, Modal, Select, Stack, Table, Tabs, Text, TextInput } from '@mantine/core';
import { IconCloudDownload, IconLink, IconPlus, IconUpload } from '@tabler/icons-react';
import { useQueryClient } from '@tanstack/react-query';
import type { ColDef, ICellRendererParams } from 'ag-grid-community';
import dayjs from 'dayjs';
import { useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useSearchParams } from 'react-router';
import { $api, authorisedFetch } from '@/shared/api/client';
import { decimal, type Schema } from '@/shared/api/types';
import { Can } from '@/shared/auth/Can';
import { notifyProblem, notifySuccess } from '@/shared/components/notify';
import { PageHeader } from '@/shared/components/PageHeader';
import { ProblemAlert } from '@/shared/components/ProblemAlert';
import { QueryState } from '@/shared/components/QueryState';
import { formatDateTime, formatDecimal } from '@/shared/format/format';
import { DataGrid } from '@/shared/grid/DataGrid';
import { ImportFormats } from './ImportFormats';
import { IndicesTab } from './IndicesTab';
import { AliasModal, CreateInstrumentModal } from './InstrumentActions';

type Instrument = Schema<'InstrumentView'>;
type Quote = Schema<'QuoteView'>;
type QuoteType = Schema<'RecordQuote'>['quoteType'];
const QUOTE_TYPES: QuoteType[] = ['SETTLEMENT', 'MID', 'BID', 'ASK', 'CLOSE', 'FIXING'];

function useRefreshMarketData() {
  const queryClient = useQueryClient();
  return () =>
    queryClient.invalidateQueries({
      predicate: (q) => typeof q.queryKey[1] === 'string' && q.queryKey[1].startsWith('/api/v1/market-data'),
    });
}

function InstrumentsTab() {
  const { t } = useTranslation();
  const instruments = $api.useQuery('get', '/api/v1/market-data/instruments', {
    params: { query: { activeOnly: false } },
  });
  const [creating, setCreating] = useState(false);
  const [aliasFor, setAliasFor] = useState<string | null>(null);
  const columns = useMemo<ColDef<Instrument>[]>(
    () => [
      { field: 'code', headerName: t('marketData.code'), width: 230 },
      { field: 'kind', headerName: t('marketData.kind'), width: 140 },
      { field: 'description', headerName: t('marketData.descriptionColumn'), flex: 1 },
      { field: 'bucketCode', headerName: t('positions.bucket'), width: 150 },
      { field: 'productionYear', headerName: t('positions.vintage'), width: 100 },
      { field: 'allowanceProductCode', headerName: t('trades.form.allowanceProduct'), width: 150 },
      { field: 'currency', headerName: t('trades.form.currency'), width: 100 },
      { field: 'active', headerName: t('marketData.active'), width: 90 },
    ],
    [t],
  );
  return (
    <Stack>
      <Group justify="flex-end">
        <Can op="marketData_addAlias">
          <Button
            variant="default"
            leftSection={<IconLink size={16} />}
            onClick={() => setAliasFor('')}
            data-testid="add-alias"
          >
            {t('marketData.addAlias')}
          </Button>
        </Can>
        <Can op="marketData_createInstrument">
          <Button leftSection={<IconPlus size={16} />} onClick={() => setCreating(true)} data-testid="new-instrument">
            {t('marketData.newInstrument')}
          </Button>
        </Can>
      </Group>
      <QueryState isPending={instruments.isPending} error={instruments.error}>
        <DataGrid<Instrument>
          rowData={instruments.data ?? []}
          columnDefs={columns}
          rowId={(i) => i.code}
          height={520}
        />
      </QueryState>
      {creating && <CreateInstrumentModal onClose={() => setCreating(false)} />}
      {aliasFor !== null && (
        <AliasModal
          instruments={(instruments.data ?? []).filter((i) => i.active).map((i) => i.code)}
          initialInstrument={aliasFor || undefined}
          onClose={() => setAliasFor(null)}
        />
      )}
    </Stack>
  );
}

function RecordQuoteModal({
  opened,
  onClose,
  instruments,
}: {
  opened: boolean;
  onClose: () => void;
  instruments: Instrument[];
}) {
  const { t } = useTranslation();
  const refresh = useRefreshMarketData();
  const [values, setValues] = useState({
    instrumentCode: '',
    quoteDate: dayjs().format('YYYY-MM-DD'),
    quoteType: 'CLOSE' as QuoteType,
    value: '',
  });
  const record = $api.useMutation('put', '/api/v1/market-data/quotes', {
    meta: { notify: false },
    onSuccess: (quote) => {
      notifySuccess(quote.quality === 'OK' ? t('marketData.quoteRecorded') : t('marketData.quoteSuspect'));
      void refresh();
      onClose();
    },
  });
  return (
    <Modal opened={opened} onClose={onClose} title={t('marketData.recordQuote')}>
      <Stack>
        <ProblemAlert error={record.error} />
        <Select
          label={t('marketData.instrument')}
          data={instruments.map((i) => i.code)}
          searchable
          value={values.instrumentCode || null}
          onChange={(code) => setValues({ ...values, instrumentCode: code ?? '' })}
        />
        <Group grow>
          <TextInput
            type="date"
            label={t('marketData.date')}
            value={values.quoteDate}
            onChange={(e) => setValues({ ...values, quoteDate: e.currentTarget.value })}
          />
          <Select
            label={t('marketData.quoteType')}
            data={QUOTE_TYPES}
            value={values.quoteType}
            allowDeselect={false}
            onChange={(type) => setValues({ ...values, quoteType: type ?? 'CLOSE' })}
          />
          <TextInput
            label={t('marketData.value')}
            inputMode="decimal"
            value={values.value}
            onChange={(e) => setValues({ ...values, value: e.currentTarget.value })}
          />
        </Group>
        <Button
          disabled={!values.instrumentCode || !values.value.trim()}
          loading={record.isPending}
          onClick={() => record.mutate({ body: { ...values, value: decimal(values.value), source: 'MANUAL' } })}
        >
          {t('marketData.recordQuote')}
        </Button>
      </Stack>
    </Modal>
  );
}

function QuotesTab() {
  const { t } = useTranslation();
  const [params, setParams] = useSearchParams();
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
  const set = (key: string, value: string) => {
    const next = new URLSearchParams(params);
    next.set(key, value);
    setParams(next, { replace: true });
  };
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
            <Badge color={p.data.quality === 'OK' ? 'teal' : 'orange'} variant="light">
              {p.data.quality}
            </Badge>
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
        <Text c="dimmed" size="sm">
          {t('marketData.pickInstrument')}
        </Text>
      )}
      <RecordQuoteModal opened={recording} onClose={() => setRecording(false)} instruments={instruments.data ?? []} />
    </Stack>
  );
}

function SuspectTab() {
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

function FxTab() {
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

function ImportsTab() {
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

/** Instruments and aliases, quotes (with suspect ones to review), FX rates, price indices, file imports and formats. */
export function MarketDataPage() {
  const { t } = useTranslation();
  const [params, setParams] = useSearchParams();
  const tab = params.get('tab') ?? 'quotes';
  return (
    <Stack>
      <PageHeader title={t('marketData.title')} description={t('marketData.description')} />
      <Tabs
        value={tab}
        onChange={(value) => setParams(value ? { tab: value } : {}, { replace: true })}
        keepMounted={false}
      >
        <Tabs.List>
          <Tabs.Tab value="quotes">{t('marketData.tabs.quotes')}</Tabs.Tab>
          <Tabs.Tab value="suspect">{t('marketData.tabs.suspect')}</Tabs.Tab>
          <Tabs.Tab value="fx">{t('marketData.tabs.fx')}</Tabs.Tab>
          <Tabs.Tab value="instruments">{t('marketData.tabs.instruments')}</Tabs.Tab>
          <Tabs.Tab value="indices">{t('marketData.tabs.indices')}</Tabs.Tab>
          <Tabs.Tab value="imports">{t('marketData.tabs.imports')}</Tabs.Tab>
        </Tabs.List>
        <Tabs.Panel value="quotes" pt="md">
          <QuotesTab />
        </Tabs.Panel>
        <Tabs.Panel value="suspect" pt="md">
          <SuspectTab />
        </Tabs.Panel>
        <Tabs.Panel value="fx" pt="md">
          <FxTab />
        </Tabs.Panel>
        <Tabs.Panel value="instruments" pt="md">
          <InstrumentsTab />
        </Tabs.Panel>
        <Tabs.Panel value="indices" pt="md">
          <IndicesTab />
        </Tabs.Panel>
        <Tabs.Panel value="imports" pt="md">
          <ImportsTab />
        </Tabs.Panel>
      </Tabs>
    </Stack>
  );
}
