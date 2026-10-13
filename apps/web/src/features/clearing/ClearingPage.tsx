import { Alert, Select, SimpleGrid, Stack, Tabs } from '@mantine/core';
import { useMemo } from 'react';
import { useTranslation } from 'react-i18next';
import { $api } from '@/shared/api/client';
import { KpiCard } from '@/shared/components/KpiCard';
import { PageHeader } from '@/shared/components/PageHeader';
import { useEntity } from '@/shared/context/EntityContext';
import { formatDecimal, formatMoney } from '@/shared/format/format';
import { AccountsTab } from './AccountsTab';
import { BreaksTab } from './BreaksTab';
import { accountLabel, useClearingAccounts, useClearingHouses, useUrlParams } from './hooks';
import { MarginCallsTab } from './MarginCallsTab';
import { MarginTab } from './MarginTab';
import { StatementsTab } from './StatementsTab';
import { chooseAccount, clearingTab, isPositive, postedFor, type Account } from './model/clearing';

/**
 * The figures to act on first, for the chosen account: the initial margin held, the cash at the member, and the
 * open margin calls and breaks. The server gives the amounts; rows are counted here, money is never added up.
 */
function ClearingKpis({ entityId, account }: { entityId: string; account: Account | null }) {
  const { t } = useTranslation();
  const accountParam = account ? `&account=${account.id}` : '';
  const posted = $api.useQuery('get', '/api/v1/clearing/margin-posted', {
    params: { query: { legalEntityId: entityId } },
  });
  const breaks = $api.useQuery('get', '/api/v1/clearing/breaks', {
    params: { query: { legalEntityId: entityId, status: 'OPEN' } },
  });
  const calls = $api.useQuery('get', '/api/v1/clearing/margin-calls', {
    params: { query: { legalEntityId: entityId, status: 'OPEN' } },
  });
  const row = postedFor(posted.data ?? [], account?.id);
  const openBreaks = (breaks.data ?? []).filter((brk) => !account || brk.accountId === account.id).length;
  const openCalls = (calls.data ?? []).filter((call) => !account || call.accountId === account.id).length;
  const callsOwed = row ? isPositive(row.openCalls) : false;
  return (
    <SimpleGrid cols={{ base: 1, xs: 2, lg: 4 }} spacing="md" data-testid="clearing-kpis">
      <KpiCard
        testId="clearing-kpi-initial-margin"
        label={t('clearing.kpi.initialMargin')}
        value={row ? formatDecimal(row.initialMargin, 2, 2) : '—'}
        unit={row?.currency}
        loading={posted.isPending}
        trend={t('clearing.kpi.initialMarginHint')}
        to={`/clearing?tab=statements${accountParam}`}
      />
      <KpiCard
        testId="clearing-kpi-cash"
        label={t('clearing.kpi.cash')}
        value={row?.cashBalance != null ? formatDecimal(row.cashBalance, 2, 2) : '—'}
        unit={row?.cashBalance != null ? row.currency : undefined}
        loading={posted.isPending}
        trend={row?.cashBalance == null ? t('clearing.kpi.noStatement') : t('clearing.kpi.cashHint')}
        to={`/clearing?tab=statements${accountParam}`}
      />
      <KpiCard
        testId="clearing-kpi-calls"
        label={t('clearing.kpi.calls')}
        value={calls.isPending ? '—' : openCalls}
        loading={calls.isPending}
        trend={
          callsOwed && row
            ? t('clearing.kpi.callsOwed', { amount: formatMoney(row.openCalls, row.currency) })
            : t('clearing.kpi.noCalls')
        }
        trendTone={callsOwed ? 'down' : 'up'}
        to={`/clearing?tab=margin-calls${accountParam}`}
      />
      <KpiCard
        testId="clearing-kpi-breaks"
        label={t('clearing.kpi.breaks')}
        value={breaks.isPending ? '—' : openBreaks}
        loading={breaks.isPending}
        trend={openBreaks > 0 ? t('clearing.kpi.breaksHint') : t('clearing.kpi.noBreaks')}
        trendTone={openBreaks > 0 ? 'warning' : 'up'}
        to={`/clearing?tab=breaks${accountParam}`}
      />
    </SimpleGrid>
  );
}

/**
 * Margining of the entity's EUA futures at its clearing houses: accounts, the daily margin we compute, the member's
 * statements, the breaks between the two and the margin calls (ADR 0046).
 */
export function ClearingPage() {
  const { t } = useTranslation();
  const { entity } = useEntity();
  const { params, setParams, set } = useUrlParams();
  const tab = clearingTab(params.get('tab'));
  const accounts = useClearingAccounts(entity?.id);
  const { byId } = useClearingHouses();
  const list = useMemo(() => accounts.data ?? [], [accounts.data]);
  const account = chooseAccount(list, params.get('account'));
  const posted = $api.useQuery(
    'get',
    '/api/v1/clearing/margin-posted',
    { params: { query: { legalEntityId: entity?.id ?? '' } } },
    { enabled: Boolean(entity) },
  );
  const selectAccount = (id: string | null) => set({ account: id });
  return (
    <Stack gap="lg" data-testid="clearing-page">
      <PageHeader
        title={t('clearing.title')}
        description={t('clearing.description', { entity: entity?.code ?? '' })}
        actions={
          list.length > 1 ? (
            <Select
              aria-label={t('clearing.account')}
              data={list.map((candidate) => ({
                value: candidate.id,
                label: accountLabel(candidate, byId.get(candidate.clearingHouseId)?.name),
              }))}
              value={account?.id ?? null}
              onChange={selectAccount}
              allowDeselect={false}
              w={300}
              data-testid="clearing-account-select"
            />
          ) : undefined
        }
      />
      {!entity ? (
        <Alert color="gray">{t('header.noEntity')}</Alert>
      ) : (
        <>
          <ClearingKpis entityId={entity.id} account={account} />
          <Tabs
            value={tab}
            onChange={(value) =>
              setParams(
                (current) => {
                  const next = new URLSearchParams();
                  if (value && value !== 'accounts') next.set('tab', value);
                  const keep = current.get('account');
                  if (keep) next.set('account', keep);
                  return next;
                },
                { replace: true },
              )
            }
            keepMounted={false}
          >
            <Tabs.List>
              {(['accounts', 'margin', 'statements', 'breaks', 'margin-calls'] as const).map((value) => (
                <Tabs.Tab key={value} value={value} data-testid={`clearing-tab-${value}`}>
                  {t(`clearing.tabs.${value}`)}
                </Tabs.Tab>
              ))}
            </Tabs.List>
            <Tabs.Panel value="accounts" pt="md">
              <AccountsTab
                entity={entity}
                accounts={list}
                loading={accounts.isPending}
                posted={posted.data ?? []}
                selectedId={account?.id ?? null}
                onSelect={selectAccount}
              />
            </Tabs.Panel>
            <Tabs.Panel value="margin" pt="md">
              <MarginTab account={account} />
            </Tabs.Panel>
            <Tabs.Panel value="statements" pt="md">
              <StatementsTab account={account} entityId={entity.id} />
            </Tabs.Panel>
            <Tabs.Panel value="breaks" pt="md">
              <BreaksTab entityId={entity.id} account={account} />
            </Tabs.Panel>
            <Tabs.Panel value="margin-calls" pt="md">
              <MarginCallsTab entityId={entity.id} account={account} />
            </Tabs.Panel>
          </Tabs>
        </>
      )}
    </Stack>
  );
}
