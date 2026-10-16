import { Alert, Button, Group, Stack, Tabs } from '@mantine/core';
import { IconArrowsTransferUp, IconCoins } from '@tabler/icons-react';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Can } from '@/shared/auth/Can';
import { PageHeader } from '@/shared/components/PageHeader';
import { useEntity } from '@/shared/context/EntityContext';
import { useUrlTab } from '@/shared/useUrlState';
import { AllowanceTransferModal } from './AllowanceTransferModal';
import { FormatsTab } from './FormatsTab';
import { PausedRegistries } from './PausedRegistries';
import { InstructionsTab } from './InstructionsTab';
import { RegistryKpis } from './RegistryKpis';
import { RoutesTab } from './RoutesTab';
import { TransferModal } from './TransferModal';

const TABS = ['instructions', 'routes', 'formats'] as const;

/** Registry instructions of the selected entity, transfer routes between registries, and statement formats. */
export function RegistryPage() {
  const { t } = useTranslation();
  const { entity } = useEntity();
  const [tab, setTab] = useUrlTab(TABS, 'instructions');
  const [transferring, setTransferring] = useState<'goo' | 'allowances' | null>(null);
  return (
    <Stack gap="lg">
      <PageHeader
        title={t('registry.title')}
        description={t('registry.description', { entity: entity?.code ?? '' })}
        actions={
          entity && (
            <Group gap="xs">
              <Can op="registry_allowanceTransfer">
                <Button
                  variant="default"
                  leftSection={<IconCoins size={16} />}
                  onClick={() => setTransferring('allowances')}
                  data-testid="new-allowance-transfer"
                >
                  {t('registry.allowanceTransfer')}
                </Button>
              </Can>
              <Can op="registry_transfer">
                <Button
                  leftSection={<IconArrowsTransferUp size={16} />}
                  onClick={() => setTransferring('goo')}
                  data-testid="new-transfer"
                >
                  {t('registry.newTransfer')}
                </Button>
              </Can>
            </Group>
          )
        }
      />
      {!entity ? (
        <Alert color="gray">{t('header.noEntity')}</Alert>
      ) : (
        <>
          <PausedRegistries />
          <RegistryKpis entityId={entity.id} />
          <Tabs value={tab} onChange={(value) => setTab(value)} keepMounted={false}>
            <Tabs.List>
              <Tabs.Tab value="instructions">{t('registry.tabs.instructions')}</Tabs.Tab>
              <Tabs.Tab value="routes">{t('registry.tabs.routes')}</Tabs.Tab>
              <Tabs.Tab value="formats">{t('registry.tabs.formats')}</Tabs.Tab>
            </Tabs.List>
            <Tabs.Panel value="instructions" pt="md">
              <InstructionsTab entityId={entity.id} />
            </Tabs.Panel>
            <Tabs.Panel value="routes" pt="md">
              <RoutesTab />
            </Tabs.Panel>
            <Tabs.Panel value="formats" pt="md">
              <FormatsTab />
            </Tabs.Panel>
          </Tabs>
        </>
      )}
      {transferring === 'goo' && entity && <TransferModal entityId={entity.id} onClose={() => setTransferring(null)} />}
      {transferring === 'allowances' && entity && (
        <AllowanceTransferModal entityId={entity.id} onClose={() => setTransferring(null)} />
      )}
    </Stack>
  );
}
