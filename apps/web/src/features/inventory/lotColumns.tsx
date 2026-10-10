import { Badge } from '@mantine/core';
import type { ColDef, ICellRendererParams } from 'ag-grid-community';
import dayjs from 'dayjs';
import type { TFunction } from 'i18next';
import type { Schema } from '@/shared/api/types';
import { formatDate, formatDecimal } from '@/shared/format/format';

export type Lot = Schema<'LotView'>;

const STATUS_COLORS: Record<string, string> = {
  HELD: 'teal',
  RESERVED: 'blue',
  IN_TRANSFER: 'cyan',
  TRANSFERRED_OUT: 'gray',
  CANCELLED: 'violet',
  EXPIRED: 'red',
  WITHDRAWN: 'orange',
  SPLIT: 'gray',
};

export function LotStatusBadge({ status }: { status: string }) {
  return (
    <Badge color={STATUS_COLORS[status] ?? 'gray'} variant="light">
      {status}
    </Badge>
  );
}

/** Days until a live lot expires, or null for lots that are no longer held. */
export function daysToExpiry(lot: Lot, today = dayjs()): number | null {
  if (!['HELD', 'RESERVED', 'IN_TRANSFER'].includes(lot.status)) return null;
  return dayjs(lot.expiryDate).diff(today.startOf('day'), 'day');
}

export function lotColumns(t: TFunction): ColDef<Lot>[] {
  return [
    { field: 'quantityMwh', headerName: t('inventory.columns.quantity'), width: 120, type: 'rightAligned' },
    { field: 'energySourceCode', headerName: t('inventory.columns.source'), width: 160 },
    { field: 'technologyCode', headerName: t('inventory.columns.technology'), width: 190 },
    {
      colId: 'device',
      headerName: t('inventory.columns.device'),
      valueGetter: (params) => (params.data ? (params.data.deviceName ?? params.data.deviceGsrn) : ''),
      width: 220,
    },
    { field: 'deviceCountry', headerName: t('inventory.columns.country'), width: 100 },
    {
      colId: 'production',
      headerName: t('inventory.columns.production'),
      valueGetter: (params) =>
        params.data
          ? `${formatDate(params.data.productionPeriod.start)} – ${formatDate(params.data.productionPeriod.endExclusive)}`
          : '',
      width: 210,
    },
    {
      field: 'expiryDate',
      headerName: t('inventory.columns.expiry'),
      width: 150,
      cellRenderer: (params: ICellRendererParams<Lot>) => {
        if (!params.data) return null;
        const days = daysToExpiry(params.data);
        return (
          <span>
            {formatDate(params.data.expiryDate)}{' '}
            {days !== null && days <= 60 && (
              <Badge size="xs" color={days <= 30 ? 'red' : 'orange'}>
                {t('inventory.expiresIn', { count: days })}
              </Badge>
            )}
          </span>
        );
      },
    },
    {
      field: 'status',
      headerName: t('inventory.columns.status'),
      width: 140,
      cellRenderer: (params: ICellRendererParams<Lot>) =>
        params.data ? <LotStatusBadge status={params.data.status} /> : null,
    },
    { field: 'registryCode', headerName: t('inventory.columns.registry'), width: 130 },
    {
      colId: 'cost',
      headerName: t('inventory.columns.cost'),
      valueGetter: (params) =>
        params.data?.costBasisPerMwh != null
          ? `${formatDecimal(params.data.costBasisPerMwh, 2, 4)} ${params.data.costCurrency ?? ''}`
          : '',
      width: 140,
      type: 'rightAligned',
    },
    { field: 'issuanceRef', headerName: t('inventory.columns.issuance'), width: 180 },
  ];
}

/** Count and MWh of a list of lots; quantities are whole certificates, so the sum is exact. */
export function totals(lots: Lot[]): { count: number; mwh: number } {
  return { count: lots.length, mwh: lots.reduce((sum, lot) => sum + lot.quantityMwh, 0) };
}
