import { Alert, SimpleGrid, Stack, Text, Title } from '@mantine/core';
import type { ColDef } from 'ag-grid-community';
import { useMemo } from 'react';
import { useTranslation } from 'react-i18next';
import type { Schema } from '@/shared/api/types';
import { Money, Quantity } from '@/shared/components/Figures';
import { KpiCard } from '@/shared/components/KpiCard';
import { formatDecimal } from '@/shared/format/format';
import { DataGrid } from '@/shared/grid/DataGrid';

type PlanValue = Schema<'PlanValue'>;
type ObligationValue = Schema<'ObligationValue'>;
type PlanPricing = Schema<'PlanPricing'>;

const cents = (value: number | null | undefined) => (value == null ? '—' : formatDecimal(value, 2, 2));

/**
 * What a plan is worth in EUR, as the server states it: the figures of the plan, then the sales that were served with
 * certificates worth more than their own product (the give-away), largest first. Nothing is computed here.
 */
export function PlanValuePanel({
  value,
  pricing,
  obligationLabel,
  kpiTestId,
  gridTestId,
}: {
  value: PlanValue;
  /** The plan's shadow prices; when optimal, the give-away grid shows each sale's marginal cost. */
  pricing?: PlanPricing | null;
  /** A readable name for an obligation id; the panel falls back to a short id. */
  obligationLabel: (obligationId: string) => string | undefined;
  kpiTestId: string;
  gridTestId: string;
}) {
  const { t } = useTranslation();
  const marginal = useMemo(
    () =>
      pricing?.status === 'OPTIMAL'
        ? new Map((pricing.obligations ?? []).map((o) => [o.obligationId, o.marginalCostEur]))
        : undefined,
    [pricing],
  );
  const columns = useMemo<ColDef<ObligationValue>[]>(
    () => [
      {
        colId: 'obligation',
        headerName: t('allocation.value.obligation'),
        flex: 2,
        minWidth: 200,
        valueGetter: (p) => (p.data ? (obligationLabel(p.data.obligationId) ?? p.data.obligationId.slice(-8)) : ''),
        tooltipValueGetter: (p) => String(p.value ?? ''),
      },
      {
        field: 'giveAwayEur',
        headerName: t('allocation.value.giveAwayEur'),
        width: 140,
        type: 'rightAligned',
        valueFormatter: (p) => cents(p.value as number | null | undefined),
        cellStyle: { fontWeight: 600 },
      },
      {
        field: 'saleBucketCode',
        headerName: t('allocation.value.saleProduct'),
        width: 170,
        valueFormatter: (p) => (p.value as string | null | undefined) ?? t('allocation.value.noBucket'),
      },
      {
        field: 'saleMarkEur',
        headerName: t('allocation.value.saleMark'),
        width: 140,
        type: 'rightAligned',
        valueFormatter: (p) => cents(p.value as number | null | undefined),
      },
      {
        field: 'mwh',
        headerName: 'MWh',
        width: 100,
        type: 'rightAligned',
        valueFormatter: (p) => formatDecimal(p.value as number),
      },
      {
        field: 'averageDeliveredMarkEur',
        headerName: t('allocation.value.averageDelivered'),
        width: 160,
        type: 'rightAligned',
        valueFormatter: (p) => cents(p.value as number | null | undefined),
      },
      {
        field: 'deliveredValueEur',
        headerName: t('allocation.value.deliveredValueEur'),
        width: 150,
        type: 'rightAligned',
        valueFormatter: (p) => cents(p.value as number),
      },
      ...(marginal
        ? [
            {
              colId: 'marginalCost',
              headerName: t('allocation.value.marginalCost'),
              width: 170,
              type: 'rightAligned',
              valueGetter: (p) => (p.data ? marginal.get(p.data.obligationId) : undefined),
              valueFormatter: (p) => cents(p.value as number | null | undefined),
            } satisfies ColDef<ObligationValue>,
          ]
        : []),
    ],
    [t, obligationLabel, marginal],
  );
  const hint = (key: string) => t(`allocation.value.hints.${key}`);
  const figure = (amount: number) => <Money value={amount} fz="inherit" fw="inherit" />;
  return (
    <Stack gap="md">
      <SimpleGrid
        type="container"
        cols={{ base: 1, '440px': 2, '680px': 3, '1000px': 5 }}
        spacing="md"
        data-testid={kpiTestId}
      >
        <KpiCard
          label={t('allocation.value.totalCost')}
          value={figure(value.totalCostEur)}
          unit={value.currency}
          trend={hint('totalCost')}
        />
        <KpiCard
          label={t('allocation.value.deliveredValue')}
          value={figure(value.deliveredValueEur)}
          unit={value.currency}
          trend={hint('deliveredValue')}
        />
        <KpiCard
          label={t('allocation.value.giveAway')}
          value={figure(value.giveAwayEur)}
          unit={value.currency}
          trend={hint('giveAway')}
          trendTone={value.giveAwayEur > 0 ? 'warning' : 'neutral'}
        />
        <KpiCard
          label={t('allocation.value.expiryHaircut')}
          value={figure(value.expiryHaircutEur)}
          unit={value.currency}
          trend={hint('expiryHaircut')}
        />
        <KpiCard
          label={t('allocation.value.transferFees')}
          value={figure(value.transferFeesEur)}
          unit={value.currency}
          trend={hint('transferFees')}
        />
      </SimpleGrid>
      {value.unpricedMwh > 0 && (
        <Alert color="orange" data-testid="plan-value-unpriced">
          <Quantity value={value.unpricedMwh} unit="MWh" fw={600} /> {t('allocation.value.unpriced')}
        </Alert>
      )}
      {value.byObligation.length > 0 && (
        <Stack gap="xs">
          <Title order={4} size="h6">
            {t('allocation.value.giveAwayTitle')}
          </Title>
          <Text size="xs" c="dimmed">
            {t('allocation.value.giveAwayHint')}
          </Text>
          <div data-testid={gridTestId}>
            <DataGrid<ObligationValue>
              rowData={value.byObligation}
              columnDefs={columns}
              rowId={(row) => row.obligationId}
              fitRows={8}
              overlayNoRowsTemplate={t('allocation.value.noGiveAway')}
            />
          </div>
        </Stack>
      )}
    </Stack>
  );
}
