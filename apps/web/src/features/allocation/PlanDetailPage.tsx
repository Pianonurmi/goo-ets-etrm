import { Button, Group, Modal, Stack, Tabs, Text, Textarea } from '@mantine/core';
import { IconInbox, IconX } from '@tabler/icons-react';
import { useQueryClient } from '@tanstack/react-query';
import { useCallback, useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link, useParams } from 'react-router';
import { $api } from '@/shared/api/client';
import type { Schema } from '@/shared/api/types';
import { useCan } from '@/shared/auth/Can';
import { DetailLayout } from '@/shared/components/DetailLayout';
import { Quantity } from '@/shared/components/Figures';
import { LifecycleTimeline } from '@/shared/components/LifecycleTimeline';
import { notifySuccess } from '@/shared/components/notify';
import { PageHeader } from '@/shared/components/PageHeader';
import { ProblemAlert } from '@/shared/components/ProblemAlert';
import { QueryState } from '@/shared/components/QueryState';
import { CardSkeleton } from '@/shared/components/Skeletons';
import { formatDateTime, formatDecimal } from '@/shared/format/format';
import { LinesByObligation, PlanStatusBadge } from './AllocationPage';
import { hasFutureCoverage, obligationName, planSteps } from './model/plan';
import { PlanValuePanel } from './PlanValuePanel';
import { ShadowPricesPanel } from './ShadowPricesPanel';
import { VirtualLinesPanel } from './VirtualLinesPanel';

type Plan = Schema<'AllocationPlanView'>;

function PlanBody({ plan }: { plan: Plan }) {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const obligations = $api.useQuery('get', '/api/v1/allocation/obligations', {
    params: { query: { holderEntityId: plan.holderEntityId } },
  });
  const task = $api.useQuery(
    'get',
    '/api/v1/workflow/tasks',
    { params: { query: { subjectRef: plan.id, type: ['ALLOCATION_APPROVAL'] } } },
    { enabled: plan.status === 'PROPOSED' },
  );
  const canCancel = useCan('allocation_cancel');
  const [cancelling, setCancelling] = useState(false);
  const [reason, setReason] = useState('');
  const cancel = $api.useMutation('post', '/api/v1/allocation/plans/{id}/cancel', {
    meta: { notify: false },
    onSuccess: () => {
      notifySuccess(t('allocation.cancelled'));
      setCancelling(false);
      void queryClient.invalidateQueries({
        predicate: (q) => typeof q.queryKey[1] === 'string' && q.queryKey[1].startsWith('/api/v1/allocation'),
      });
    },
  });
  const byId = useMemo(() => new Map((obligations.data ?? []).map((o) => [o.id, o])), [obligations.data]);
  const obligationLabel = useCallback((id: string) => obligationName(byId.get(id)), [byId]);
  const approvalTask = task.data?.items[0];
  const futureMwh = (plan.virtualLines ?? []).reduce((sum, line) => sum + line.quantityMwh, 0);
  const title =
    futureMwh > 0
      ? t('allocation.planTitleFuture', {
          mwh: formatDecimal(plan.allocatedMwh ?? 0),
          future: formatDecimal(futureMwh),
        })
      : t('allocation.planTitle', { mwh: formatDecimal(plan.allocatedMwh ?? 0) });
  const shortfall = plan.coverage?.totalShortfallMwh;

  const lines =
    plan.lines.length === 0 ? (
      <Text size="sm" c="dimmed">
        {t('allocation.noLines')}
      </Text>
    ) : (
      <LinesByObligation lines={plan.lines} obligations={byId} />
    );
  const virtualLines = plan.virtualLines ?? [];
  const hasVirtual = virtualLines.length > 0 || hasFutureCoverage(plan.coverage);
  // Plans made before value-based allocation carry neither value nor shadow prices; they show the lines alone.
  const body =
    plan.value || plan.pricing || hasVirtual ? (
      <Tabs defaultValue="lines" keepMounted={false}>
        <Tabs.List>
          <Tabs.Tab value="lines" data-testid="plan-lines-tab">
            {t('allocation.lines')}
          </Tabs.Tab>
          {hasVirtual && (
            <Tabs.Tab value="virtual" data-testid="plan-virtual-lines-tab">
              {t('allocation.virtual.title')}
            </Tabs.Tab>
          )}
          {plan.value && (
            <Tabs.Tab value="value" data-testid="plan-value-tab">
              {t('allocation.value.title')}
            </Tabs.Tab>
          )}
          {plan.pricing && (
            <Tabs.Tab value="shadow-prices" data-testid="plan-shadow-prices">
              {t('allocation.shadow.title')}
            </Tabs.Tab>
          )}
        </Tabs.List>
        <Tabs.Panel value="lines" pt="md">
          {lines}
        </Tabs.Panel>
        {hasVirtual && (
          <Tabs.Panel value="virtual" pt="md">
            <VirtualLinesPanel
              lines={virtualLines}
              coverage={plan.coverage}
              obligations={byId}
              testId="plan-virtual-lines"
            />
          </Tabs.Panel>
        )}
        {plan.value && (
          <Tabs.Panel value="value" pt="md">
            <PlanValuePanel
              value={plan.value}
              pricing={plan.pricing}
              obligationLabel={obligationLabel}
              kpiTestId="plan-value-kpis"
              gridTestId="plan-give-away"
            />
          </Tabs.Panel>
        )}
        {plan.pricing && (
          <Tabs.Panel value="shadow-prices" pt="md">
            <ShadowPricesPanel
              pricing={plan.pricing}
              obligationLabel={obligationLabel}
              kpiTestId="plan-shadow-kpis"
              obligationsTestId="plan-shadow-obligations"
              supplyTestId="plan-shadow-supply"
            />
          </Tabs.Panel>
        )}
      </Tabs>
    ) : (
      lines
    );

  return (
    <Stack gap="lg">
      <PageHeader
        breadcrumbs={[{ label: t('allocation.title'), to: '/allocation' }, { label: title }]}
        title={title}
        badge={<PlanStatusBadge status={plan.status} />}
        description={t('allocation.planLead', {
          by: plan.proposedBy,
          at: formatDateTime(plan.proposedAt),
          solver: t(`allocation.solvers.${plan.solver}`),
        })}
        actions={
          <>
            {canCancel && ['PROPOSED', 'APPROVED'].includes(plan.status) && (
              <Button
                variant="subtle"
                color="red"
                leftSection={<IconX size={16} />}
                onClick={() => setCancelling(true)}
                data-testid="plan-cancel"
              >
                {t('allocation.cancel')}
              </Button>
            )}
            {approvalTask && (
              <Button
                component={Link}
                to={`/inbox/${approvalTask.id}`}
                leftSection={<IconInbox size={16} />}
                data-testid="plan-approval-task"
              >
                {t('allocation.approvalTask')}
              </Button>
            )}
          </>
        }
      />
      <DetailLayout
        summary={[
          {
            label: t('allocation.allocatedLabel'),
            value: <Quantity value={plan.allocatedMwh ?? 0} unit="MWh" fw={600} />,
          },
          ...(futureMwh > 0
            ? [
                {
                  label: t('allocation.virtual.countedOn'),
                  value: <Quantity value={futureMwh} unit="MWh" fw={600} data-testid="plan-future-mwh" />,
                },
              ]
            : []),
          {
            label: t(futureMwh > 0 ? 'allocation.virtual.shortOfHoldings' : 'allocation.shortfallLabel'),
            value:
              shortfall != null ? <Quantity value={shortfall} unit="MWh" c={shortfall > 0 ? 'red' : undefined} /> : '—',
          },
          { label: t('allocation.reservedUntil'), value: formatDateTime(plan.reservationExpiresAt) },
          { label: t('allocation.lineCount'), value: plan.lines.length },
          { label: t('allocation.solver'), value: t(`allocation.solvers.${plan.solver}`) },
          { label: t('allocation.snapshot'), value: plan.snapshotSeq },
          { label: t('allocation.approvedBy'), value: plan.approvedBy ?? '—' },
          { label: t('allocation.decision'), value: plan.decisionReason ?? '—' },
        ]}
        aside={
          <LifecycleTimeline
            title={t('allocation.lifecycle.title')}
            steps={planSteps(plan, (key) => t(`allocation.lifecycle.${key}`))}
            testId="plan-lifecycle"
          />
        }
      >
        {body}
      </DetailLayout>
      <Modal opened={cancelling} onClose={() => setCancelling(false)} title={t('allocation.cancel')}>
        <Stack>
          <Text size="sm" c="dimmed">
            {t('allocation.cancelHint')}
          </Text>
          <ProblemAlert error={cancel.error} />
          <Textarea
            label={t('inbox.reason')}
            required
            value={reason}
            onChange={(e) => setReason(e.currentTarget.value)}
          />
          <Group justify="flex-end">
            <Button variant="default" onClick={() => setCancelling(false)}>
              {t('common.cancel')}
            </Button>
            <Button
              color="red"
              disabled={!reason.trim()}
              loading={cancel.isPending}
              onClick={() => cancel.mutate({ params: { path: { id: plan.id } }, body: { reason: reason.trim() } })}
            >
              {t('allocation.cancel')}
            </Button>
          </Group>
        </Stack>
      </Modal>
    </Stack>
  );
}

export function PlanDetailPage() {
  const { planId = '' } = useParams();
  const plan = $api.useQuery('get', '/api/v1/allocation/plans/{id}', { params: { path: { id: planId } } });
  return (
    <QueryState isPending={false} error={plan.error}>
      {plan.data ? (
        <PlanBody plan={plan.data} />
      ) : (
        <Stack gap="lg">
          <CardSkeleton height={90} />
          <CardSkeleton height={240} />
        </Stack>
      )}
    </QueryState>
  );
}
