import { Stack, Switch } from '@mantine/core';
import type { ColDef, ICellRendererParams } from 'ag-grid-community';
import { useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useNavigate } from 'react-router';
import { $api } from '@/shared/api/client';
import { QueryState } from '@/shared/components/QueryState';
import { StatusBadge, statusLabel } from '@/shared/components/StatusBadge';
import { formatDateTime, formatDecimal } from '@/shared/format/format';
import { DataGrid } from '@/shared/grid/DataGrid';
import { useScopeNames } from './hooks';
import { type Limit, limitUnit, scopeLabel } from './model/limits';
import { type Breach } from './shared';

/** An open breach is a problem (red), a closed one is history (grey). */
export function BreachBadge({ status }: { status: Breach['status'] }) {
  return <StatusBadge status={status === 'OPEN' ? 'BREACH' : 'CLOSED'} label={statusLabel(status)} />;
}

export interface BreachRow extends Breach {
  limit?: Limit;
  scopeText: string;
}

export function BreachesTab({ entityId }: { entityId: string }) {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const names = useScopeNames(entityId);
  const [openOnly, setOpenOnly] = useState(true);
  const breaches = $api.useQuery('get', '/api/v1/risk/breaches', {
    params: { query: { legalEntityId: entityId, openOnly } },
  });
  const limits = $api.useQuery('get', '/api/v1/risk/limits', {
    params: { query: { legalEntityId: entityId, activeOnly: false } },
  });
  const rows = useMemo<BreachRow[]>(() => {
    const byId = new Map((limits.data ?? []).map((limit) => [limit.id, limit]));
    return (breaches.data ?? []).map((breach) => {
      const limit = byId.get(breach.limitId);
      return { ...breach, limit, scopeText: limit ? scopeLabel(limit.scope, names) : '' };
    });
  }, [breaches.data, limits.data, names]);
  const columns = useMemo<ColDef<BreachRow>[]>(
    () => [
      {
        field: 'openedAt',
        headerName: t('risk.openedAt'),
        width: 170,
        sort: 'desc',
        valueFormatter: (p) => formatDateTime(p.value as string),
      },
      {
        colId: 'type',
        headerName: t('risk.limitType'),
        width: 190,
        valueGetter: (p) => (p.data?.limit ? t(`risk.types.${p.data.limit.type}`) : ''),
      },
      { field: 'scopeText', headerName: t('risk.scope'), flex: 1, minWidth: 180 },
      {
        colId: 'limitAmount',
        headerName: t('risk.amount'),
        width: 150,
        type: 'rightAligned',
        valueGetter: (p) =>
          p.data?.limit ? `${formatDecimal(p.data.limit.amount, 0, 2)} ${limitUnit(p.data.limit)}` : '',
      },
      {
        field: 'firstValue',
        headerName: t('risk.firstValue'),
        width: 130,
        type: 'rightAligned',
        valueFormatter: (p) => formatDecimal(p.value as number, 0, 2),
      },
      {
        field: 'peakValue',
        headerName: t('risk.peakValue'),
        width: 130,
        type: 'rightAligned',
        valueFormatter: (p) => formatDecimal(p.value as number, 0, 2),
      },
      {
        field: 'status',
        headerName: t('allocation.status'),
        width: 110,
        cellRenderer: (p: ICellRendererParams<BreachRow>) => p.data && <BreachBadge status={p.data.status} />,
      },
      {
        field: 'closedAt',
        headerName: t('risk.closedAt'),
        width: 170,
        valueFormatter: (p) => formatDateTime(p.value as string | null),
      },
    ],
    [t],
  );
  return (
    <Stack>
      <Switch label={t('risk.openOnly')} checked={openOnly} onChange={(e) => setOpenOnly(e.currentTarget.checked)} />
      <QueryState isPending={breaches.isPending} error={breaches.error}>
        <DataGrid<BreachRow>
          rowData={rows}
          columnDefs={columns}
          rowId={(row) => row.id}
          height={400}
          onRowClicked={(event) => {
            if (event.data) void navigate(`/risk/limits/${event.data.limitId}`);
          }}
          overlayNoRowsTemplate={t('risk.noBreaches')}
        />
      </QueryState>
    </Stack>
  );
}
