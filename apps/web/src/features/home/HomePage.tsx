import { BarChart } from '@mantine/charts';
import {
  Anchor,
  Badge,
  Button,
  Grid,
  Group,
  Paper,
  SimpleGrid,
  Stack,
  Text,
  ThemeIcon,
  Title,
  UnstyledButton,
} from '@mantine/core';
import { IconCircleCheck, IconPlus } from '@tabler/icons-react';
import dayjs from 'dayjs';
import { useState, type ReactNode } from 'react';
import { useTranslation } from 'react-i18next';
import { Link } from 'react-router';
import { token } from '@/app/tokens';
import { $api } from '@/shared/api/client';
import type { Schema } from '@/shared/api/types';
import { Can } from '@/shared/auth/Can';
import { currentPersona } from '@/shared/auth/personas';
import { useSession } from '@/shared/auth/SessionContext';
import { EmptyState } from '@/shared/components/EmptyState';
import { KpiCard, type Trend } from '@/shared/components/KpiCard';
import { PageHeader } from '@/shared/components/PageHeader';
import { ProblemAlert } from '@/shared/components/ProblemAlert';
import { CardSkeleton } from '@/shared/components/Skeletons';
import { StatusBadge } from '@/shared/components/StatusBadge';
import { useEntity } from '@/shared/context/EntityContext';
import { formatDate, formatDecimal, formatMoney } from '@/shared/format/format';
import { useTaskTypes } from '@/features/inbox/hooks';
import { useSpanText } from '@/features/inbox/TaskBadges';
import { span } from '@/features/inbox/model/time';
import { eventLabel, greeting, isNegative, orderKpis, type KpiKey } from './model/home';

type Summary = Schema<'HomeSummaryView'>;

/** How many figures lead the page as cards; the rest go to the watchlist. */
const LEAD = 4;

interface Kpi {
  key: KpiKey;
  label: string;
  value: ReactNode;
  unit?: string;
  trend: ReactNode;
  tone: Trend;
  spark?: number[];
  to: string;
}

const TONE_TEXT: Record<Trend, string> = {
  up: token('gain'),
  down: token('loss'),
  warning: token('warning'),
  neutral: token('muted'),
};

/** The figures the server sent for this user, each with its screen. */
function useKpis(summary: Summary): Kpi[] {
  const { t } = useTranslation();
  const kpis: Kpi[] = [];
  // With nothing to decide, "My work" says so; a zero card would only take space.
  if (summary.tasks && summary.tasks.actionable > 0) {
    const overdue = summary.tasks.recent.filter((task) => task.overdue).length;
    kpis.push({
      key: 'tasks',
      label: t('home.kpi.tasks'),
      value: summary.tasks.actionable,
      trend: overdue > 0 ? t('home.kpi.overdue', { count: overdue }) : t('home.kpi.nothingOverdue'),
      tone: overdue > 0 ? 'warning' : 'neutral',
      to: '/inbox',
    });
  }
  if (summary.trades) {
    kpis.push({
      key: 'trades',
      label: t('home.kpi.pendingTrades'),
      value: summary.trades.pendingApproval,
      trend: t('home.kpi.lastSevenDays', { count: summary.trades.lastSevenDays }),
      tone: summary.trades.pendingApproval > 0 ? 'warning' : 'neutral',
      to: '/trades',
    });
  }
  if (summary.goo) {
    if (summary.goo.coveragePct !== undefined && summary.goo.coveragePct !== null) {
      kpis.push({
        key: 'coverage',
        label: t('home.kpi.coverage'),
        value: formatDecimal(summary.goo.coveragePct, 1, 1),
        unit: '%',
        trend:
          summary.goo.shortfallMwh > 0
            ? t('home.kpi.shortfall', { mwh: formatDecimal(summary.goo.shortfallMwh) })
            : t('home.kpi.covered'),
        tone: summary.goo.shortfallMwh > 0 ? 'warning' : 'up',
        to: '/allocation',
      });
    }
    kpis.push({
      key: 'live',
      label: t('home.kpi.live'),
      value: formatDecimal(summary.goo.liveMwh),
      unit: 'MWh',
      trend: t('home.kpi.expiring', { mwh: formatDecimal(summary.goo.expiring90Mwh) }),
      tone: summary.goo.expiring90Mwh > 0 ? 'warning' : 'neutral',
      to: '/inventory',
    });
  }
  if (summary.valuation) {
    const change = summary.valuation.change;
    const first = change === null || change === undefined;
    kpis.push({
      key: 'mtm',
      label: t('home.kpi.mtm', { date: formatDate(summary.valuation.valuationDate) }),
      value: formatDecimal(summary.valuation.totalMtm, 0, 0),
      unit: summary.valuation.currency,
      trend: first
        ? t('home.kpi.firstRun')
        : t('home.kpi.change', { sign: isNegative(change) ? '▼' : '▲', amount: formatMoney(change) }),
      tone: first ? 'neutral' : isNegative(change) ? 'down' : 'up',
      spark: summary.valuation.history.map((point) => point.amount),
      to: '/valuation',
    });
  }
  if (summary.eua) {
    kpis.push({
      key: 'eua',
      label: t('home.kpi.eua', { year: summary.eua.complianceYear }),
      value: formatDecimal(summary.eua.netPosition),
      unit: 'tCO₂e',
      trend: summary.eua.surrenderDeadline
        ? t('home.kpi.surrender', { date: formatDate(summary.eua.surrenderDeadline) })
        : t('home.kpi.noObligation'),
      tone: summary.eua.netPosition < 0 ? 'warning' : 'neutral',
      to: '/ets',
    });
  }
  if (summary.reconciliation) {
    kpis.push({
      key: 'breaks',
      label: t('home.kpi.breaks'),
      value: summary.reconciliation.open,
      trend: summary.reconciliation.oldestFirstSeen
        ? t('home.kpi.oldest', { date: formatDate(summary.reconciliation.oldestFirstSeen) })
        : t('home.kpi.allReconciled'),
      tone: summary.reconciliation.open > 0 ? 'warning' : 'up',
      to: '/reconciliation',
    });
  }
  if (summary.settlement) {
    kpis.push({
      key: 'overdue',
      label: t('home.kpi.overdueInvoices'),
      value: summary.settlement.overdueInvoices,
      trend:
        summary.settlement.overdueAmounts.map((amount) => formatMoney(amount.amount, amount.currency)).join(' · ') ||
        t('home.kpi.allPaid'),
      tone: summary.settlement.overdueInvoices > 0 ? 'down' : 'up',
      to: '/settlement?tab=invoices',
    });
  }
  if (summary.risk) {
    kpis.push({
      key: 'risk',
      label: t('home.kpi.breaches'),
      value: summary.risk.openBreaches,
      trend: summary.risk.openBreaches > 0 ? t('home.kpi.review') : t('home.kpi.withinLimits'),
      tone: summary.risk.openBreaches > 0 ? 'down' : 'up',
      to: '/risk',
    });
  }
  return kpis;
}

/** A card with a title, an optional link on the right, and its content. */
function Section({
  title,
  action,
  children,
  testId,
}: {
  title: string;
  action?: ReactNode;
  children: ReactNode;
  testId: string;
}) {
  return (
    <Paper withBorder p="lg" data-testid={testId}>
      <Group justify="space-between" mb="md" wrap="nowrap">
        <Title order={3} size="h5">
          {title}
        </Title>
        {action}
      </Group>
      {children}
    </Paper>
  );
}

/** The figures that did not make the lead row, as compact lines. */
function Watchlist({ kpis }: { kpis: Kpi[] }) {
  const { t } = useTranslation();
  return (
    <Section title={t('home.watchlist')} testId="home-watchlist">
      <Stack gap={8}>
        {kpis.map((kpi) => (
          <UnstyledButton
            key={kpi.key}
            component={Link}
            to={kpi.to}
            className="etrm-task-row"
            data-testid={`kpi-${kpi.key}`}
          >
            <Group justify="space-between" wrap="nowrap" gap="sm">
              <Stack gap={2} miw={0}>
                <Text size="sm" truncate>
                  {kpi.label}
                </Text>
                <Text size="xs" fw={500} truncate style={{ color: TONE_TEXT[kpi.tone] }}>
                  {kpi.trend}
                </Text>
              </Stack>
              <Text fw={600} className="etrm-figure" style={{ whiteSpace: 'nowrap' }}>
                {kpi.value}
                {kpi.unit && (
                  <Text span size="xs" c="dimmed" ml={4}>
                    {kpi.unit}
                  </Text>
                )}
              </Text>
            </Group>
          </UnstyledButton>
        ))}
      </Stack>
    </Section>
  );
}

function MyWork({ summary }: { summary: Summary }) {
  const { t } = useTranslation();
  const words = useSpanText();
  const tasks = summary.tasks?.recent ?? [];
  const [now] = useState(() => Date.now());
  return (
    <Section
      title={t('home.myWork')}
      testId="my-tasks"
      action={
        <Group gap="sm" wrap="nowrap">
          {(summary.tasks?.actionable ?? 0) > tasks.length && (
            <Text size="xs" c="dimmed">
              {t('home.showingOf', { shown: tasks.length, total: summary.tasks?.actionable ?? 0 })}
            </Text>
          )}
          <Anchor component={Link} to="/inbox" size="sm">
            {t('inbox.openInbox')}
          </Anchor>
        </Group>
      }
    >
      {tasks.length === 0 ? (
        <Group gap="sm" wrap="nowrap" data-testid="home-caught-up">
          <ThemeIcon variant="light" color="green" radius="xl" size={32}>
            <IconCircleCheck size={18} />
          </ThemeIcon>
          <Stack gap={0}>
            <Text size="sm" fw={500}>
              {t('home.noWork')}
            </Text>
            <Text size="xs" c="dimmed">
              {t('home.noWorkHint')}
            </Text>
          </Stack>
        </Group>
      ) : (
        <Stack gap={8}>
          {tasks.map((task) => {
            const dueSoon = task.dueAt && !task.overdue && dayjs(task.dueAt).isSame(dayjs(), 'day');
            return (
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
                      {t('home.taskMeta', {
                        type: task.typeName,
                        who: task.requestedBy,
                        ago: words(span(new Date(task.requestedAt).getTime(), now)),
                      })}
                    </Text>
                  </Stack>
                  {task.dueAt && (
                    <StatusBadge
                      style={{ flexShrink: 0 }}
                      status={task.overdue ? 'OVERDUE' : dueSoon ? 'PENDING' : 'OPEN'}
                      label={
                        task.overdue
                          ? t('home.overdue')
                          : dueSoon
                            ? t('home.dueToday', { time: dayjs(task.dueAt).format('HH:mm') })
                            : t('home.due', { date: dayjs(task.dueAt).format('D MMM') })
                      }
                    />
                  )}
                </Group>
              </UnstyledButton>
            );
          })}
        </Stack>
      )}
    </Section>
  );
}

function Coverage({ goo }: { goo: NonNullable<Summary['goo']> }) {
  const { t } = useTranslation();
  const months = goo.byMonth;
  return (
    <Section
      title={t('home.coverageTitle')}
      testId="home-coverage"
      action={
        <Anchor component={Link} to="/allocation" size="sm">
          {t('home.openAllocation')}
        </Anchor>
      }
    >
      {months.length === 0 ? (
        <EmptyState
          title={t('home.noObligations')}
          description={t('home.noObligationsHint')}
          action={
            <Can op="trade_capture">
              <Button component={Link} to="/trades/new" variant="light" size="xs">
                {t('home.newTrade')}
              </Button>
            </Can>
          }
        />
      ) : (
        <Stack gap="md">
          <SimpleGrid cols={3} spacing="md">
            {[
              { label: t('home.demand'), value: goo.demandMwh },
              { label: t('home.covered'), value: goo.coveredMwh },
              { label: t('home.short'), value: goo.shortfallMwh, warn: goo.shortfallMwh > 0 },
            ].map((figure) => (
              <Stack key={figure.label} gap={0}>
                <Text size="xs" c="dimmed">
                  {figure.label}
                </Text>
                <Text fw={600} className="etrm-figure" style={figure.warn ? { color: token('warning') } : undefined}>
                  {formatDecimal(figure.value)}{' '}
                  <Text span size="xs" c="dimmed" fw={400}>
                    MWh
                  </Text>
                </Text>
              </Stack>
            ))}
          </SimpleGrid>
          <BarChart
            h={200}
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
            maxBarWidth={44}
            withLegend
            legendProps={{ verticalAlign: 'top', height: 32 }}
            tickLine="y"
            valueFormatter={(value) => formatDecimal(value)}
          />
        </Stack>
      )}
    </Section>
  );
}

interface Upcoming {
  key: string;
  title: string;
  meta?: string;
  badge: ReactNode;
  to?: string;
}

function ComingUp({ summary }: { summary: Summary }) {
  const { t } = useTranslation();
  const rows: Upcoming[] = summary.deadlines.map((deadline) => {
    const days = dayjs(deadline.dueOn).diff(dayjs().startOf('day'), 'day');
    return {
      key: `${deadline.kind}-${deadline.installationName}-${deadline.dueOn}`,
      title: t(`home.deadline.${deadline.kind}`, { year: deadline.complianceYear }),
      meta: deadline.installationName,
      badge: <StatusBadge status={days <= 14 ? 'PENDING' : 'OPEN'} label={formatDate(deadline.dueOn)} />,
      to: '/ets',
    };
  });
  if (summary.goo && summary.goo.expiring30Mwh > 0) {
    rows.push({
      key: 'expiring',
      title: t('home.expiringSoon', { mwh: formatDecimal(summary.goo.expiring30Mwh) }),
      meta: t('home.expiringHint'),
      badge: <StatusBadge status="WARNING" label={t('home.within30')} />,
      to: '/inventory',
    });
  }
  if (summary.ledger && (summary.ledger.blockers.length > 0 || summary.ledger.status !== 'CLOSED')) {
    rows.push({
      key: 'ledger',
      title: t('home.closePeriod', { period: summary.ledger.period }),
      meta: summary.ledger.blockers.length > 0 ? summary.ledger.blockers.join(' · ') : undefined,
      badge: <StatusBadge status={summary.ledger.status} />,
      to: '/month-end',
    });
  }
  return (
    <Section title={t('home.upcoming')} testId="home-deadlines">
      {rows.length === 0 ? (
        <Text size="sm" c="dimmed">
          {t('home.nothingUpcoming')}
        </Text>
      ) : (
        <Stack gap={8}>
          {rows.map((row) => (
            <UnstyledButton
              key={row.key}
              component={Link}
              to={row.to ?? '/'}
              className="etrm-task-row"
              data-testid="home-upcoming"
            >
              <Group justify="space-between" wrap="nowrap" align="flex-start" gap="sm">
                <Stack gap={2} miw={0}>
                  <Text size="sm" fw={500}>
                    {row.title}
                  </Text>
                  {row.meta && (
                    <Text size="xs" c="dimmed" lineClamp={2}>
                      {row.meta}
                    </Text>
                  )}
                </Stack>
                <span style={{ flexShrink: 0 }}>{row.badge}</span>
              </Group>
            </UnstyledButton>
          ))}
        </Stack>
      )}
    </Section>
  );
}

function Activity({ activity }: { activity: NonNullable<Summary['activity']> }) {
  const { t } = useTranslation();
  const words = useSpanText();
  const [now] = useState(() => Date.now());
  return (
    <Section
      title={t('home.activity')}
      testId="home-activity"
      action={
        <Anchor component={Link} to="/audit" size="sm">
          {t('home.auditTrail')}
        </Anchor>
      }
    >
      {activity.length === 0 ? (
        <Text size="sm" c="dimmed">
          {t('home.noActivity')}
        </Text>
      ) : (
        <Stack gap={12}>
          {activity.map((event) => (
            <Group
              key={`${event.occurredAt}-${event.eventType}-${event.aggregateRef ?? ''}`}
              justify="space-between"
              wrap="nowrap"
              align="flex-start"
              gap="sm"
            >
              <Group gap="sm" wrap="nowrap" miw={0} align="flex-start">
                <span
                  aria-hidden="true"
                  style={{
                    width: 6,
                    height: 6,
                    marginTop: 7,
                    borderRadius: 3,
                    flexShrink: 0,
                    background: token('accent'),
                  }}
                />
                <Stack gap={0} miw={0}>
                  {event.aggregateRef ? (
                    <Anchor
                      component={Link}
                      to={`/audit?aggregateRef=${encodeURIComponent(event.aggregateRef)}`}
                      size="sm"
                      c="var(--mantine-color-text)"
                      truncate
                    >
                      {eventLabel(event.eventType)}
                    </Anchor>
                  ) : (
                    <Text size="sm" truncate>
                      {eventLabel(event.eventType)}
                    </Text>
                  )}
                  <Text size="xs" c="dimmed" truncate>
                    {event.actor}
                    {event.aggregateType ? ` · ${event.aggregateType}` : ''}
                  </Text>
                </Stack>
              </Group>
              <Text
                size="xs"
                c="dimmed"
                style={{ whiteSpace: 'nowrap' }}
                title={dayjs(event.occurredAt).format('YYYY-MM-DD HH:mm')}
              >
                {t('inbox.ago', { span: words(span(new Date(event.occurredAt).getTime(), now)) })}
              </Text>
            </Group>
          ))}
        </Stack>
      )}
    </Section>
  );
}

function Dashboard({ summary }: { summary: Summary }) {
  const { me } = useSession();
  const types = useTaskTypes();
  // A role that never decides a task (the auditor) has no "My work" to show.
  const decides =
    types.size === 0 ||
    me.roles.includes('ADMIN') ||
    [...types.values()].some((type) => type.candidateRoles.some((role) => me.roles.includes(role)));
  const kpis = useKpis(summary);
  const ordered = orderKpis(
    kpis.map((kpi) => kpi.key),
    me.roles,
  ).map((key) => kpis.find((kpi) => kpi.key === key) as Kpi);
  const lead = ordered.slice(0, LEAD);
  const rest = ordered.slice(LEAD);
  const goo = summary.goo;
  return (
    <>
      <SimpleGrid cols={{ base: 1, xs: 2, lg: Math.max(lead.length, 1) }} spacing="md" data-testid="home-kpis">
        {lead.map((kpi) => (
          <KpiCard
            key={kpi.key}
            testId={`kpi-${kpi.key}`}
            label={kpi.label}
            value={kpi.value}
            unit={kpi.unit}
            trend={kpi.trend}
            trendTone={kpi.tone}
            spark={kpi.spark}
            to={kpi.to}
          />
        ))}
      </SimpleGrid>
      <Grid gap="md">
        <Grid.Col span={{ base: 12, lg: 8 }}>
          <Stack gap="md">
            {summary.tasks && decides && <MyWork summary={summary} />}
            {goo && <Coverage goo={goo} />}
          </Stack>
        </Grid.Col>
        <Grid.Col span={{ base: 12, lg: 4 }}>
          <Stack gap="md">
            <ComingUp summary={summary} />
            {rest.length > 0 && <Watchlist kpis={rest} />}
            {summary.activity && <Activity activity={summary.activity} />}
          </Stack>
        </Grid.Col>
      </Grid>
    </>
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
        eyebrow={dayjs().format('dddd D MMMM YYYY')}
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
      ) : summary.error ? (
        <Stack align="flex-start">
          <ProblemAlert error={summary.error} />
          <Button variant="default" onClick={() => void summary.refetch()}>
            {t('home.retry')}
          </Button>
        </Stack>
      ) : !summary.data ? (
        <SimpleGrid cols={{ base: 1, xs: 2, lg: 4 }}>
          {[1, 2, 3, 4].map((key) => (
            <CardSkeleton key={key} height={130} />
          ))}
        </SimpleGrid>
      ) : (
        <Dashboard summary={summary.data} />
      )}
    </Stack>
  );
}
