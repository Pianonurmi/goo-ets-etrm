import type { ColDef, ICellRendererParams } from 'ag-grid-community';
import { useMemo } from 'react';
import { useTranslation } from 'react-i18next';
import type { Schema } from '@/shared/api/types';
import { QueryState } from '@/shared/components/QueryState';
import { statusLabel } from '@/shared/components/StatusBadge';
import { DataGrid } from '@/shared/grid/DataGrid';
import { useAccounts } from './hooks';
import { ActiveBadge } from './shared';

export type Account = Schema<'GlAccountView'>;

export function AccountsTab({ entityId }: { entityId: string }) {
  const { t } = useTranslation();
  const accounts = useAccounts(entityId);
  const columns = useMemo<ColDef<Account>[]>(
    () => [
      { field: 'code', headerName: t('ledger.account'), width: 120, sort: 'asc' },
      { field: 'name', headerName: t('ledger.accountName'), flex: 1, minWidth: 240 },
      {
        field: 'type',
        headerName: t('ledger.type'),
        width: 130,
        valueFormatter: (p) => statusLabel(String(p.value)),
      },
      { field: 'glMapping', headerName: t('ledger.glMapping'), width: 140 },
      {
        field: 'active',
        headerName: t('marketData.active'),
        width: 120,
        cellRenderer: (p: ICellRendererParams<Account>) => p.data && <ActiveBadge active={p.data.active} />,
      },
    ],
    [t],
  );
  return (
    <QueryState isPending={accounts.isPending} error={accounts.error}>
      <DataGrid<Account> rowData={accounts.data ?? []} columnDefs={columns} rowId={(row) => row.id} height={520} />
    </QueryState>
  );
}
