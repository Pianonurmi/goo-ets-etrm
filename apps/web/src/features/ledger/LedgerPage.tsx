import { Alert, Stack, Tabs } from '@mantine/core';
import { useTranslation } from 'react-i18next';
import { PageHeader } from '@/shared/components/PageHeader';
import { useEntity } from '@/shared/context/EntityContext';
import { useUrlTab } from '@/shared/useUrlState';
import { AccountsTab } from './AccountsTab';
import { EntriesTab } from './EntriesTab';
import { LedgerKpis } from './LedgerKpis';
import { RulesTab } from './RulesTab';
import { TrialBalanceTab } from './TrialBalanceTab';

const TABS = ['entries', 'trial-balance', 'accounts', 'rules'] as const;

/** The journal of the selected entity: entries, trial balance, chart of accounts and posting rules. */
export function LedgerPage() {
  const { t } = useTranslation();
  const { entity } = useEntity();
  const [tab, setTab] = useUrlTab(TABS, 'entries');
  return (
    <Stack>
      <PageHeader title={t('ledger.title')} description={t('ledger.description', { entity: entity?.code ?? '' })} />
      {!entity ? (
        <Alert color="gray">{t('header.noEntity')}</Alert>
      ) : (
        <>
          <LedgerKpis entityId={entity.id} />
          <Tabs value={tab} onChange={(value) => setTab(value)} keepMounted={false}>
            <Tabs.List>
              <Tabs.Tab value="entries">{t('ledger.tabs.entries')}</Tabs.Tab>
              <Tabs.Tab value="trial-balance">{t('ledger.tabs.trialBalance')}</Tabs.Tab>
              <Tabs.Tab value="accounts">{t('ledger.tabs.accounts')}</Tabs.Tab>
              <Tabs.Tab value="rules">{t('ledger.tabs.rules')}</Tabs.Tab>
            </Tabs.List>
            <Tabs.Panel value="entries" pt="md">
              <EntriesTab entityId={entity.id} />
            </Tabs.Panel>
            <Tabs.Panel value="trial-balance" pt="md">
              <TrialBalanceTab entityId={entity.id} />
            </Tabs.Panel>
            <Tabs.Panel value="accounts" pt="md">
              <AccountsTab entityId={entity.id} />
            </Tabs.Panel>
            <Tabs.Panel value="rules" pt="md">
              <RulesTab entityId={entity.id} />
            </Tabs.Panel>
          </Tabs>
        </>
      )}
    </Stack>
  );
}
