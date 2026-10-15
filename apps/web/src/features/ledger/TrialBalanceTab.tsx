import { Stack, TextInput } from '@mantine/core';
import type { ColDef } from 'ag-grid-community';
import dayjs from 'dayjs';
import { useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { $api } from '@/shared/api/client';
import type { Schema } from '@/shared/api/types';
import { QueryState } from '@/shared/components/QueryState';
import { statusLabel } from '@/shared/components/StatusBadge';
import { DataGrid } from '@/shared/grid/DataGrid';
import { amount } from './shared';

export type TrialBalanceRow = Schema<'TrialBalanceRow'>;

export function TrialBalanceTab({ entityId }: { entityId: string }) {
  const { t } = useTranslation();
  const [period, setPeriod] = useState(dayjs().format('YYYY-MM'));
  const balance = $api.useQuery(
    'get',
    '/api/v1/ledger/trial-balance',
    { params: { query: { legalEntityId: entityId, period } } },
    { enabled: /^\d{4}-\d{2}$/.test(period) },
  );
  const columns = useMemo<ColDef<TrialBalanceRow>[]>(
    () => [
      { field: 'accountCode', headerName: t('ledger.account'), width: 120 },
      { field: 'accountName', headerName: t('ledger.accountName'), flex: 1, minWidth: 220 },
      {
        field: 'type',
        headerName: t('ledger.type'),
        width: 120,
        valueFormatter: (p) => statusLabel(String(p.value)),
      },
      {
        field: 'debit',
        headerName: t('ledger.debit'),
        width: 150,
        type: 'rightAligned',
        valueFormatter: (p) => amount(p.value as number),
      },
      {
        field: 'credit',
        headerName: t('ledger.credit'),
        width: 150,
        type: 'rightAligned',
        valueFormatter: (p) => amount(p.value as number),
      },
      {
        field: 'balance',
        headerName: t('ledger.balance'),
        width: 160,
        type: 'rightAligned',
        valueFormatter: (p) => amount(p.value as number),
      },
      { field: 'glMapping', headerName: t('ledger.glMapping'), width: 130 },
    ],
    [t],
  );
  return (
    <Stack>
      <TextInput
        type="month"
        label={t('ledger.period')}
        value={period}
        onChange={(e) => setPeriod(e.currentTarget.value)}
        w={170}
      />
      <QueryState isPending={balance.isPending} error={balance.error}>
        <DataGrid<TrialBalanceRow>
          rowData={balance.data ?? []}
          columnDefs={columns}
          rowId={(row) => row.accountCode}
          height={520}
          overlayNoRowsTemplate={t('common.empty')}
        />
      </QueryState>
    </Stack>
  );
}
