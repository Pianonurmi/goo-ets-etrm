import { BarChart } from '@mantine/charts';
import { Anchor, Badge, Button, Group, Paper, SimpleGrid, Stack, Text, Title, UnstyledButton } from '@mantine/core';
import { IconPlus } from '@tabler/icons-react';
import dayjs from 'dayjs';
import { useTranslation } from 'react-i18next';
import { Link } from 'react-router';
import { $api } from '@/shared/api/client';
import type { Schema } from '@/shared/api/types';
import { Can } from '@/shared/auth/Can';
import { currentPersona } from '@/shared/auth/personas';
import { useSession } from '@/shared/auth/SessionContext';
import { EmptyState } from '@/shared/components/EmptyState';
import { KpiCard } from '@/shared/components/KpiCard';
import { PageHeader } from '@/shared/components/PageHeader';
import { CardSkeleton } from '@/shared/components/Skeletons';
import { StatusBadge } from '@/shared/components/StatusBadge';
import { useEntity } from '@/shared/context/EntityContext';
import { formatDate, formatDateTime, formatDecimal, formatMoney } from '@/shared/format/format';
import { greeting, isNegative } from './model/home';

type Summary = Schema<'HomeSummaryView'>;

function Kpis({ summary }: { summary: Summary }) {
  const { t } = useTranslation();
  const cards = [];
  if (summary.tasks) {
    const overdue = summary.tasks.recent.filter((task) => task.overdue).length;
    cards.push(
      <KpiCard
        key="tasks"
        testId="kpi-tasks"
        label={t('home.kpi.tasks')}
        value={summary.tasks.actionable}
        trend={overdue > 0 ? t('home.kpi.overdue', { count: overdue }) : t('home.kpi.nothingOverdue')}
        trendTone={overdue > 0 ? 'warning' : 'neutral'}
        to="/inbox"
      />,
    );
  }
  if (summary.goo?.coveragePct !== undefined && summary.goo.coveragePct !== null) {
    cards.push(
      <KpiCard
        key="coverage"
        testId="kpi-coverage"
        label={t('home.kpi.coverage')}
        value={formatDecimal(summary.goo.coveragePct, 1, 1)}
        unit="%"
        trend={
          summary.goo.shortfallMwh > 0
            ? t('home.kpi.shortfall', { mwh: formatDecimal(summary.goo.shortfallMwh) })
            : t('home.kpi.covered')
        }
        trendTone={summary.goo.shortfallMwh > 0 ? 'warning' : 'up'}
        to="/allocation"
      />,
    );
  } else if (summary.goo) {
    cards.push(
      <KpiCard
        key="live"
        testId="kpi-live"
        label={t('home.kpi.live')}
        value={formatDecimal(summary.goo.liveMwh)}
        unit="MWh"
        trend={t('home.kpi.expiring', { mwh: formatDecimal(summary.goo.expiring90Mwh) })}
        trendTone={summary.goo.expiring90Mwh > 0 ? 'warning' : 'neutral'}
        to="/inventory"
      />,
    );
  }
  if (summary.valuation) {
    const change = summary.valuation.change;
    cards.push(
      <KpiCard
        key="mtm"
        testId="kpi-mtm"
        label={t('home.kpi.mtm', { date: formatDate(summary.valuation.valuationDate) })}
        value={formatDecimal(summary.valuation.totalMtm, 0, 0)}
        unit={summary.valuation.currency}
        trend={
          change === null || change === undefined
            ? t('home.kpi.firstRun')
            : `${isNegative(change) ? '▼' : '▲'} ${formatMoney(change)}`
        }
        trendTone={change === null || change === undefined ? 'neutral' : isNegative(change) ? 'down' : 'up'}
        spark={summary.valuation.history.map((point) => point.amount)}
        to="/valuation"
      />,
    );
  }
  if (summary.eua) {
    const short = summary.eua.netPosition < 0;
    cards.push(
      <KpiCard
        key="eua"
        testId="kpi-eua"
        label={t('home.kpi.eua', { year: summary.eua.complianceYear })}
        value={formatDecimal(summary.eua.netPosition)}
        unit="tCO₂e"
        trend={
          summary.eua.surrenderDeadline
            ? t('home.kpi.surrender', { date: formatDate(summary.eua.surrenderDeadline) })
            : t('home.kpi.noObligation')
        }
        trendTone={short ? 'warning' : 'neutral'}
        to="/ets"
      />,
    );
  }
  if (summary.reconciliation) {
    cards.push(
      <KpiCard
        key="breaks"
        testId="kpi-breaks"
        label={t('home.kpi.breaks')}
        value={summary.reconciliation.open}
        trend={
          summary.reconciliation.oldestFirstSeen
            ? t('home.kpi.oldest', { date: formatDate(summary.reconciliation.oldestFirstSeen) })
            : t('home.kpi.allReconciled')
        }
        trendTone={summary.reconciliation.open > 0 ? 'warning' : 'up'}
        to="/reconciliation"
      />,
    );
  }
  if (summary.settlement) {
    cards.push(
      <KpiCard
        key="overdue"
        testId="kpi-overdue"
        label={t('home.kpi.overdueInvoices')}
        value={summary.settlement.overdueInvoices}
        trend={
          summary.settlement.overdueAmounts.map((amount) => formatMoney(amount.amount, amount.currency)).join(' · ') ||
          t('home.kpi.allPaid')
        }
        trendTone={summary.settlement.overdueInvoices > 0 ? 'down' : 'up'}
        to="/settlement/invoices"
      />,
    );
  }
  if (summary.risk) {
    cards.push(
      <KpiCard
        key="risk"
        testId="kpi-risk"
        label={t('home.kpi.breaches')}
        value={summary.risk.openBreaches}
        trend={summary.risk.openBreaches > 0 ? t('home.kpi.review') : t('home.kpi.withinLimits')}
        trendTone={summary.risk.openBreaches > 0 ? 'down' : 'up'}
        to="/risk"
      />,
    );
  }
  return (
    <SimpleGrid cols={{ base: 1, xs: 2, lg: 4 }} spacing="md" data-testid="home-kpis">
      {cards.slice(0, 8)}
    </SimpleGrid>
  );
}

function MyWork({ summary }: { summary: Summary }) {
  const { t } = useTranslation();
  const tasks = summary.tasks?.recent ?? [];
  return (
    <Paper withBorder p="lg" data-testid="my-tasks">
      <Group justify="space-between" mb="sm">
        <Title order={3} size="h4">
          {t('home.myWork')}
        </Title>
        <Anchor component={Link} to="/inbox" size="sm">
          {t('inbox.openInbox')}
        </Anchor>
      </Group>
      {tasks.length === 0 ? (
        <EmptyState title={t('home.noWork')} description={t('home.noWorkHint')} />
      ) : (
        <Stack gap={8}>
          {tasks.map((task) => (
            <UnstyledButton
              key={task.id}
              component={Link}
              to={`/inbox/${task.id}`}
              className="etrm-task-row"
              data-testid="home-task"
            >
              <Group justify="space-between" wrap="nowrap" gap="sm">
                <Stack gap={2} miw={0}>
                  <Text size="sm" fw={500} truncate>
                    {task.summary ?? task.typeName}
                  </Text>
                  <Text size="xs" c="dimmed" truncate>
                    {task.typeName} · {task.requestedBy} · {formatDateTime(task.requestedAt)}
                  </Text>
                </Stack>
                {task.dueAt && (
                  <StatusBadge
                    style={{ flexShrink: 0 }}
                    status={task.overdue ? 'OVERDUE' : 'PENDING'}
                    label={
                      task.overdue ? t('home.overdue') : t('home.due', { date: dayjs(task.dueAt).format('D MMM') })
                    }
                  />
                )}
              </Group>
            </UnstyledButton>
          ))}
        </Stack>
      )}
    </Paper>
  );
}

function Coverage({ summary }: { summary: Summary }) {
  const { t } = useTranslation();
  const months = summary.goo?.byMonth ?? [];
  return (
    <Paper withBorder p="lg" data-testid="home-coverage">
      <Group justify="space-between" mb="sm">
        <Title order={3} size="h4">
          {t('home.coverageTitle')}
        </Title>
        <Text size="xs" c="dimmed">
          {t('home.coverageUnit')}
        </Text>
      </Group>
      {months.length === 0 ? (
        <EmptyState title={t('home.noObligations')} description={t('home.noObligationsHint')} />
      ) : (
        <BarChart
          h={220}
          data={months.map((month) => ({
            month: dayjs(`${month.month}-01`).format('MMM YY'),
            covered: month.coveredMwh,
            demand: month.demandMwh,
          }))}
          dataKey="month"
          series={[
            { name: 'demand', label: t('home.demand'), color: 'gray.4' },
            { name: 'covered', label: t('home.covered'), color: 'brand.7' },
          ]}
          withLegend
          tickLine="y"
          valueFormatter={(value) => formatDecimal(value)}
        />
      )}
    </Paper>
  );
}

function Deadlines({ summary }: { summary: Summary }) {
  const { t } = useTranslation();
  const rows = [
    ...summary.deadlines.map((deadline) => ({
      key: `${deadline.kind}-${deadline.installationName}-${deadline.dueOn}`,
      title: t(`home.deadline.${deadline.kind}`, { year: deadline.complianceYear }),
      meta: deadline.installationName,
      date: deadline.dueOn,
    })),
    ...(summary.ledger && summary.ledger.blockers.length > 0
      ? [
          {
            key: 'ledger',
            title: t('home.closePeriod', { period: summary.ledger.period }),
            meta: summary.ledger.blockers.join(' · '),
            date: null,
          },
        ]
      : []),
  ];
  return (
    <Paper withBorder p="lg" data-testid="home-deadlines">
      <Title order={3} size="h4" mb="sm">
        {t('home.upcoming')}
      </Title>
      {rows.length === 0 ? (
        <Text size="sm" c="dimmed">
          {t('home.nothingUpcoming')}
        </Text>
      ) : (
        <Stack gap="sm">
          {rows.map((row) => (
            <Group key={row.key} justify="space-between" wrap="nowrap" align="flex-start">
              <Stack gap={0} miw={0}>
                <Text size="sm" fw={500}>
                  {row.title}
                </Text>
                <Text size="xs" c="dimmed" lineClamp={2}>
                  {row.meta}
                </Text>
              </Stack>
              {row.date && (
                <Badge variant="light" color="yellow" tt="none" style={{ flexShrink: 0 }}>
                  {formatDate(row.date)}
                </Badge>
              )}
            </Group>
          ))}
        </Stack>
      )}
    </Paper>
  );
}

function Activity({ summary }: { summary: Summary }) {
  const { t } = useTranslation();
  if (!summary.activity) return null;
  return (
    <Paper withBorder p="lg" data-testid="home-activity">
      <Group justify="space-between" mb="sm">
        <Title order={3} size="h4">
          {t('home.activity')}
        </Title>
        <Anchor component={Link} to="/audit" size="sm">
          {t('home.auditTrail')}
        </Anchor>
      </Group>
      <Stack gap={10}>
        {summary.activity.map((event) => (
          <Group
            key={`${event.occurredAt}-${event.eventType}-${event.aggregateRef ?? ''}`}
            justify="space-between"
            wrap="nowrap"
          >
            <Text size="sm" truncate>
              <Text span fw={500}>
                {event.actor}
              </Text>{' '}
              <Text span c="dimmed">
                {event.eventType}
              </Text>
            </Text>
            <Text size="xs" c="dimmed" style={{ whiteSpace: 'nowrap' }}>
              {dayjs(event.occurredAt).format('D MMM HH:mm')}
            </Text>
          </Group>
        ))}
      </Stack>
    </Paper>
  );
}

/** The home dashboard (ADR 0034): what needs you, and the headline figures of the selected legal entity. */
export function HomePage() {
  const { t } = useTranslation();
  const { me, config } = useSession();
  const { entity } = useEntity();
  // With personas the person is the persona; greet by first name.
  const name = (config.authMode === 'OIDC' ? me.displayName : currentPersona().name).split(' ')[0];
  const summary = $api.useQuery(
    'get',
    '/api/v1/home/summary',
    { params: { query: { legalEntityId: entity?.id ?? '' } } },
    { enabled: !!entity, refetchInterval: 60_000 },
  );
  return (
    <Stack gap="lg">
      <PageHeader
        eyebrow={dayjs().format('dddd D MMMM')}
        title={t(greeting(new Date().getHours()), { name })}
        description={
          <Group gap={6} component="span" data-testid="my-roles">
            {me.roles.map((role) => (
              <Badge key={role} variant="light" tt="none" component="span">
                {t(`roles.${role}`)}
              </Badge>
            ))}
            {entity && (
              <Text span size="sm" c="dimmed">
                · {entity.code} · {entity.name}
              </Text>
            )}
          </Group>
        }
        actions={
          <Can op="trade_capture">
            <Button component={Link} to="/trades/new" leftSection={<IconPlus size={16} />}>
              {t('home.newTrade')}
            </Button>
          </Can>
        }
      />
      {!entity ? (
        <EmptyState title={t('home.noEntity')} description={t('home.noEntityHint')} />
      ) : !summary.data ? (
        <SimpleGrid cols={{ base: 1, xs: 2, lg: 4 }}>
          {[1, 2, 3, 4].map((key) => (
            <CardSkeleton key={key} height={130} />
          ))}
        </SimpleGrid>
      ) : (
        <>
          <Kpis summary={summary.data} />
          <SimpleGrid cols={{ base: 1, lg: 2 }} spacing="md">
            <MyWork summary={summary.data} />
            {summary.data.goo && <Coverage summary={summary.data} />}
          </SimpleGrid>
          <SimpleGrid cols={{ base: 1, lg: 2 }} spacing="md">
            <Deadlines summary={summary.data} />
            <Activity summary={summary.data} />
          </SimpleGrid>
        </>
      )}
    </Stack>
  );
}
