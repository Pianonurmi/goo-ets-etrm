import { SimpleGrid } from '@mantine/core';
import { useTranslation } from 'react-i18next';
import { $api } from '@/shared/api/client';
import { KpiCard } from '@/shared/components/KpiCard';
import { formatDecimal } from '@/shared/format/format';
import { useUtilisations } from './hooks';

/**
 * Limits in breach and near their warning level (the latest measurements), the highest utilisation, and open breaches;
 * the counts come from the rows, the percentages from the server.
 */
export function RiskKpis({ entityId }: { entityId: string }) {
  const { t } = useTranslation();
  const limits = $api.useQuery('get', '/api/v1/risk/limits', {
    params: { query: { legalEntityId: entityId, activeOnly: true } },
  });
  const breaches = $api.useQuery('get', '/api/v1/risk/breaches', {
    params: { query: { legalEntityId: entityId, openOnly: true } },
  });
  const active = limits.data ?? [];
  const utilisations = useUtilisations(active);
  const measured = active.flatMap((limit) => {
    const current = utilisations.get(limit.id);
    return current ? [{ limit, current }] : [];
  });
  const inBreach = measured.filter(
    (row) => row.current.status === 'BREACH' || row.current.status === 'NOT_MEASURED',
  ).length;
  const warning = measured.filter((row) => row.current.status === 'WARNING').length;
  const highest = measured.reduce<(typeof measured)[number] | undefined>(
    (top, row) => (!top || row.current.utilisation > top.current.utilisation ? row : top),
    undefined,
  );
  const open = breaches.data?.length ?? 0;
  return (
    <SimpleGrid cols={{ base: 1, xs: 2, lg: 4 }} spacing="md" data-testid="risk-kpis">
      <KpiCard
        label={t('risk.kpi.inBreach')}
        value={inBreach}
        loading={limits.isPending}
        trend={inBreach > 0 ? t('risk.kpi.inBreachHint') : t('risk.kpi.withinLimits')}
        trendTone={inBreach > 0 ? 'down' : 'up'}
      />
      <KpiCard
        label={t('risk.kpi.warning')}
        value={warning}
        loading={limits.isPending}
        trend={t('risk.kpi.warningHint')}
        trendTone={warning > 0 ? 'warning' : 'neutral'}
      />
      <KpiCard
        label={t('risk.kpi.highest')}
        value={highest ? formatDecimal(highest.current.utilisation, 0, 1) : '—'}
        unit={highest ? '%' : undefined}
        loading={limits.isPending}
        trend={
          highest
            ? `${t(`risk.types.${highest.limit.type}`)} · ${t('risk.kpi.measuredOf', { count: measured.length, total: active.length })}`
            : t('risk.notMeasured')
        }
        trendTone={
          highest
            ? highest.current.status === 'OK'
              ? 'up'
              : highest.current.status === 'WARNING'
                ? 'warning'
                : 'down'
            : 'neutral'
        }
        to={highest ? `/risk/limits/${highest.limit.id}` : undefined}
      />
      <KpiCard
        label={t('risk.kpi.openBreaches')}
        value={open}
        loading={breaches.isPending}
        trend={open > 0 ? t('risk.kpi.review') : t('risk.kpi.noBreaches')}
        trendTone={open > 0 ? 'down' : 'up'}
        to="/risk?tab=breaches"
      />
    </SimpleGrid>
  );
}
