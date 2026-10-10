import { Anchor, Button, Card, Group, Modal, SimpleGrid, Stack, Text, Textarea, Title } from '@mantine/core';
import { IconArrowLeft, IconInbox } from '@tabler/icons-react';
import { useQueryClient } from '@tanstack/react-query';
import { useMemo, useState, type ReactNode } from 'react';
import { useTranslation } from 'react-i18next';
import { Link, useParams } from 'react-router';
import { $api } from '@/shared/api/client';
import { useCan } from '@/shared/auth/Can';
import { notifySuccess } from '@/shared/components/notify';
import { ProblemAlert } from '@/shared/components/ProblemAlert';
import { QueryState } from '@/shared/components/QueryState';
import { formatDateTime, formatDecimal } from '@/shared/format/format';
import { LinesByObligation, PlanStatusBadge } from './AllocationPage';

function Field({ label, children }: { label: string; children: ReactNode }) {
  return (
    <Stack gap={0}>
      <Text size="xs" c="dimmed">
        {label}
      </Text>
      <Text size="sm" component="div">
        {children ?? '—'}
      </Text>
    </Stack>
  );
}

export function PlanDetailPage() {
  const { t } = useTranslation();
  const { planId = '' } = useParams();
  const queryClient = useQueryClient();
  const plan = $api.useQuery('get', '/api/v1/allocation/plans/{id}', { params: { path: { id: planId } } });
  const holder = plan.data?.holderEntityId ?? '';
  const obligations = $api.useQuery(
    'get',
    '/api/v1/allocation/obligations',
    { params: { query: { holderEntityId: holder } } },
    { enabled: Boolean(holder) },
  );
  const task = $api.useQuery(
    'get',
    '/api/v1/workflow/tasks',
    { params: { query: { subjectRef: planId, type: ['ALLOCATION_APPROVAL'] } } },
    { enabled: plan.data?.status === 'PROPOSED' },
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

  return (
    <Stack>
      <Anchor component={Link} to="/allocation" size="sm">
        <Group gap={4}>
          <IconArrowLeft size={14} />
          {t('allocation.back')}
        </Group>
      </Anchor>
      <QueryState isPending={plan.isPending} error={plan.error}>
        {plan.data && (
          <Stack>
            <Card withBorder>
              <Group justify="space-between" align="flex-start">
                <Group gap="xs">
                  <Title order={3}>
                    {t('allocation.planTitle', { mwh: formatDecimal(plan.data.allocatedMwh ?? 0) })}
                  </Title>
                  <PlanStatusBadge status={plan.data.status} />
                </Group>
                <Group>
                  {approvalTask && (
                    <Button
                      component={Link}
                      to={`/inbox/${approvalTask.id}`}
                      variant="light"
                      leftSection={<IconInbox size={16} />}
                      data-testid="plan-approval-task"
                    >
                      {t('allocation.approvalTask')}
                    </Button>
                  )}
                  {canCancel && ['PROPOSED', 'APPROVED'].includes(plan.data.status) && (
                    <Button color="red" variant="light" onClick={() => setCancelling(true)}>
                      {t('allocation.cancel')}
                    </Button>
                  )}
                </Group>
              </Group>
              <SimpleGrid cols={{ base: 2, md: 4 }} mt="md">
                <Field
                  label={t('allocation.proposedBy')}
                >{`${plan.data.proposedBy} · ${formatDateTime(plan.data.proposedAt)}`}</Field>
                <Field label={t('allocation.approvedBy')}>
                  {plan.data.approvedBy ? `${plan.data.approvedBy} · ${formatDateTime(plan.data.approvedAt)}` : null}
                </Field>
                <Field label={t('allocation.reservedUntil')}>{formatDateTime(plan.data.reservationExpiresAt)}</Field>
                <Field label={t('allocation.solver')}>{plan.data.solver}</Field>
                <Field label={t('allocation.snapshot')}>{plan.data.snapshotSeq}</Field>
                <Field label={t('allocation.shortfallLabel')}>
                  {plan.data.coverage ? `${formatDecimal(plan.data.coverage.totalShortfallMwh)} MWh` : null}
                </Field>
                <Field label={t('allocation.decision')}>{plan.data.decisionReason}</Field>
              </SimpleGrid>
            </Card>
            <LinesByObligation lines={plan.data.lines} obligations={byId} />
          </Stack>
        )}
      </QueryState>
      <Modal opened={cancelling} onClose={() => setCancelling(false)} title={t('allocation.cancel')}>
        <Stack>
          <ProblemAlert error={cancel.error} />
          <Textarea
            label={t('inbox.reason')}
            required
            value={reason}
            onChange={(e) => setReason(e.currentTarget.value)}
          />
          <Button
            color="red"
            disabled={!reason.trim()}
            loading={cancel.isPending}
            onClick={() => cancel.mutate({ params: { path: { id: planId } }, body: { reason: reason.trim() } })}
          >
            {t('allocation.cancel')}
          </Button>
        </Stack>
      </Modal>
    </Stack>
  );
}
