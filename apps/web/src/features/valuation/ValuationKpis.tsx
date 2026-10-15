import { SimpleGrid } from '@mantine/core';
import { useTranslation } from 'react-i18next';
import { $api } from '@/shared/api/client';
import { KpiCard } from '@/shared/components/KpiCard';
import { statusLabel } from '@/shared/components/StatusBadge';
import { formatDate, formatDecimal, formatMoney } from '@/shared/format/format';
import { useRecentRuns } from './hooks';

/** The latest official MtM and its change (the server's figures), runs waiting for sign-off, and exceptions. */
export function ValuationKpis({ entityId }: { entityId: string }) {
  const { t } = useTranslation();
  const summary = $api.useQuery('get', '/api/v1/home/summary', { params: { query: { legalEntityId: entityId } } });
  const runs = useRecentRuns(entityId);
  const valuation = summary.data?.valuation;
  const change = valuation?.change;
  const latest = [...(runs.data ?? [])].sort((a, b) => b.valuationDate.localeCompare(a.valuationDate))[0];
  const waiting = (runs.data ?? []).filter((run) => run.status === 'PENDING_SIGN_OFF' || run.status === 'DRAFT');
  return (
    <SimpleGrid cols={{ base: 1, xs: 2, lg: 4 }} spacing="md" data-testid="valuation-kpis">
      <KpiCard
        label={
          valuation ? t('valuation.kpi.mtm', { date: formatDate(valuation.valuationDate) }) : t('valuation.totalMtm')
        }
        value={valuation ? formatDecimal(valuation.totalMtm, 0, 0) : '—'}
        unit={valuation?.currency}
        loading={summary.isPending}
        trend={
          change === null || change === undefined
            ? t('valuation.kpi.firstRun')
            : `${change < 0 ? '▼' : '▲'} ${formatMoney(change)}`
        }
        trendTone={change === null || change === undefined ? 'neutral' : change < 0 ? 'down' : 'up'}
        spark={valuation?.history.map((point) => point.amount)}
        to="/valuation?tab=explain"
      />
      <KpiCard
        label={t('valuation.kpi.latestRun')}
        value={latest ? formatDate(latest.valuationDate) : '—'}
        loading={runs.isPending}
        trend={
          latest ? `${t(`valuation.kinds.${latest.kind}`)} · ${statusLabel(latest.status)}` : t('valuation.kpi.noRuns')
        }
        trendTone={latest?.status === 'OFFICIAL' ? 'up' : latest ? 'warning' : 'neutral'}
        to={latest ? `/valuation/runs/${latest.id}` : undefined}
      />
      <KpiCard
        label={t('valuation.kpi.waiting')}
        value={waiting.length}
        loading={runs.isPending}
        trend={waiting.length > 0 ? t('valuation.kpi.waitingHint') : t('valuation.kpi.allSignedOff')}
        trendTone={waiting.length > 0 ? 'warning' : 'up'}
      />
      <KpiCard
        label={t('valuation.kpi.exceptions')}
        value={latest ? latest.exceptions : '—'}
        loading={runs.isPending}
        trend={latest && latest.exceptions > 0 ? t('valuation.kpi.exceptionsHint') : t('valuation.kpi.noExceptions')}
        trendTone={latest && latest.exceptions > 0 ? 'warning' : 'up'}
        to={latest ? `/valuation/runs/${latest.id}` : undefined}
      />
    </SimpleGrid>
  );
}
