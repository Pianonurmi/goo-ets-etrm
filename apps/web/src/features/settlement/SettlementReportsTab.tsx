import { useUrlState } from '@/shared/useUrlState';
import { Group, SegmentedControl, Stack, Table, Text, TextInput } from '@mantine/core';
import dayjs from 'dayjs';
import type { ReactNode } from 'react';
import { useTranslation } from 'react-i18next';
import { $api } from '@/shared/api/client';
import { QueryState } from '@/shared/components/QueryState';
import { formatDate, formatDecimal, formatMoney } from '@/shared/format/format';
import { useCounterparties } from '@/features/trades/hooks';
import { REPORTS, asReport, reportDefaults } from './model/payments';

function ReportTable({
  headers,
  rows,
  empty,
}: {
  headers: { label: string; right?: boolean }[];
  rows: ReactNode[][];
  empty: string;
}) {
  if (rows.length === 0) {
    return (
      <Text size="sm" c="dimmed" data-testid="report-empty">
        {empty}
      </Text>
    );
  }
  return (
    <Table striped data-testid="report-table">
      <Table.Thead>
        <Table.Tr>
          {headers.map((header) => (
            <Table.Th key={header.label} ta={header.right ? 'right' : undefined}>
              {header.label}
            </Table.Th>
          ))}
        </Table.Tr>
      </Table.Thead>
      <Table.Tbody>
        {rows.map((cells, row) => (
          <Table.Tr key={row}>
            {cells.map((cell, column) => (
              <Table.Td key={column} ta={headers[column]?.right ? 'right' : undefined}>
                {cell}
              </Table.Td>
            ))}
          </Table.Tr>
        ))}
      </Table.Tbody>
    </Table>
  );
}

function Ageing({ entityId, asOf }: { entityId: string; asOf: string }) {
  const { t } = useTranslation();
  const counterparties = useCounterparties();
  const rows = $api.useQuery('get', '/api/v1/settlement/reports/ageing', {
    params: { query: { legalEntityId: entityId, asOf } },
  });
  return (
    <QueryState isPending={rows.isPending} error={rows.error}>
      <ReportTable
        empty={t('settlement.nothingOpen')}
        headers={[
          { label: t('trades.columns.counterparty') },
          { label: t('settlement.direction') },
          { label: t('settlement.notDue'), right: true },
          { label: '1–30', right: true },
          { label: '31–60', right: true },
          { label: '61–90', right: true },
          { label: '> 90', right: true },
          { label: t('settlement.grossAmount'), right: true },
        ]}
        rows={(rows.data ?? []).map((row) => [
          counterparties.byId.get(row.counterpartyId)?.name ?? row.counterpartyId,
          t(`settlement.directions.${row.direction}`),
          formatMoney(row.notDue, row.currency),
          formatMoney(row.days1to30, row.currency),
          formatMoney(row.days31to60, row.currency),
          formatMoney(row.days61to90, row.currency),
          formatMoney(row.over90, row.currency),
          formatMoney(row.total, row.currency),
        ])}
      />
    </QueryState>
  );
}

function CashForecast({ entityId, from, to }: { entityId: string; from: string; to: string }) {
  const { t } = useTranslation();
  const rows = $api.useQuery('get', '/api/v1/settlement/reports/cash-forecast', {
    params: { query: { legalEntityId: entityId, from, to } },
  });
  return (
    <QueryState isPending={rows.isPending} error={rows.error}>
      <ReportTable
        empty={t('settlement.nothingDue')}
        headers={[
          { label: t('settlement.date') },
          { label: t('settlement.incoming'), right: true },
          { label: t('settlement.outgoing'), right: true },
          { label: t('settlement.basis') },
        ]}
        rows={(rows.data ?? []).map((row) => [
          formatDate(row.date),
          formatMoney(row.incoming, row.currency),
          formatMoney(row.outgoing, row.currency),
          row.basis,
        ])}
      />
    </QueryState>
  );
}

function Vat({ entityId, period }: { entityId: string; period: string }) {
  const { t } = useTranslation();
  const rows = $api.useQuery('get', '/api/v1/settlement/reports/vat', {
    params: { query: { legalEntityId: entityId, period } },
  });
  return (
    <QueryState isPending={rows.isPending} error={rows.error}>
      <ReportTable
        empty={t('settlement.nothingIssued')}
        headers={[
          { label: t('settlement.direction') },
          { label: t('settlement.vatTreatment') },
          { label: t('settlement.vatRate'), right: true },
          { label: t('settlement.netAmount'), right: true },
          { label: t('settlement.vatAmount'), right: true },
        ]}
        rows={(rows.data ?? []).map((row) => [
          t(`settlement.directions.${row.direction}`),
          t(`settlement.treatments.${row.treatment}`),
          `${formatDecimal(row.rate, 0, 2)} %`,
          formatMoney(row.net, row.currency),
          formatMoney(row.vat, row.currency),
        ])}
      />
    </QueryState>
  );
}

function EcSalesList({ entityId, period }: { entityId: string; period: string }) {
  const { t } = useTranslation();
  const rows = $api.useQuery('get', '/api/v1/settlement/reports/ec-sales-list', {
    params: { query: { legalEntityId: entityId, period } },
  });
  return (
    <QueryState isPending={rows.isPending} error={rows.error}>
      <ReportTable
        empty={t('settlement.noIntraEuSales')}
        headers={[
          { label: t('settlement.customerVatNumber') },
          { label: t('settlement.country') },
          { label: t('settlement.netAmount'), right: true },
        ]}
        rows={(rows.data ?? []).map((row) => [row.customerVatNumber, row.country, formatMoney(row.net, row.currency)])}
      />
    </QueryState>
  );
}

/** Receivables and payables by age, expected cash, VAT by period and the EC sales list; parameters in the URL. */
export function SettlementReportsTab({ entityId }: { entityId: string }) {
  const { t } = useTranslation();
  const { params, set: patchUrl } = useUrlState();
  const report = asReport(params.get('report'));
  const defaults = reportDefaults(dayjs().format('YYYY-MM-DD'));
  const value = (key: keyof typeof defaults) => params.get(key) ?? defaults[key];
  const set = (key: string, next: string) => patchUrl({ [key]: next });
  return (
    <Stack>
      <Group align="flex-end">
        <SegmentedControl
          value={report}
          onChange={(next) => set('report', next === 'ageing' ? '' : next)}
          data={REPORTS.map((name) => ({ value: name, label: t(`settlement.reports.${name}`) }))}
          data-testid="report-choice"
        />
        {report === 'ageing' && (
          <TextInput
            type="date"
            label={t('settlement.asOf')}
            value={value('asOf')}
            onChange={(e) => set('asOf', e.currentTarget.value)}
          />
        )}
        {report === 'cash-forecast' && (
          <>
            <TextInput
              type="date"
              label={t('settlement.from')}
              value={value('from')}
              onChange={(e) => set('from', e.currentTarget.value)}
            />
            <TextInput
              type="date"
              label={t('settlement.toExclusive')}
              value={value('to')}
              onChange={(e) => set('to', e.currentTarget.value)}
            />
          </>
        )}
        {(report === 'vat' || report === 'ec-sales-list') && (
          <TextInput
            type="month"
            label={t('ledger.period')}
            value={value('period')}
            onChange={(e) => set('period', e.currentTarget.value)}
            data-testid="report-period"
          />
        )}
      </Group>
      {report === 'ageing' && <Ageing entityId={entityId} asOf={value('asOf')} />}
      {report === 'cash-forecast' && <CashForecast entityId={entityId} from={value('from')} to={value('to')} />}
      {report === 'vat' && <Vat entityId={entityId} period={value('period')} />}
      {report === 'ec-sales-list' && <EcSalesList entityId={entityId} period={value('period')} />}
    </Stack>
  );
}
