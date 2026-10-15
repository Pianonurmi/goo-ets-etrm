import { Alert, Stack, Tabs, Text } from '@mantine/core';
import { IconShieldCheck } from '@tabler/icons-react';
import { useTranslation } from 'react-i18next';
import { PageHeader } from '@/shared/components/PageHeader';
import { useUrlTab } from '@/shared/useUrlState';
import { CounterpartiesTab } from './reference/CounterpartiesTab';
import { DevicesTab } from './reference/DevicesTab';
import { EntitiesTab } from './reference/EntitiesTab';
import { RegistriesTab } from './reference/RegistriesTab';

const TABS = ['entities', 'counterparties', 'registries', 'devices'] as const;

/** Legal entities, counterparties, registries and devices, with their history and four-eyes changes. */
export function ReferenceDataPage() {
  const { t } = useTranslation();
  const [tab, setTab] = useUrlTab(TABS, 'entities');
  return (
    <Stack>
      <PageHeader title={t('admin.referenceTitle')} description={t('admin.referenceDescription')} />
      <Alert color="blue" variant="light" icon={<IconShieldCheck size={18} />} py="xs">
        <Text size="sm">{t('admin.fourEyesNote')}</Text>
      </Alert>
      <Tabs value={tab} onChange={(value) => setTab(value)} keepMounted={false}>
        <Tabs.List>
          <Tabs.Tab value="entities">{t('admin.tabs.entities')}</Tabs.Tab>
          <Tabs.Tab value="counterparties">{t('admin.tabs.counterparties')}</Tabs.Tab>
          <Tabs.Tab value="registries">{t('admin.tabs.registries')}</Tabs.Tab>
          <Tabs.Tab value="devices">{t('admin.tabs.devices')}</Tabs.Tab>
        </Tabs.List>
        <Tabs.Panel value="entities" pt="md">
          <EntitiesTab />
        </Tabs.Panel>
        <Tabs.Panel value="counterparties" pt="md">
          <CounterpartiesTab />
        </Tabs.Panel>
        <Tabs.Panel value="registries" pt="md">
          <RegistriesTab />
        </Tabs.Panel>
        <Tabs.Panel value="devices" pt="md">
          <DevicesTab />
        </Tabs.Panel>
      </Tabs>
    </Stack>
  );
}
