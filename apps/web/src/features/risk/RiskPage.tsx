import { Alert, Stack, Tabs } from '@mantine/core';
import { useTranslation } from 'react-i18next';
import { PageHeader } from '@/shared/components/PageHeader';
import { useEntity } from '@/shared/context/EntityContext';
import { useUrlTab } from '@/shared/useUrlState';
import { BreachesTab } from './BreachesTab';
import { CreditSupportTab } from './CreditSupportTab';
import { ExposuresTab } from './ExposuresTab';
import { LimitsTab } from './LimitsTab';
import { PfeTab } from './PfeTab';
import { RiskKpis } from './RiskKpis';

const TABS = ['limits', 'exposures', 'breaches', 'credit-support', 'pfe'] as const;

/** Limits and their utilisation, counterparty exposure, breaches, credit support and PFE add-ons of an entity. */
export function RiskPage() {
  const { t } = useTranslation();
  const { entity } = useEntity();
  const [tab, setTab] = useUrlTab(TABS, 'limits');
  return (
    <Stack>
      <PageHeader title={t('risk.title')} description={t('risk.description', { entity: entity?.code ?? '' })} />
      {!entity ? (
        <Alert color="gray">{t('header.noEntity')}</Alert>
      ) : (
        <>
          <RiskKpis entityId={entity.id} />
          <Tabs value={tab} onChange={(value) => setTab(value)} keepMounted={false}>
            <Tabs.List>
              <Tabs.Tab value="limits">{t('risk.tabs.limits')}</Tabs.Tab>
              <Tabs.Tab value="exposures">{t('risk.tabs.exposures')}</Tabs.Tab>
              <Tabs.Tab value="breaches">{t('risk.tabs.breaches')}</Tabs.Tab>
              <Tabs.Tab value="credit-support">{t('risk.tabs.creditSupport')}</Tabs.Tab>
              <Tabs.Tab value="pfe">{t('risk.tabs.pfe')}</Tabs.Tab>
            </Tabs.List>
            <Tabs.Panel value="limits" pt="md">
              <LimitsTab entityId={entity.id} />
            </Tabs.Panel>
            <Tabs.Panel value="exposures" pt="md">
              <ExposuresTab entityId={entity.id} />
            </Tabs.Panel>
            <Tabs.Panel value="breaches" pt="md">
              <BreachesTab entityId={entity.id} />
            </Tabs.Panel>
            <Tabs.Panel value="credit-support" pt="md">
              <CreditSupportTab entityId={entity.id} />
            </Tabs.Panel>
            <Tabs.Panel value="pfe" pt="md">
              <PfeTab />
            </Tabs.Panel>
          </Tabs>
        </>
      )}
    </Stack>
  );
}
