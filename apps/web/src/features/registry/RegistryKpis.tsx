import { SimpleGrid } from '@mantine/core';
import { useTranslation } from 'react-i18next';
import { $api } from '@/shared/api/client';
import { KpiCard } from '@/shared/components/KpiCard';
import { queueStatuses, type InstructionStatus } from './model/instructions';

/** The figures read a page of their own: a plain query must not share the infinite list's cache entry. */
export const KPI_PAGE = 200;

export const SENT: InstructionStatus[] = ['DISPATCHING', 'SENT', 'ACKNOWLEDGED'];

/** Open work in the registry queue, counted from the open and the attention views (first page of each). */
export function RegistryKpis({ entityId }: { entityId: string }) {
  const { t } = useTranslation();
  const query = (view: 'open' | 'attention') => ({
    params: { query: { holderEntityId: entityId, status: queueStatuses(view), limit: KPI_PAGE } },
  });
  const open = $api.useQuery('get', '/api/v1/registry/instructions/search', query('open'));
  const attention = $api.useQuery('get', '/api/v1/registry/instructions/search', query('attention'));
  const shown = (count: number, more: boolean) => (more ? `${count}+` : count);
  const items = open.data?.items ?? [];
  const more = Boolean(open.data?.nextCursor);
  const pending = items.filter((row) => row.status === 'PENDING_APPROVAL').length;
  const sent = items.filter((row) => SENT.includes(row.status)).length;
  const problems = attention.data?.items.length ?? 0;
  return (
    <SimpleGrid cols={{ base: 1, xs: 2, lg: 4 }} spacing="md" data-testid="registry-kpis">
      <KpiCard
        label={t('registry.kpi.open')}
        value={shown(items.length, more)}
        loading={open.isPending}
        trend={t('registry.kpi.openHint')}
        to="/registry"
      />
      <KpiCard
        label={t('registry.kpi.pending')}
        value={shown(pending, more)}
        loading={open.isPending}
        trend={pending > 0 ? t('registry.kpi.pendingHint') : t('registry.kpi.nothingPending')}
        trendTone={pending > 0 ? 'warning' : 'neutral'}
      />
      <KpiCard
        label={t('registry.kpi.sent')}
        value={shown(sent, more)}
        loading={open.isPending}
        trend={t('registry.kpi.sentHint')}
      />
      <KpiCard
        label={t('registry.kpi.attention')}
        value={shown(problems, Boolean(attention.data?.nextCursor))}
        loading={attention.isPending}
        trend={problems > 0 ? t('registry.kpi.attentionHint') : t('registry.kpi.nothingWrong')}
        trendTone={problems > 0 ? 'down' : 'up'}
        to="/registry?view=attention"
      />
    </SimpleGrid>
  );
}
