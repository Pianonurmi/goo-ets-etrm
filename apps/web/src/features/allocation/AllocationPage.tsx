import { BarChart } from '@mantine/charts';
import {
  Accordion,
  Alert,
  Anchor,
  Badge,
  Button,
  Grid,
  Group,
  Paper,
  ScrollArea,
  SegmentedControl,
  SimpleGrid,
  Stack,
  Table,
  Text,
  Title,
  UnstyledButton,
} from '@mantine/core';
import { IconFlask, IconPlus, IconSend } from '@tabler/icons-react';
import type { ColDef, ICellRendererParams } from 'ag-grid-community';
import { useCallback, useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link, useNavigate, useSearchParams } from 'react-router';
import { $api } from '@/shared/api/client';
import type { Body, Schema } from '@/shared/api/types';
import { Can } from '@/shared/auth/Can';
import { EmptyState } from '@/shared/components/EmptyState';
import { KpiCard } from '@/shared/components/KpiCard';
import { notifySuccess } from '@/shared/components/notify';
import { PageHeader } from '@/shared/components/PageHeader';
import { ProblemAlert } from '@/shared/components/ProblemAlert';
import { QueryState } from '@/shared/components/QueryState';
import { CardSkeleton, TableSkeleton } from '@/shared/components/Skeletons';
import { StatusBadge, statusLabel } from '@/shared/components/StatusBadge';
import { useEntity } from '@/shared/context/EntityContext';
import { formatDate, formatDateTime, formatDecimal } from '@/shared/format/format';
import { DataGrid } from '@/shared/grid/DataGrid';
import { newKey } from '@/shared/ids';
import { coverageRows, groupLines, obligationName, type Line } from './model/plan';
import { ObligationDrawer, OpenObligationModal } from './ObligationPanels';
import { PlanValuePanel } from './PlanValuePanel';
import { ShadowPricesPanel } from './ShadowPricesPanel';

type Obligation = Schema<'ObligationView'>;
type Plan = Schema<'AllocationPlanView'>;
type WhatIf = Schema<'AllocationWhatIf'>;
type Coverage = Schema<'CoverageReport'>;
type Solver = Body<'ProposeAllocation'>['solver'] & string;

export function PlanStatusBadge({ status, testId = 'plan-status' }: { status: string; testId?: string }) {
  return <StatusBadge status={status} data-testid={testId} />;
}

/** Covered and short MWh per open obligation, straight from the coverage report. */
function CoverageChart({ coverage, obligations }: { coverage: Coverage; obligations: Obligation[] }) {
  const { t } = useTranslation();
  const rows = coverageRows(coverage, obligations, (kind) => t(`allocation.kinds.${kind}`));
  return (
    <Paper withBorder radius="lg" p="lg" h="100%" data-testid="coverage-chart">
      <Group justify="space-between" align="baseline" mb="sm">
        <Title order={3} size="h5">
          {t('allocation.coverageTitle')}
        </Title>
        <Text size="xs" c="dimmed">
          {t('allocation.coverageHint')}
        </Text>
      </Group>
      {rows.length === 0 ? (
        <EmptyState title={t('allocation.noObligations')} description={t('allocation.noObligationsHint')} />
      ) : (
        <BarChart
          h={Math.max(160, rows.length * 30 + 40)}
          data={rows}
          dataKey="label"
          type="stacked"
          orientation="vertical"
          yAxisProps={{ width: 210 }}
          withLegend
          legendProps={{ verticalAlign: 'bottom', height: 28 }}
          series={[
            { name: 'covered', label: t('allocation.covered'), color: 'teal.7' },
            { name: 'shortfall', label: t('allocation.shortfallLabel'), color: 'red.6' },
          ]}
          valueFormatter={(value) => `${formatDecimal(value)} MWh`}
          gridAxis="x"
        />
      )}
    </Paper>
  );
}

/** The plans of the entity, newest first, as a short list beside the chart. */
function PlanList({ plans, onOpen }: { plans: Plan[]; onOpen: (plan: Plan) => void }) {
  const { t } = useTranslation();
  const sorted = [...plans].sort((a, b) => b.proposedAt.localeCompare(a.proposedAt));
  return (
    <Paper withBorder radius="lg" p="lg" h="100%" data-testid="plan-list">
      <Title order={3} size="h5" mb="sm">
        {t('allocation.plans')}
      </Title>
      {sorted.length === 0 ? (
        <EmptyState title={t('allocation.noPlans')} description={t('allocation.noPlansHint')} />
      ) : (
        <ScrollArea.Autosize mah={340}>
          <Stack gap={4}>
            {sorted.map((plan) => (
              <UnstyledButton key={plan.id} onClick={() => onOpen(plan)} className="etrm-task-row" p="xs">
                <Group justify="space-between" wrap="nowrap">
                  <Stack gap={0} miw={0}>
                    <Text size="sm" fw={600} className="etrm-figure">
                      {t('allocation.planTitle', { mwh: formatDecimal(plan.allocatedMwh ?? 0) })}
                    </Text>
                    <Text size="xs" c="dimmed" truncate>
                      {plan.proposedBy} · {formatDateTime(plan.proposedAt)} · {t(`allocation.solvers.${plan.solver}`)}
                    </Text>
                  </Stack>
                  <PlanStatusBadge status={plan.status} testId={`plan-row-status-${plan.id}`} />
                </Group>
              </UnstyledButton>
            ))}
          </Stack>
        </ScrollArea.Autosize>
      )}
    </Paper>
  );
}

/** Lines of a computed or proposed plan, one table per obligation. */
export function LinesByObligation({ lines, obligations }: { lines: Line[]; obligations: Map<string, Obligation> }) {
  const { t } = useTranslation();
  // New plans carry a cost in EUR per MWh; plans made before value-based allocation keep the dimensionless score.
  const inEur = lines.length > 0 && lines.every((line) => line.breakdown.unit === 'EUR_PER_MWH');
  return (
    <Stack gap="sm">
      {groupLines(lines).map((group) => {
        const obligation = obligations.get(group.obligationId);
        return (
          <Paper withBorder radius="lg" p="md" key={group.obligationId}>
            <Group justify="space-between" mb="xs">
              <Text fw={600} size="sm">
                {obligation?.criteriaSummary ?? group.obligationId.slice(-8)}
              </Text>
              <Group gap="xs">
                {obligation && (
                  <Badge variant="default" radius="xl" tt="none">
                    {t('allocation.due', { date: formatDate(obligation.deliveryDate) })}
                  </Badge>
                )}
                <Badge variant="light" radius="xl" tt="none">
                  {formatDecimal(group.totalMwh)} / {formatDecimal(obligation?.remainingMwh ?? group.totalMwh)} MWh
                </Badge>
              </Group>
            </Group>
            <Table striped withRowBorders={false}>
              <Table.Thead>
                <Table.Tr>
                  <Table.Th>{t('allocation.lot')}</Table.Th>
                  <Table.Th>{t('allocation.class')}</Table.Th>
                  <Table.Th ta="right">MWh</Table.Th>
                  <Table.Th ta="right">{t(inEur ? 'allocation.costPerMwh' : 'allocation.score')}</Table.Th>
                  {inEur && <Table.Th ta="right">{t('allocation.giveAwayPerMwh')}</Table.Th>}
                  <Table.Th>{t('allocation.bucket')}</Table.Th>
                </Table.Tr>
              </Table.Thead>
              <Table.Tbody>
                {group.lines.map((line) => (
                  <Table.Tr key={`${line.lotId}-${line.lineNo}`}>
                    <Table.Td>
                      <Anchor component={Link} to={`/inventory/lots/${line.lotId}`} size="sm" ff="monospace">
                        {line.lotId.slice(-8)}
                      </Anchor>
                    </Table.Td>
                    <Table.Td>
                      <Text size="xs" ff="monospace">
                        {line.lotClassKey.slice(0, 40)}
                      </Text>
                    </Table.Td>
                    <Table.Td ta="right">{formatDecimal(line.quantityMwh)}</Table.Td>
                    <Table.Td ta="right">{formatDecimal(line.score, 2, 4)}</Table.Td>
                    {inEur && (
                      <Table.Td ta="right" data-testid="line-give-away">
                        {line.breakdown.priced === false
                          ? '—'
                          : formatDecimal(line.breakdown.giveAwayPerMwh, 2, 2) || '—'}
                      </Table.Td>
                    )}
                    <Table.Td>
                      <Group gap={6} wrap="nowrap">
                        {line.breakdown.bucketCode ?? '—'}
                        {line.breakdown.priced === false && (
                          <span title={t('allocation.unpricedBadgeHint')}>
                            <StatusBadge
                              status="MISSING_PRICE"
                              label={t('allocation.unpricedBadge')}
                              data-testid="line-unpriced"
                            />
                          </span>
                        )}
                      </Group>
                    </Table.Td>
                  </Table.Tr>
                ))}
              </Table.Tbody>
            </Table>
          </Paper>
        );
      })}
    </Stack>
  );
}

function WhatIfResult({
  result,
  active,
  obligations,
}: {
  result: WhatIf;
  active: Plan | undefined;
  obligations: Map<string, Obligation>;
}) {
  const { t } = useTranslation();
  const obligationLabel = useCallback((id: string) => obligationName(obligations.get(id)), [obligations]);
  const allocated = Number(result.objective.allocatedMwh ?? 0);
  const { solver, fallbackReason } = result.objective;
  const solvedBy = typeof solver === 'string' ? solver : result.solver;
  const fallback = typeof fallbackReason === 'string' ? fallbackReason : undefined;
  return (
    <Paper withBorder radius="lg" p="lg" data-testid="what-if-result">
      <Group justify="space-between" mb="sm">
        <Title order={3} size="h5">
          {t('allocation.whatIfTitle', { at: formatDateTime(result.computedAt) })}
        </Title>
        <Group gap="xs">
          <Badge variant="light" color="teal" data-testid="what-if-allocated">
            {t('allocation.allocated', { mwh: formatDecimal(allocated) })}
          </Badge>
          <Badge variant="outline" color="gray" data-testid="what-if-solver">
            {t('allocation.solvedBy', { solver: solvedBy })}
          </Badge>
          {active && (
            <Badge variant="default" radius="xl" tt="none">
              {t('allocation.activePlan', {
                mwh: formatDecimal(active.allocatedMwh ?? 0),
                status: statusLabel(active.status).toLowerCase(),
              })}
            </Badge>
          )}
        </Group>
      </Group>
      {fallback && (
        <Alert color="gray" mb="sm" data-testid="what-if-fallback">
          {t('allocation.fallback', { reason: fallback })}
        </Alert>
      )}
      {result.coverage.totalShortfallMwh > 0 && (
        <Alert color="orange" mb="sm">
          {t('allocation.shortfall', { mwh: formatDecimal(result.coverage.totalShortfallMwh) })}
        </Alert>
      )}
      {result.value && (
        <Stack gap="sm" mb="md">
          <PlanValuePanel
            value={result.value}
            pricing={result.pricing}
            obligationLabel={obligationLabel}
            kpiTestId="what-if-value-kpis"
            gridTestId="what-if-give-away"
          />
        </Stack>
      )}
      {result.pricing && (
        <Accordion variant="separated" mb="md" data-testid="what-if-shadow-prices">
          <Accordion.Item value="shadow-prices">
            <Accordion.Control>{t('allocation.shadow.title')}</Accordion.Control>
            <Accordion.Panel>
              <ShadowPricesPanel
                pricing={result.pricing}
                obligationLabel={obligationLabel}
                kpiTestId="what-if-shadow-kpis"
                obligationsTestId="what-if-shadow-obligations"
                supplyTestId="what-if-shadow-supply"
              />
            </Accordion.Panel>
          </Accordion.Item>
        </Accordion>
      )}
      <LinesByObligation lines={result.lines} obligations={obligations} />
    </Paper>
  );
}

export function AllocationPage() {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const { entity } = useEntity();
  const entityId = entity?.id ?? '';
  const enabled = { enabled: Boolean(entity) };
  const obligations = $api.useQuery(
    'get',
    '/api/v1/allocation/obligations',
    { params: { query: { holderEntityId: entityId } } },
    enabled,
  );
  const coverage = $api.useQuery(
    'get',
    '/api/v1/allocation/coverage',
    { params: { query: { holderEntityId: entityId } } },
    enabled,
  );
  const plans = $api.useQuery(
    'get',
    '/api/v1/allocation/plans',
    { params: { query: { holderEntityId: entityId, activeOnly: false } } },
    enabled,
  );
  const [solver, setSolver] = useState<Solver>('GREEDY');
  const [params, setParams] = useSearchParams();
  const [opening, setOpening] = useState(false);
  const selectedObligation = params.get('obligation');
  const showObligation = (id: string | null) => {
    const next = new URLSearchParams(params);
    if (id) next.set('obligation', id);
    else next.delete('obligation');
    setParams(next, { replace: true });
  };
  const whatIf = $api.useMutation('post', '/api/v1/allocation/plans/what-if', { meta: { notify: false } });
  const propose = $api.useMutation('post', '/api/v1/allocation/plans', { meta: { notify: false } });
  const byId = useMemo(() => new Map((obligations.data ?? []).map((o) => [o.id, o])), [obligations.data]);
  const active = (plans.data ?? []).find((plan) => ['PROPOSED', 'APPROVED', 'EXECUTING'].includes(plan.status));

  const obligationColumns = useMemo<ColDef<Obligation>[]>(
    () => [
      { field: 'deliveryDate', headerName: t('allocation.deliveryDate'), width: 120, sort: 'asc' },
      {
        field: 'criteriaSummary',
        headerName: t('allocation.criteria'),
        flex: 2,
        minWidth: 260,
        tooltipField: 'criteriaSummary',
      },
      {
        field: 'quantityMwh',
        headerName: 'MWh',
        width: 100,
        type: 'rightAligned',
        valueFormatter: (p) => formatDecimal(p.value as number),
      },
      {
        field: 'remainingMwh',
        headerName: t('allocation.remaining'),
        width: 140,
        type: 'rightAligned',
        valueFormatter: (p) => formatDecimal(p.value as number),
        cellStyle: { fontWeight: 600 },
      },
      {
        field: 'kind',
        headerName: t('allocation.kind'),
        width: 140,
        valueFormatter: (p) => t(`allocation.kinds.${String(p.value)}`),
      },
      {
        field: 'status',
        headerName: t('allocation.status'),
        width: 130,
        cellRenderer: (p: ICellRendererParams<Obligation>) => (p.data ? <StatusBadge status={p.data.status} /> : null),
      },
      { field: 'targetAccountRef', headerName: t('allocation.target'), width: 200 },
    ],
    [t],
  );
  const body = { holderEntityId: entityId, idempotencyKey: newKey('ui'), solver };
  return (
    <Stack gap="lg">
      <PageHeader
        title={t('allocation.title')}
        description={t('allocation.description', { entity: entity?.code ?? '' })}
        actions={
          <>
            <Can op="allocation_openObligation">
              <Button
                variant="default"
                leftSection={<IconPlus size={16} />}
                disabled={!entity}
                onClick={() => setOpening(true)}
                data-testid="open-obligation"
              >
                {t('allocation.openObligation')}
              </Button>
            </Can>
            <Can op="allocation_propose">
              <SegmentedControl
                value={solver}
                onChange={setSolver}
                data={[
                  { value: 'GREEDY', label: t('allocation.solvers.GREEDY') },
                  { value: 'EXACT', label: t('allocation.solvers.EXACT') },
                ]}
                aria-label={t('allocation.solver')}
                data-testid="solver"
              />
              <Button
                variant="default"
                leftSection={<IconFlask size={16} />}
                loading={whatIf.isPending}
                onClick={() => whatIf.mutate({ body })}
                data-testid="what-if"
              >
                {t('allocation.whatIf')}
              </Button>
              <Button
                leftSection={<IconSend size={16} />}
                loading={propose.isPending}
                onClick={() =>
                  propose.mutate(
                    { body },
                    {
                      onSuccess: (plan) => {
                        notifySuccess(t('allocation.proposed'));
                        void navigate(`/allocation/plans/${plan.id}`);
                      },
                    },
                  )
                }
                data-testid="propose"
              >
                {t('allocation.propose')}
              </Button>
            </Can>
          </>
        }
      />
      <ProblemAlert error={whatIf.error ?? propose.error} />
      <QueryState isPending={false} error={coverage.error}>
        <SimpleGrid cols={{ base: 1, xs: 2, lg: 4 }} spacing="md" data-testid="coverage-kpis">
          <KpiCard
            label={t('allocation.demand')}
            value={coverage.data ? formatDecimal(coverage.data.totalDemandMwh) : '—'}
            unit="MWh"
            loading={coverage.isPending && Boolean(entity)}
            trend={t('allocation.demandHint')}
          />
          <KpiCard
            label={t('allocation.covered')}
            value={coverage.data ? formatDecimal(coverage.data.totalCoveredMwh) : '—'}
            unit="MWh"
            loading={coverage.isPending && Boolean(entity)}
            trend={t('allocation.coveredHint')}
            trendTone="up"
          />
          <KpiCard
            label={t('allocation.shortfallLabel')}
            value={coverage.data ? formatDecimal(coverage.data.totalShortfallMwh) : '—'}
            unit="MWh"
            loading={coverage.isPending && Boolean(entity)}
            trend={
              coverage.data && coverage.data.totalShortfallMwh > 0
                ? t('allocation.shortfallHint')
                : t('allocation.noShortfall')
            }
            trendTone={coverage.data && coverage.data.totalShortfallMwh > 0 ? 'down' : 'up'}
          />
          <KpiCard
            label={t('allocation.obligationCount')}
            value={obligations.data ? obligations.data.length : '—'}
            loading={obligations.isPending && Boolean(entity)}
            trend={
              active
                ? t('allocation.activePlan', {
                    mwh: formatDecimal(active.allocatedMwh ?? 0),
                    status: statusLabel(active.status).toLowerCase(),
                  })
                : t('allocation.noActivePlan')
            }
            trendTone={active ? 'warning' : 'neutral'}
          />
        </SimpleGrid>
      </QueryState>
      {whatIf.data && <WhatIfResult result={whatIf.data} active={active} obligations={byId} />}
      <Grid gap="md">
        <Grid.Col span={{ base: 12, lg: 7 }}>
          {coverage.data ? (
            <CoverageChart coverage={coverage.data} obligations={obligations.data ?? []} />
          ) : (
            <CardSkeleton height={260} />
          )}
        </Grid.Col>
        <Grid.Col span={{ base: 12, lg: 5 }}>
          <QueryState isPending={false} error={plans.error}>
            {plans.data ? (
              <PlanList plans={plans.data} onOpen={(plan) => void navigate(`/allocation/plans/${plan.id}`)} />
            ) : (
              <CardSkeleton height={260} />
            )}
          </QueryState>
        </Grid.Col>
      </Grid>
      <Stack gap="xs">
        <Title order={3} size="h5">
          {t('allocation.obligations')}
        </Title>
        <QueryState isPending={false} error={obligations.error}>
          {obligations.isPending && entity ? (
            <Paper withBorder radius="lg">
              <TableSkeleton rows={5} />
            </Paper>
          ) : (obligations.data ?? []).length === 0 ? (
            <Paper withBorder radius="lg">
              <EmptyState title={t('allocation.noObligations')} description={t('allocation.noObligationsHint')} />
            </Paper>
          ) : (
            <DataGrid<Obligation>
              rowData={obligations.data ?? []}
              columnDefs={obligationColumns}
              rowId={(o) => o.id}
              height={Math.min(420, 46 + (obligations.data ?? []).length * 40 + 2)}
              onRowClicked={(event) => {
                if (event.data) showObligation(event.data.id);
              }}
              rowStyle={{ cursor: 'pointer' }}
              overlayNoRowsTemplate={t('allocation.noObligations')}
            />
          )}
        </QueryState>
      </Stack>
      <ObligationDrawer obligationId={selectedObligation} onClose={() => showObligation(null)} />
      {opening && entity && (
        <OpenObligationModal
          entityId={entity.id}
          onClose={() => setOpening(false)}
          onOpened={(obligation) => {
            setOpening(false);
            showObligation(obligation.id);
          }}
        />
      )}
    </Stack>
  );
}
