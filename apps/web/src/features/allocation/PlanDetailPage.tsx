import { Button, Group, Modal, Stack, Text, Textarea } from '@mantine/core';
import { IconInbox, IconX } from '@tabler/icons-react';
import { useQueryClient } from '@tanstack/react-query';
import { useMemo, useState } from 'react';
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
import { planSteps } from './model/plan';

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
  const approvalTask = task.data?.items[0];
  const title = t('allocation.planTitle', { mwh: formatDecimal(plan.allocatedMwh ?? 0) });
  const shortfall = plan.coverage?.totalShortfallMwh;

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
          {
            label: t('allocation.shortfallLabel'),
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
        {plan.lines.length === 0 ? (
          <Text size="sm" c="dimmed">
            {t('allocation.noLines')}
          </Text>
        ) : (
          <LinesByObligation lines={plan.lines} obligations={byId} />
        )}
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
