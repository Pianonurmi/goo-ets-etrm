import { Alert, SimpleGrid, Stack, Text, Title } from '@mantine/core';
import type { ColDef, ICellRendererParams } from 'ag-grid-community';
import { useMemo } from 'react';
import { useTranslation } from 'react-i18next';
import type { Schema } from '@/shared/api/types';
import { Money } from '@/shared/components/Figures';
import { KpiCard } from '@/shared/components/KpiCard';
import { StatusBadge, statusLabel } from '@/shared/components/StatusBadge';
import { formatDecimal } from '@/shared/format/format';
import { DataGrid } from '@/shared/grid/DataGrid';

type PlanPricing = Schema<'PlanPricing'>;
type ObligationShadowPrice = Schema<'ObligationShadowPrice'>;
type SupplyShadowPrice = Schema<'SupplyShadowPrice'>;

const cents = (value: number | null | undefined) => (value == null ? '—' : formatDecimal(value, 2, 2));

/**
 * What the allocation program says one more MWh would cost (per sale) or save (per supply), as the server states it.
 * The figures are indicative: a linear program in floating point, not unique when it is degenerate, never booked.
 * Nothing is computed here.
 */
export function ShadowPricesPanel({
  pricing,
  obligationLabel,
  kpiTestId,
  obligationsTestId,
  supplyTestId,
}: {
  pricing: PlanPricing;
  /** A readable name for an obligation id; the panel falls back to a short id. */
  obligationLabel: (obligationId: string) => string | undefined;
  kpiTestId: string;
  obligationsTestId: string;
  supplyTestId: string;
}) {
  const { t } = useTranslation();
  const obligationColumns = useMemo<ColDef<ObligationShadowPrice>[]>(
    () => [
      {
        colId: 'obligation',
        headerName: t('allocation.shadow.obligation'),
        flex: 2,
        minWidth: 200,
        valueGetter: (p) => (p.data ? (obligationLabel(p.data.obligationId) ?? p.data.obligationId.slice(-8)) : ''),
        tooltipValueGetter: (p) => String(p.value ?? ''),
      },
      {
        field: 'coveredMwh',
        headerName: t('allocation.shadow.covered'),
        width: 120,
        type: 'rightAligned',
        valueFormatter: (p) => formatDecimal(p.value as number),
      },
      {
        field: 'marginalCostEur',
        headerName: t('allocation.shadow.marginalCost'),
        width: 170,
        type: 'rightAligned',
        valueFormatter: (p) => cents(p.value as number | null | undefined),
        cellStyle: { fontWeight: 600 },
      },
      {
        field: 'short',
        headerName: t('allocation.shadow.coverage'),
        width: 130,
        cellRenderer: (p: ICellRendererParams<ObligationShadowPrice>) =>
          p.data?.short ? (
            <span title={t('allocation.shadow.shortHint')}>
              <StatusBadge status="WARNING" label={t('allocation.shadow.short')} data-testid="shadow-short" />
            </span>
          ) : null,
      },
    ],
    [t, obligationLabel],
  );
  const supplyColumns = useMemo<ColDef<SupplyShadowPrice>[]>(
    () => [
      // Most telling first: on a narrow page the grid scrolls, and the rents stay in view.
      { field: 'bucketCode', headerName: t('allocation.shadow.bucket'), flex: 1, minWidth: 190 },
      { field: 'productionYear', headerName: t('allocation.shadow.year'), width: 80 },
      {
        field: 'maxRentEur',
        headerName: t('allocation.shadow.maxRent'),
        width: 140,
        type: 'rightAligned',
        valueFormatter: (p) => cents(p.value as number | null | undefined),
        cellStyle: { fontWeight: 600 },
      },
      {
        field: 'averageRentEur',
        headerName: t('allocation.shadow.averageRent'),
        width: 160,
        type: 'rightAligned',
        valueFormatter: (p) => cents(p.value as number | null | undefined),
      },
      {
        field: 'supplyMwh',
        headerName: t('allocation.shadow.supply'),
        width: 120,
        type: 'rightAligned',
        valueFormatter: (p) => formatDecimal(p.value as number),
      },
      {
        field: 'usedMwh',
        headerName: t('allocation.shadow.used'),
        width: 110,
        type: 'rightAligned',
        valueFormatter: (p) => formatDecimal(p.value as number),
      },
    ],
    [t],
  );

  if (pricing.status !== 'OPTIMAL') {
    return (
      <Alert color="gray" title={t('allocation.shadow.unavailable')} data-testid="shadow-prices-unavailable">
        {statusLabel(pricing.status)}
        {pricing.reason ? `: ${pricing.reason}` : ''}
      </Alert>
    );
  }

  const currency = pricing.currency ?? 'EUR';
  const hint = (key: string) => t(`allocation.shadow.hints.${key}`);
  const figure = (amount: number | null | undefined) =>
    amount == null ? '—' : <Money value={amount} fz="inherit" fw="inherit" />;
  return (
    <Stack gap="md">
      <Text size="xs" c="dimmed" data-testid="shadow-prices-note">
        {t('allocation.shadow.indicative')}
      </Text>
      <SimpleGrid type="container" cols={{ base: 1, '440px': 2, '680px': 3 }} spacing="md" data-testid={kpiTestId}>
        <KpiCard
          label={t('allocation.shadow.lpCost')}
          value={figure(pricing.lpCostEur)}
          unit={currency}
          trend={hint('lpCost')}
        />
        <KpiCard
          label={t('allocation.shadow.planCost')}
          value={figure(pricing.planCostEur)}
          unit={currency}
          trend={hint('planCost')}
        />
        <KpiCard
          label={t('allocation.shadow.gap')}
          value={figure(pricing.gapEur)}
          unit={currency}
          trend={hint('gap')}
          trendTone={(pricing.gapEur ?? 0) > 0 ? 'warning' : 'neutral'}
        />
      </SimpleGrid>
      <Stack gap="xs">
        <Title order={4} size="h6">
          {t('allocation.shadow.obligationsTitle')}
        </Title>
        <Text size="xs" c="dimmed">
          {t('allocation.shadow.obligationsHint')}
        </Text>
        <div data-testid={obligationsTestId}>
          <DataGrid<ObligationShadowPrice>
            rowData={pricing.obligations ?? []}
            columnDefs={obligationColumns}
            rowId={(row) => row.obligationId}
            fitRows={8}
            overlayNoRowsTemplate={t('allocation.shadow.noObligations')}
          />
        </div>
      </Stack>
      <Stack gap="xs">
        <Title order={4} size="h6">
          {t('allocation.shadow.supplyTitle')}
        </Title>
        <Text size="xs" c="dimmed">
          {t('allocation.shadow.supplyHint')}
        </Text>
        <div data-testid={supplyTestId}>
          <DataGrid<SupplyShadowPrice>
            rowData={pricing.supply ?? []}
            columnDefs={supplyColumns}
            rowId={(row) => `${row.bucketCode}:${row.productionYear}`}
            fitRows={8}
            overlayNoRowsTemplate={t('allocation.shadow.noSupply')}
          />
        </div>
      </Stack>
    </Stack>
  );
}
