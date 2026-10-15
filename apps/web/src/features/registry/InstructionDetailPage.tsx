import {
  Alert,
  Anchor,
  Button,
  Group,
  Modal,
  Paper,
  Stack,
  Text,
  TextInput,
  Title,
  UnstyledButton,
} from '@mantine/core';
import { IconAlertTriangle, IconCheck, IconChevronRight, IconInbox, IconRefresh, IconX } from '@tabler/icons-react';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link, useParams } from 'react-router';
import { $api } from '@/shared/api/client';
import { Can } from '@/shared/auth/Can';
import { DetailLayout } from '@/shared/components/DetailLayout';
import { LifecycleTimeline } from '@/shared/components/LifecycleTimeline';
import { notifySuccess } from '@/shared/components/notify';
import { PageHeader } from '@/shared/components/PageHeader';
import { ProblemAlert } from '@/shared/components/ProblemAlert';
import { QueryState } from '@/shared/components/QueryState';
import { ReasonModal } from '@/shared/components/ReasonModal';
import { CardSkeleton } from '@/shared/components/Skeletons';
import { StatusBadge, statusLabel } from '@/shared/components/StatusBadge';
import { formatDateTime } from '@/shared/format/format';
import { instructionActions, instructionSteps, subject, type Instruction } from './model/instructions';
import { InstructionStatusBadge } from './shared';
import { useInvalidateRegistry } from './TransferModal';

/** Records that the registry executed the instruction, with the registry's own reference. */
function ConfirmModal({ instruction, onClose }: { instruction: Instruction; onClose: () => void }) {
  const { t } = useTranslation();
  const invalidate = useInvalidateRegistry();
  const [externalRef, setExternalRef] = useState(instruction.externalRef ?? '');
  const confirm = $api.useMutation('post', '/api/v1/registry/instructions/{id}/confirm', {
    meta: { notify: false },
    onSuccess: () => {
      notifySuccess(t('registry.confirmed'));
      void invalidate();
      onClose();
    },
  });
  return (
    <Modal opened onClose={onClose} title={t('registry.confirmTitle')}>
      <Stack>
        <ProblemAlert error={confirm.error} />
        <Text size="sm" c="dimmed">
          {t('registry.confirmHint')}
        </Text>
        <TextInput
          label={t('registry.externalRef')}
          value={externalRef}
          onChange={(e) => setExternalRef(e.currentTarget.value)}
          data-testid="confirm-reference"
        />
        <Button
          loading={confirm.isPending}
          data-testid="confirm-submit"
          onClick={() =>
            confirm.mutate({
              params: { path: { id: instruction.id } },
              body: { externalRef: externalRef.trim() || undefined },
            })
          }
        >
          {t('registry.confirm')}
        </Button>
      </Stack>
    </Modal>
  );
}

/** One instruction: what it moves where, its lifecycle, its approval and follow-up tasks, and operator actions. */
export function InstructionDetailPage() {
  const { t } = useTranslation();
  const { instructionId = '' } = useParams();
  const invalidate = useInvalidateRegistry();
  const [dialog, setDialog] = useState<'confirm' | 'fail' | 'cancel' | null>(null);
  const path = { params: { path: { id: instructionId } } };
  const instruction = $api.useQuery('get', '/api/v1/registry/instructions/{id}', path);
  const tasks = $api.useQuery('get', '/api/v1/workflow/tasks', {
    params: { query: { subjectRef: instructionId, type: ['INSTRUCTION_APPROVAL', 'INSTRUCTION_FOLLOW_UP'] } },
  });
  const done = (message: string) => () => {
    notifySuccess(message);
    setDialog(null);
    void invalidate();
  };
  const fail = $api.useMutation('post', '/api/v1/registry/instructions/{id}/fail', {
    meta: { notify: false },
    onSuccess: done(t('registry.failed')),
  });
  const cancel = $api.useMutation('post', '/api/v1/registry/instructions/{id}/cancel', {
    meta: { notify: false },
    onSuccess: done(t('registry.cancelled')),
  });
  const retry = $api.useMutation('post', '/api/v1/registry/instructions/{id}/retry', {
    onSuccess: done(t('registry.resubmitted')),
  });
  const data = instruction.data;
  if (!data) {
    return (
      <QueryState isPending={false} error={instruction.error}>
        <Stack gap="lg">
          <CardSkeleton height={90} />
          <CardSkeleton height={200} />
        </Stack>
      </QueryState>
    );
  }
  const actions = instructionActions(data);
  const allTasks = tasks.data?.items ?? [];
  const openTasks = allTasks.filter((task) => task.status === 'OPEN');
  return (
    <Stack gap="lg">
      <PageHeader
        breadcrumbs={[{ label: t('registry.title'), to: '/registry' }, { label: data.instructionKey }]}
        title={data.instructionKey}
        badge={<InstructionStatusBadge status={data.status} testId="instruction-status" />}
        description={`${t(`registry.types.${data.type}`)} · ${subject(data)} · ${t('registry.viaChannel', {
          channel: statusLabel(data.channel ?? 'MANUAL'),
        })}`}
        actions={
          <>
            {actions.cancel && (
              <Can op="registry_cancel">
                <Button
                  variant="subtle"
                  color="red"
                  leftSection={<IconX size={16} />}
                  onClick={() => setDialog('cancel')}
                  data-testid="instruction-cancel"
                >
                  {t('registry.cancel')}
                </Button>
              </Can>
            )}
            {actions.fail && (
              <Can op="registry_fail">
                <Button variant="subtle" color="red" onClick={() => setDialog('fail')} data-testid="instruction-fail">
                  {t('registry.markFailed')}
                </Button>
              </Can>
            )}
            {actions.retry && (
              <Can op="registry_retry">
                <Button
                  variant="default"
                  leftSection={<IconRefresh size={16} />}
                  loading={retry.isPending}
                  onClick={() => retry.mutate(path)}
                  data-testid="instruction-retry"
                >
                  {t('registry.retry')}
                </Button>
              </Can>
            )}
            {openTasks.map((task) => (
              <Button
                key={task.id}
                component={Link}
                to={`/inbox/${task.id}`}
                variant={actions.confirm ? 'light' : 'filled'}
                leftSection={<IconInbox size={16} />}
                data-testid={`task-${task.type}`}
              >
                {t(`registry.tasks.${task.type}`)}
              </Button>
            ))}
            {actions.confirm && (
              <Can op="registry_confirm">
                <Button
                  leftSection={<IconCheck size={16} />}
                  onClick={() => setDialog('confirm')}
                  data-testid="instruction-confirm"
                >
                  {t('registry.confirm')}
                </Button>
              </Can>
            )}
          </>
        }
      />
      <DetailLayout
        summary={[
          {
            label: t('registry.what'),
            value: (
              <Text span fw={600} size="sm">
                {subject(data)}
              </Text>
            ),
          },
          { label: t('registry.fromRegistry'), value: data.registryCode },
          {
            label: t('registry.toRegistry'),
            value: `${data.toRegistryCode ?? '—'} ${data.toAccountRef ?? ''}`.trim(),
          },
          {
            label: t('registry.lot'),
            value: data.lotId ? (
              <Anchor component={Link} to={`/inventory/lots/${data.lotId}`} size="sm">
                {t('registry.openLot')}
              </Anchor>
            ) : (
              (data.productCode ?? '—')
            ),
          },
          {
            label: t('registry.plan'),
            value: data.planRef ? (
              <Anchor component={Link} to={`/allocation/plans/${data.planRef}`} size="sm">
                {t('registry.openPlan')}
              </Anchor>
            ) : (
              t('registry.unplanned')
            ),
          },
          { label: t('registry.externalRef'), value: data.externalRef ?? '—' },
          { label: t('registry.attempts'), value: data.attempts },
          { label: t('registry.nextAttempt'), value: formatDateTime(data.nextAttemptAt) || '—' },
          { label: t('registry.expectedEffective'), value: formatDateTime(data.expectedEffectiveAt) || '—' },
        ]}
        aside={
          <LifecycleTimeline
            title={t('registry.lifecycle')}
            steps={instructionSteps(data, (key) => t(`registry.steps.${key}`))}
            testId="instruction-lifecycle"
          />
        }
      >
        {data.lastError && (
          <Alert
            color={data.status === 'FAILED' ? 'red' : 'orange'}
            icon={<IconAlertTriangle size={18} />}
            title={data.status === 'CANCELLED' ? t('registry.cancelReason') : t('registry.registrySaid')}
            data-testid="instruction-error"
          >
            {data.lastError}
          </Alert>
        )}
        <Paper withBorder radius="lg" p="lg">
          <Title order={3} size="h5">
            {t('registry.relatedTasks')}
          </Title>
          <Text size="sm" c="dimmed" mb="sm">
            {t('registry.relatedTasksHint')}
          </Text>
          {allTasks.length === 0 ? (
            <Text size="sm" c="dimmed">
              {t('registry.noTasks')}
            </Text>
          ) : (
            <Stack gap={4}>
              {allTasks.map((task) => (
                <UnstyledButton
                  key={task.id}
                  component={Link}
                  to={`/inbox/${task.id}`}
                  className="etrm-task-row"
                  p="xs"
                >
                  <Group justify="space-between" wrap="nowrap">
                    <Stack gap={0} miw={0}>
                      <Text size="sm" fw={500}>
                        {t(`registry.tasks.${task.type}`)}
                      </Text>
                      <Text size="xs" c="dimmed" truncate>
                        {task.requestedBy} · {formatDateTime(task.requestedAt)}
                      </Text>
                    </Stack>
                    <Group gap="xs" wrap="nowrap">
                      <StatusBadge status={task.status} />
                      <IconChevronRight size={16} />
                    </Group>
                  </Group>
                </UnstyledButton>
              ))}
            </Stack>
          )}
        </Paper>
      </DetailLayout>
      {dialog === 'confirm' && <ConfirmModal instruction={data} onClose={() => setDialog(null)} />}
      {dialog === 'fail' && (
        <ReasonModal
          title={t('registry.markFailed')}
          hint={t('registry.failHint')}
          action={t('registry.markFailed')}
          pending={fail.isPending}
          error={fail.error}
          onSubmit={(reason) => fail.mutate({ ...path, body: { reason } })}
          onClose={() => setDialog(null)}
        />
      )}
      {dialog === 'cancel' && (
        <ReasonModal
          title={t('registry.cancel')}
          hint={t('registry.cancelHint')}
          action={t('registry.cancel')}
          pending={cancel.isPending}
          error={cancel.error}
          onSubmit={(reason) => cancel.mutate({ ...path, body: { reason } })}
          onClose={() => setDialog(null)}
        />
      )}
    </Stack>
  );
}
