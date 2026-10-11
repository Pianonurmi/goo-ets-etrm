import {
  Alert,
  Badge,
  Button,
  Card,
  Group,
  SegmentedControl,
  SimpleGrid,
  Stack,
  Table,
  Text,
  Title,
} from '@mantine/core';
import { IconFlask, IconSend } from '@tabler/icons-react';
import type { ColDef, ICellRendererParams } from 'ag-grid-community';
import { useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useNavigate } from 'react-router';
import { $api } from '@/shared/api/client';
import type { Body, Schema } from '@/shared/api/types';
import { Can } from '@/shared/auth/Can';
import { notifySuccess } from '@/shared/components/notify';
import { PageHeader } from '@/shared/components/PageHeader';
import { ProblemAlert } from '@/shared/components/ProblemAlert';
import { QueryState } from '@/shared/components/QueryState';
import { useEntity } from '@/shared/context/EntityContext';
import { formatDate, formatDateTime, formatDecimal } from '@/shared/format/format';
import { DataGrid } from '@/shared/grid/DataGrid';
import { newKey } from '@/shared/ids';
import { groupLines, type Line } from './model/plan';

type Obligation = Schema<'ObligationView'>;
type Plan = Schema<'AllocationPlanView'>;
type WhatIf = Schema<'AllocationWhatIf'>;
type Solver = Body<'ProposeAllocation'>['solver'] & string;

export function PlanStatusBadge({ status }: { status: string }) {
  const colors: Record<string, string> = {
    PROPOSED: 'yellow',
    APPROVED: 'blue',
    EXECUTING: 'cyan',
    COMPLETED: 'teal',
    REJECTED: 'red',
    CANCELLED: 'gray',
    EXPIRED: 'gray',
    STALE: 'orange',
  };
  return (
    <Badge color={colors[status] ?? 'gray'} variant="light" data-testid="plan-status">
      {status}
    </Badge>
  );
}

function Figure({ label, value, color }: { label: string; value: string; color?: string }) {
  return (
    <Card withBorder>
      <Text size="xs" c="dimmed">
        {label}
      </Text>
      <Text size="xl" fw={700} c={color}>
        {value}
      </Text>
    </Card>
  );
}

/** Lines of a computed or proposed plan, one table per obligation. */
export function LinesByObligation({ lines, obligations }: { lines: Line[]; obligations: Map<string, Obligation> }) {
  const { t } = useTranslation();
  return (
    <Stack gap="sm">
      {groupLines(lines).map((group) => {
        const obligation = obligations.get(group.obligationId);
        return (
          <Card withBorder key={group.obligationId}>
            <Group justify="space-between" mb="xs">
              <Text fw={600} size="sm">
                {obligation?.criteriaSummary ?? group.obligationId.slice(-8)}
              </Text>
              <Group gap="xs">
                {obligation && (
                  <Badge variant="outline">{t('allocation.due', { date: formatDate(obligation.deliveryDate) })}</Badge>
                )}
                <Badge variant="light">
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
                  <Table.Th ta="right">{t('allocation.score')}</Table.Th>
                  <Table.Th>{t('allocation.bucket')}</Table.Th>
                </Table.Tr>
              </Table.Thead>
              <Table.Tbody>
                {group.lines.map((line) => (
                  <Table.Tr key={`${line.lotId}-${line.lineNo}`}>
                    <Table.Td>{line.lotId.slice(-8)}</Table.Td>
                    <Table.Td>
                      <Text size="xs" ff="monospace">
                        {line.lotClassKey.slice(0, 40)}
                      </Text>
                    </Table.Td>
                    <Table.Td ta="right">{formatDecimal(line.quantityMwh)}</Table.Td>
                    <Table.Td ta="right">{formatDecimal(line.score, 2, 4)}</Table.Td>
                    <Table.Td>{line.breakdown.bucketCode ?? '—'}</Table.Td>
                  </Table.Tr>
                ))}
              </Table.Tbody>
            </Table>
          </Card>
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
  const allocated = Number(result.objective.allocatedMwh ?? 0);
  const { solver, fallbackReason } = result.objective;
  const solvedBy = typeof solver === 'string' ? solver : result.solver;
  const fallback = typeof fallbackReason === 'string' ? fallbackReason : undefined;
  return (
    <Card withBorder data-testid="what-if-result">
      <Group justify="space-between" mb="sm">
        <Title order={5}>{t('allocation.whatIfTitle', { at: formatDateTime(result.computedAt) })}</Title>
        <Group gap="xs">
          <Badge variant="light" color="teal" data-testid="what-if-allocated">
            {t('allocation.allocated', { mwh: formatDecimal(allocated) })}
          </Badge>
          <Badge variant="outline" color="gray" data-testid="what-if-solver">
            {t('allocation.solvedBy', { solver: solvedBy })}
          </Badge>
          {active && (
            <Badge variant="outline">
              {t('allocation.activePlan', { mwh: formatDecimal(active.allocatedMwh ?? 0), status: active.status })}
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
      <LinesByObligation lines={result.lines} obligations={obligations} />
    </Card>
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
  const whatIf = $api.useMutation('post', '/api/v1/allocation/plans/what-if', { meta: { notify: false } });
  const propose = $api.useMutation('post', '/api/v1/allocation/plans', { meta: { notify: false } });
  const byId = useMemo(() => new Map((obligations.data ?? []).map((o) => [o.id, o])), [obligations.data]);
  const active = (plans.data ?? []).find((plan) => ['PROPOSED', 'APPROVED', 'EXECUTING'].includes(plan.status));

  const obligationColumns = useMemo<ColDef<Obligation>[]>(
    () => [
      { field: 'criteriaSummary', headerName: t('allocation.criteria'), flex: 2, minWidth: 260 },
      { field: 'deliveryDate', headerName: t('allocation.deliveryDate'), width: 140 },
      { field: 'quantityMwh', headerName: 'MWh', width: 110, type: 'rightAligned' },
      { field: 'remainingMwh', headerName: t('allocation.remaining'), width: 130, type: 'rightAligned' },
      { field: 'kind', headerName: t('allocation.kind'), width: 130 },
      { field: 'status', headerName: t('allocation.status'), width: 120 },
      { field: 'targetAccountRef', headerName: t('allocation.target'), width: 200 },
    ],
    [t],
  );
  const planColumns = useMemo<ColDef<Plan>[]>(
    () => [
      {
        field: 'proposedAt',
        headerName: t('allocation.proposedAt'),
        width: 170,
        valueFormatter: (p) => formatDateTime(p.value as string),
      },
      {
        field: 'status',
        headerName: t('allocation.status'),
        width: 140,
        cellRenderer: (p: ICellRendererParams<Plan>) => (p.data ? <PlanStatusBadge status={p.data.status} /> : null),
      },
      { field: 'allocatedMwh', headerName: 'MWh', width: 110, type: 'rightAligned' },
      { field: 'proposedBy', headerName: t('allocation.proposedBy'), width: 140 },
      { field: 'approvedBy', headerName: t('allocation.approvedBy'), width: 140 },
      { field: 'solver', headerName: t('allocation.solver'), width: 110 },
    ],
    [t],
  );

  const body = { holderEntityId: entityId, idempotencyKey: newKey('ui'), solver };
  return (
    <Stack>
      <PageHeader
        title={t('allocation.title')}
        description={t('allocation.description', { entity: entity?.code ?? '' })}
        actions={
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
              variant="light"
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
        }
      />
      <ProblemAlert error={whatIf.error ?? propose.error} />
      <QueryState isPending={coverage.isPending && Boolean(entity)} error={coverage.error}>
        {coverage.data && (
          <SimpleGrid cols={{ base: 2, md: 4 }}>
            <Figure label={t('allocation.demand')} value={`${formatDecimal(coverage.data.totalDemandMwh)} MWh`} />
            <Figure
              label={t('allocation.covered')}
              value={`${formatDecimal(coverage.data.totalCoveredMwh)} MWh`}
              color="teal"
            />
            <Figure
              label={t('allocation.shortfallLabel')}
              value={`${formatDecimal(coverage.data.totalShortfallMwh)} MWh`}
              color={coverage.data.totalShortfallMwh > 0 ? 'red' : undefined}
            />
            <Figure label={t('allocation.obligationCount')} value={String(coverage.data.obligations.length)} />
          </SimpleGrid>
        )}
      </QueryState>
      {whatIf.data && <WhatIfResult result={whatIf.data} active={active} obligations={byId} />}
      <Title order={4}>{t('allocation.obligations')}</Title>
      <QueryState isPending={obligations.isPending && Boolean(entity)} error={obligations.error}>
        <DataGrid<Obligation>
          rowData={obligations.data ?? []}
          columnDefs={obligationColumns}
          rowId={(o) => o.id}
          height={280}
          overlayNoRowsTemplate={t('allocation.noObligations')}
        />
      </QueryState>
      <Title order={4}>{t('allocation.plans')}</Title>
      <QueryState isPending={plans.isPending && Boolean(entity)} error={plans.error}>
        <DataGrid<Plan>
          rowData={plans.data ?? []}
          columnDefs={planColumns}
          rowId={(p) => p.id}
          height={260}
          onRowClicked={(event) => {
            if (event.data) void navigate(`/allocation/plans/${event.data.id}`);
          }}
          overlayNoRowsTemplate={t('common.empty')}
        />
      </QueryState>
    </Stack>
  );
}
