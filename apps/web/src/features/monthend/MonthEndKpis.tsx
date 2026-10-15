import { SimpleGrid } from '@mantine/core';
import { useTranslation } from 'react-i18next';
import { $api } from '@/shared/api/client';
import { useCan } from '@/shared/auth/Can';
import { KpiCard } from '@/shared/components/KpiCard';
import { statusLabel } from '@/shared/components/StatusBadge';
import { formatDateTime } from '@/shared/format/format';

/**
 * Where the close stands for the period: its status, what blocks the hard close, the latest period-end measurement
 * and the latest GL export. The queries are the ones the tabs below use, so nothing is fetched twice.
 */
export function MonthEndKpis({ entityId, period }: { entityId: string; period: string }) {
  const { t } = useTranslation();
  const canSeeBlockers = useCan('ledger_hardCloseBlockers');
  const canReadExports = useCan('ledger_exports');
  const periods = $api.useQuery('get', '/api/v1/ledger/periods', { params: { query: { legalEntityId: entityId } } });
  const blockers = $api.useQuery(
    'get',
    '/api/v1/ledger/periods/{period}/hard-close-blockers',
    { params: { path: { period }, query: { legalEntityId: entityId } } },
    { enabled: canSeeBlockers },
  );
  const runs = $api.useQuery('get', '/api/v1/ledger/period-end/runs', {
    params: { query: { legalEntityId: entityId, period } },
  });
  const exports = $api.useQuery(
    'get',
    '/api/v1/ledger/exports',
    { params: { query: { legalEntityId: entityId, period } } },
    { enabled: canReadExports },
  );
  const status = (periods.data ?? []).find((p) => p.period === period)?.status ?? 'OPEN';
  const open = blockers.data?.length ?? 0;
  const latestRun = [...(runs.data ?? [])].sort((a, b) => b.seq - a.seq)[0];
  const latestExport = [...(exports.data ?? [])].sort((a, b) => b.exportSeq - a.exportSeq)[0];
  return (
    <SimpleGrid cols={{ base: 1, xs: 2, lg: 4 }} spacing="md" data-testid="month-end-kpis">
      <KpiCard
        label={t('monthEnd.kpi.status', { period })}
        value={statusLabel(status)}
        loading={periods.isPending}
        trend={t(`monthEnd.kpi.next.${status}`)}
        trendTone={status === 'CLOSED' ? 'up' : 'neutral'}
      />
      <KpiCard
        label={t('monthEnd.kpi.blockers')}
        value={canSeeBlockers ? open : '—'}
        loading={canSeeBlockers && blockers.isPending}
        trend={
          status === 'CLOSED'
            ? t('monthEnd.hardClosed')
            : open > 0
              ? t('monthEnd.kpi.blockersHint')
              : t('monthEnd.readyToClose')
        }
        trendTone={status !== 'CLOSED' && open > 0 ? 'warning' : 'up'}
      />
      <KpiCard
        label={t('monthEnd.periodEnd')}
        value={latestRun ? t('monthEnd.kpi.run', { seq: latestRun.seq }) : '—'}
        loading={runs.isPending}
        trend={latestRun ? t('monthEnd.kpi.warnings', { count: latestRun.warnings.length }) : t('monthEnd.noRuns')}
        trendTone={latestRun && latestRun.warnings.length > 0 ? 'warning' : 'neutral'}
      />
      <KpiCard
        label={t('monthEnd.kpi.export')}
        value={latestExport ? statusLabel(latestExport.status ?? 'GENERATED') : '—'}
        loading={canReadExports && exports.isPending}
        trend={
          latestExport
            ? t('monthEnd.kpi.exportOf', { seq: latestExport.exportSeq, at: formatDateTime(latestExport.exportedAt) })
            : t('monthEnd.kpi.noExport')
        }
        trendTone={
          latestExport?.status === 'ACKNOWLEDGED' ? 'up' : latestExport?.status === 'REJECTED' ? 'down' : 'neutral'
        }
        to="/month-end?tab=gl"
      />
    </SimpleGrid>
  );
}
