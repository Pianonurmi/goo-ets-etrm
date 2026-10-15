import { Group, NumberInput, SimpleGrid, Stack, Tabs } from '@mantine/core';
import { IconCalendar } from '@tabler/icons-react';
import { useTranslation } from 'react-i18next';
import { useParams } from 'react-router';
import { $api } from '@/shared/api/client';
import { DetailLayout } from '@/shared/components/DetailLayout';
import { KpiCard } from '@/shared/components/KpiCard';
import { PageHeader } from '@/shared/components/PageHeader';
import { QueryState } from '@/shared/components/QueryState';
import { CardSkeleton } from '@/shared/components/Skeletons';
import { StatusBadge } from '@/shared/components/StatusBadge';
import { formatDate, formatDecimal } from '@/shared/format/format';
import { oneOf, yearOr } from '@/shared/urlParams';
import { useUrlState } from '@/shared/useUrlState';
import { EmissionsTab } from './EmissionsTab';
import { ComplianceStatusBadge } from './EtsPage';
import { FreeAllocationTab } from './FreeAllocationTab';
import { SurrendersTab } from './SurrendersTab';

/** One installation: the year's compliance position, master data, emissions, free allocation and surrenders. */
export function InstallationPage() {
  const { t } = useTranslation();
  const { installationId = '' } = useParams();
  const { params, set } = useUrlState();
  const year = yearOr(params.get('year'), new Date().getFullYear());
  const tab = oneOf(params.get('tab'), ['emissions', 'free-allocation', 'surrenders'] as const, 'emissions');
  const installation = $api.useQuery('get', '/api/v1/ets/installations/{id}', {
    params: { path: { id: installationId } },
  });
  const compliance = $api.useQuery('get', '/api/v1/ets/installations/{id}/compliance/{year}', {
    params: { path: { id: installationId, year } },
  });
  const setParam = (key: string, value: string | null) => set({ [key]: value });
  const breadcrumbs = [{ label: t('ets.title'), to: `/ets?year=${year}` }];
  const data = installation.data;
  if (!data) {
    return (
      <Stack gap={0}>
        <PageHeader breadcrumbs={breadcrumbs} title={t('positions.installation')} />
        <QueryState isPending={false} error={installation.error}>
          <CardSkeleton height={260} />
        </QueryState>
      </Stack>
    );
  }
  const position = compliance.data;
  return (
    <Stack>
      <PageHeader
        breadcrumbs={breadcrumbs}
        title={data.name}
        badge={
          <Group gap={6} wrap="nowrap">
            {position && <ComplianceStatusBadge status={position.status} />}
            <StatusBadge status={data.active ? 'KIND' : 'INACTIVE'} label={t(`ets.kinds.${data.kind}`)} />
          </Group>
        }
        description={`${data.eutlId} · ${data.country}${data.activityCode ? ` · ${t('ets.activityCode')} ${data.activityCode}` : ''}`}
        actions={
          <NumberInput
            aria-label={t('positions.complianceYear')}
            leftSection={<IconCalendar size={16} />}
            value={year}
            onChange={(value) => setParam('year', Number(value) ? String(value) : null)}
            w={130}
            min={2005}
          />
        }
      />
      <QueryState isPending={false} error={compliance.error}>
        <SimpleGrid cols={{ base: 1, xs: 2, lg: 4 }} spacing="md" data-testid="installation-kpis">
          <KpiCard
            label={t('ets.outstanding')}
            value={position ? formatDecimal(position.outstanding) : '—'}
            unit="t"
            loading={!position}
            trend={position && t('ets.kpi.surrenderBy', { date: formatDate(position.surrenderDeadline) })}
            trendTone={position && position.outstanding > 0 ? 'warning' : 'neutral'}
          />
          <KpiCard
            label={t('positions.held')}
            value={position ? formatDecimal(position.held) : '—'}
            unit="t"
            loading={!position}
            trend={position && t('ets.kpi.surrendered', { tonnes: formatDecimal(position.surrendered) })}
          />
          <KpiCard
            label={t('ets.net')}
            value={position ? formatDecimal(position.net) : '—'}
            unit="t"
            loading={!position}
            trend={position && t(position.net < 0 ? 'ets.kpi.short' : 'ets.kpi.covered')}
            trendTone={position ? (position.net < 0 ? 'down' : 'up') : 'neutral'}
          />
          <KpiCard
            label={t('positions.emissions')}
            value={position ? formatDecimal(position.emissions) : '—'}
            unit="t"
            loading={!position}
            trend={position && t(`ets.basis.${position.emissionsBasis}`)}
          />
        </SimpleGrid>
      </QueryState>
      <DetailLayout
        summary={[
          { label: t('ets.eutlId'), value: data.eutlId },
          { label: t('ets.permitId'), value: data.permitId ?? '–' },
          { label: t('ets.eligibleProducts'), value: data.eligibleProducts.join(', ') },
          {
            label: t('risk.validity'),
            value: `${formatDate(data.validFrom)} – ${data.validTo ? formatDate(data.validTo) : '∞'}`,
          },
          {
            label: t('ets.monitoringPlan'),
            value: [data.monitoringPlanRef, data.monitoringPlanVersion].filter(Boolean).join(' v') || '–',
          },
        ]}
      >
        <Tabs
          value={tab}
          onChange={(value) => setParam('tab', value === 'emissions' ? null : value)}
          keepMounted={false}
        >
          <Tabs.List>
            <Tabs.Tab value="emissions">{t('ets.tabs.emissions')}</Tabs.Tab>
            <Tabs.Tab value="free-allocation">{t('ets.tabs.freeAllocation')}</Tabs.Tab>
            <Tabs.Tab value="surrenders">{t('ets.tabs.surrenders')}</Tabs.Tab>
          </Tabs.List>
          <Tabs.Panel value="emissions" pt="md">
            <EmissionsTab installationId={installationId} year={year} />
          </Tabs.Panel>
          <Tabs.Panel value="free-allocation" pt="md">
            <FreeAllocationTab installationId={installationId} year={year} />
          </Tabs.Panel>
          <Tabs.Panel value="surrenders" pt="md">
            <SurrendersTab installation={data} year={year} />
          </Tabs.Panel>
        </Tabs>
      </DetailLayout>
    </Stack>
  );
}
