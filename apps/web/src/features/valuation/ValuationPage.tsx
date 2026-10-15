import { Alert, Stack, Tabs } from '@mantine/core';
import { useTranslation } from 'react-i18next';
import { PageHeader } from '@/shared/components/PageHeader';
import { useEntity } from '@/shared/context/EntityContext';
import { useUrlTab } from '@/shared/useUrlState';
import { Explain } from './Explain';
import { OfficialRun } from './OfficialRun';
import { Runs } from './Runs';
import { ValuationKpis } from './ValuationKpis';

/** Valuation runs of the selected entity, the official run as of a date and the P&L explain between two official runs. */
export function ValuationPage() {
  const { t } = useTranslation();
  const { entity } = useEntity();
  const [tab, setTab] = useUrlTab(['runs', 'official', 'explain'] as const, 'runs');
  return (
    <Stack>
      <PageHeader
        title={t('valuation.title')}
        description={t('valuation.description', { entity: entity?.code ?? '' })}
      />
      {!entity ? (
        <Alert color="gray">{t('header.noEntity')}</Alert>
      ) : (
        <>
          <ValuationKpis entityId={entity.id} />
          <Tabs value={tab} onChange={(value) => setTab(value)} keepMounted={false}>
            <Tabs.List>
              <Tabs.Tab value="runs">{t('valuation.runs')}</Tabs.Tab>
              <Tabs.Tab value="official" data-testid="tab-official">
                {t('valuation.official')}
              </Tabs.Tab>
              <Tabs.Tab value="explain">{t('valuation.explain')}</Tabs.Tab>
            </Tabs.List>
            <Tabs.Panel value="runs" pt="md">
              <Runs entityId={entity.id} />
            </Tabs.Panel>
            <Tabs.Panel value="official" pt="md">
              <OfficialRun entityId={entity.id} />
            </Tabs.Panel>
            <Tabs.Panel value="explain" pt="md">
              <Explain entityId={entity.id} />
            </Tabs.Panel>
          </Tabs>
        </>
      )}
    </Stack>
  );
}
