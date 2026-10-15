import { SimpleGrid } from '@mantine/core';
import { useTranslation } from 'react-i18next';
import { $api } from '@/shared/api/client';
import { KpiCard } from '@/shared/components/KpiCard';
import { statusLabel } from '@/shared/components/StatusBadge';
import { useAccounts } from './hooks';

/** The open period and what blocks closing it, posting rules waiting for approval, and the chart of accounts. */
export function LedgerKpis({ entityId }: { entityId: string }) {
  const { t } = useTranslation();
  const summary = $api.useQuery('get', '/api/v1/home/summary', { params: { query: { legalEntityId: entityId } } });
  const changes = $api.useQuery('get', '/api/v1/ledger/rules/changes', { params: { query: { status: 'PENDING' } } });
  const accounts = useAccounts(entityId);
  const ledger = summary.data?.ledger;
  const pending = changes.data?.length ?? 0;
  return (
    <SimpleGrid cols={{ base: 1, xs: 2, lg: 3 }} spacing="md" data-testid="ledger-kpis">
      <KpiCard
        label={t('ledger.kpi.period')}
        value={ledger?.period ?? '—'}
        loading={summary.isPending}
        trend={
          ledger && `${statusLabel(ledger.status)} · ${t('ledger.kpi.blockers', { count: ledger.blockers.length })}`
        }
        trendTone={ledger && ledger.blockers.length > 0 ? 'warning' : 'neutral'}
        to="/month-end"
      />
      <KpiCard
        label={t('ledger.kpi.pendingRules')}
        value={pending}
        loading={changes.isPending}
        trend={pending > 0 ? t('ledger.kpi.pendingRulesHint') : t('ledger.kpi.noPendingRules')}
        trendTone={pending > 0 ? 'warning' : 'neutral'}
        to="/ledger?tab=rules"
      />
      <KpiCard
        label={t('ledger.kpi.accounts')}
        value={(accounts.data ?? []).filter((account) => account.active).length}
        loading={accounts.isPending}
        trend={t('ledger.kpi.accountsHint')}
        to="/ledger?tab=accounts"
      />
    </SimpleGrid>
  );
}
