import { Alert, Stack, Tabs, TextInput } from '@mantine/core';
import { IconCalendar } from '@tabler/icons-react';
import { useTranslation } from 'react-i18next';
import { PageHeader } from '@/shared/components/PageHeader';
import { useEntity } from '@/shared/context/EntityContext';
import { oneOf } from '@/shared/urlParams';
import { useUrlState } from '@/shared/useUrlState';
import { GlInterface } from './GlInterface';
import { previousPeriod } from './model/glBalances';
import { MonthEndKpis } from './MonthEndKpis';
import { PeriodCard } from './PeriodCard';
import { PeriodEndRuns } from './PeriodEndRuns';
import { Policies } from './Policies';

/** Period end of the selected entity: close and its checklist, period-end runs, the GL interface and policies. */
export function MonthEndPage() {
  const { t } = useTranslation();
  const { entity } = useEntity();
  const { params, set: patch } = useUrlState();
  const period = params.get('period') ?? previousPeriod(new Date());
  const tab = oneOf(params.get('tab'), ['close', 'gl', 'policies'] as const, 'close');
  const set = (key: string, value: string | null) => patch({ [key]: value });
  return (
    <Stack>
      <PageHeader
        title={t('monthEnd.title')}
        description={t('monthEnd.description', { entity: entity?.code ?? '' })}
        actions={
          entity && (
            <TextInput
              type="month"
              aria-label={t('ledger.period')}
              leftSection={<IconCalendar size={16} />}
              value={period}
              onChange={(e) => set('period', e.currentTarget.value || null)}
              w={190}
              data-testid="period-picker"
            />
          )
        }
      />
      {!entity ? (
        <Alert color="gray">{t('header.noEntity')}</Alert>
      ) : (
        <>
          <MonthEndKpis entityId={entity.id} period={period} />
          <Tabs value={tab} onChange={(value) => set('tab', value === 'close' ? null : value)} keepMounted={false}>
            <Tabs.List>
              <Tabs.Tab value="close">{t('monthEnd.tabs.close')}</Tabs.Tab>
              <Tabs.Tab value="gl">{t('monthEnd.tabs.gl')}</Tabs.Tab>
              <Tabs.Tab value="policies">{t('monthEnd.tabs.policies')}</Tabs.Tab>
            </Tabs.List>
            <Tabs.Panel value="close" pt="md">
              <Stack>
                <PeriodCard entityId={entity.id} period={period} />
                <PeriodEndRuns entityId={entity.id} period={period} />
              </Stack>
            </Tabs.Panel>
            <Tabs.Panel value="gl" pt="md">
              <GlInterface entityId={entity.id} period={period} />
            </Tabs.Panel>
            <Tabs.Panel value="policies" pt="md">
              <Policies entityId={entity.id} />
            </Tabs.Panel>
          </Tabs>
        </>
      )}
    </Stack>
  );
}
