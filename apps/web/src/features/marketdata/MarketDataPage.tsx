import { SimpleGrid, Stack, Tabs } from '@mantine/core';
import { useTranslation } from 'react-i18next';
import { $api } from '@/shared/api/client';
import { KpiCard } from '@/shared/components/KpiCard';
import { PageHeader } from '@/shared/components/PageHeader';
import { useUrlTab } from '@/shared/useUrlState';
import { FxTab } from './FxTab';
import { ImportsTab } from './ImportsTab';
import { IndicesTab } from './IndicesTab';
import { InstrumentsTab } from './InstrumentsTab';
import { QuotesTab } from './QuotesTab';
import { SuspectTab } from './SuspectTab';

/** Instruments and aliases, quotes (with suspect ones to review), FX rates, price indices, file imports and formats. */
export function MarketDataPage() {
  const { t } = useTranslation();
  const [tab, setTab] = useUrlTab(['quotes', 'suspect', 'fx', 'instruments', 'indices', 'imports'] as const, 'quotes');
  const suspect = $api.useQuery('get', '/api/v1/market-data/quotes/suspect', {});
  const instruments = $api.useQuery('get', '/api/v1/market-data/instruments', {});
  const curves = $api.useQuery('get', '/api/v1/market-data/curves', { params: { query: { activeOnly: true } } });
  const flagged = suspect.data?.length ?? 0;
  return (
    <Stack>
      <PageHeader title={t('marketData.title')} description={t('marketData.description')} />
      <SimpleGrid cols={{ base: 1, xs: 3 }} spacing="md" data-testid="market-data-kpis">
        <KpiCard
          label={t('marketData.kpi.suspect')}
          value={flagged}
          loading={suspect.isPending}
          trend={flagged > 0 ? t('marketData.kpi.suspectHint') : t('marketData.noSuspect')}
          trendTone={flagged > 0 ? 'warning' : 'up'}
          to="/market-data?tab=suspect"
        />
        <KpiCard
          label={t('marketData.kpi.instruments')}
          value={(instruments.data ?? []).filter((instrument) => instrument.active).length}
          loading={instruments.isPending}
          trend={t('marketData.kpi.instrumentsHint')}
          to="/market-data?tab=instruments"
        />
        <KpiCard
          label={t('marketData.kpi.curves')}
          value={curves.data?.length ?? '—'}
          loading={curves.isPending}
          trend={t('marketData.kpi.curvesHint')}
          to="/market-data/curves"
        />
      </SimpleGrid>
      <Tabs value={tab} onChange={(value) => setTab(value)} keepMounted={false}>
        <Tabs.List>
          <Tabs.Tab value="quotes">{t('marketData.tabs.quotes')}</Tabs.Tab>
          <Tabs.Tab value="suspect">{t('marketData.tabs.suspect')}</Tabs.Tab>
          <Tabs.Tab value="fx">{t('marketData.tabs.fx')}</Tabs.Tab>
          <Tabs.Tab value="instruments">{t('marketData.tabs.instruments')}</Tabs.Tab>
          <Tabs.Tab value="indices">{t('marketData.tabs.indices')}</Tabs.Tab>
          <Tabs.Tab value="imports">{t('marketData.tabs.imports')}</Tabs.Tab>
        </Tabs.List>
        <Tabs.Panel value="quotes" pt="md">
          <QuotesTab />
        </Tabs.Panel>
        <Tabs.Panel value="suspect" pt="md">
          <SuspectTab />
        </Tabs.Panel>
        <Tabs.Panel value="fx" pt="md">
          <FxTab />
        </Tabs.Panel>
        <Tabs.Panel value="instruments" pt="md">
          <InstrumentsTab />
        </Tabs.Panel>
        <Tabs.Panel value="indices" pt="md">
          <IndicesTab />
        </Tabs.Panel>
        <Tabs.Panel value="imports" pt="md">
          <ImportsTab />
        </Tabs.Panel>
      </Tabs>
    </Stack>
  );
}
