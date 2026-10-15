import { BarChart } from '@mantine/charts';
import { Alert, Card, Stack } from '@mantine/core';
import type { ColDef } from 'ag-grid-community';
import { useMemo } from 'react';
import { useTranslation } from 'react-i18next';
import { useCounterparties } from '@/features/trades/hooks';
import { $api } from '@/shared/api/client';
import { QueryState } from '@/shared/components/QueryState';
import { formatDateTime, formatDecimal } from '@/shared/format/format';
import { DataGrid } from '@/shared/grid/DataGrid';
import { type Exposure } from './shared';

export function money(field: keyof Exposure, header: string): ColDef<Exposure> {
  return {
    field,
    headerName: header,
    width: 140,
    type: 'rightAligned',
    valueFormatter: (p) => formatDecimal(p.value as number, 2, 2),
  };
}

export function ExposuresTab({ entityId }: { entityId: string }) {
  const { t } = useTranslation();
  const counterparties = useCounterparties();
  const exposures = $api.useQuery('get', '/api/v1/risk/exposures', { params: { query: { legalEntityId: entityId } } });
  const columns = useMemo<ColDef<Exposure>[]>(
    () => [
      {
        field: 'counterpartyId',
        headerName: t('trades.columns.counterparty'),
        flex: 1,
        minWidth: 200,
        valueFormatter: (p) => counterparties.byId.get(String(p.value))?.name ?? String(p.value),
      },
      money('mtm', t('risk.mtm')),
      money('receivables', t('risk.receivables')),
      money('payablesNetted', t('risk.payablesNetted')),
      money('pfe', t('risk.pfe')),
      money('collateral', t('risk.collateral')),
      { ...money('exposure', t('risk.exposure')), cellStyle: { fontWeight: 600 } },
      { field: 'currency', headerName: t('trades.form.currency'), width: 100 },
      {
        colId: 'incomplete',
        headerName: t('risk.incompleteColumn'),
        width: 150,
        valueGetter: (p) =>
          p.data?.incomplete ? p.data.leftOutCurrencies.join(', ') || t('risk.incomplete', { currencies: '' }) : '',
        tooltipValueGetter: (p) => p.data?.incompleteReasons.join('; '),
      },
      {
        field: 'measuredAt',
        headerName: t('risk.measuredAt'),
        width: 170,
        valueFormatter: (p) => formatDateTime(p.value as string),
      },
    ],
    [t, counterparties.byId],
  );
  const chart = (exposures.data ?? []).map((exposure) => ({
    counterparty: counterparties.byId.get(exposure.counterpartyId)?.code ?? exposure.counterpartyId.slice(0, 8),
    exposure: exposure.exposure,
    collateral: exposure.collateral,
  }));
  return (
    <QueryState isPending={exposures.isPending} error={exposures.error}>
      {(exposures.data ?? []).length === 0 ? (
        <Alert color="gray">{t('risk.noExposures')}</Alert>
      ) : (
        <Stack>
          <Card withBorder>
            <BarChart
              h={220}
              data={chart}
              dataKey="counterparty"
              series={[
                { name: 'exposure', label: t('risk.exposure'), color: 'orange.6' },
                { name: 'collateral', label: t('risk.collateral'), color: 'teal.6' },
              ]}
              tickLine="y"
            />
          </Card>
          <DataGrid<Exposure>
            rowData={exposures.data ?? []}
            columnDefs={columns}
            rowId={(row) => row.counterpartyId}
            height={320}
          />
        </Stack>
      )}
    </QueryState>
  );
}
