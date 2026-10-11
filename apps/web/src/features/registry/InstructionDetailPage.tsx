import {
  Alert,
  Anchor,
  Button,
  Card,
  Group,
  Modal,
  SimpleGrid,
  Stack,
  Text,
  TextInput,
  Timeline,
  Title,
} from '@mantine/core';
import { IconArrowLeft, IconCheck, IconInbox, IconRefresh, IconX } from '@tabler/icons-react';
import { useState, type ReactNode } from 'react';
import { useTranslation } from 'react-i18next';
import { Link, useParams } from 'react-router';
import { $api } from '@/shared/api/client';
import { Can } from '@/shared/auth/Can';
import { notifySuccess } from '@/shared/components/notify';
import { ProblemAlert } from '@/shared/components/ProblemAlert';
import { QueryState } from '@/shared/components/QueryState';
import { ReasonModal } from '@/shared/components/ReasonModal';
import { formatDateTime } from '@/shared/format/format';
import { instructionActions, lifecycle, subject, type Instruction } from './model/instructions';
import { InstructionStatusBadge } from './RegistryPage';
import { useInvalidateRegistry } from './TransferModal';

function Field({ label, children }: { label: string; children: ReactNode }) {
  return (
    <Stack gap={0}>
      <Text size="xs" c="dimmed">
        {label}
      </Text>
      <Text size="sm" component="div">
        {children}
      </Text>
    </Stack>
  );
}

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
  const actions = data ? instructionActions(data) : null;
  const openTasks = (tasks.data?.items ?? []).filter((task) => task.status === 'OPEN');
  return (
    <Stack>
      <Anchor component={Link} to="/registry" size="sm">
        <Group gap={4}>
          <IconArrowLeft size={14} />
          {t('registry.back')}
        </Group>
      </Anchor>
      <QueryState isPending={instruction.isPending} error={instruction.error}>
        {data && actions && (
          <>
            <Card withBorder>
              <Group justify="space-between" align="flex-start">
                <Stack gap={4}>
                  <Group gap="xs">
                    <Title order={3}>{data.instructionKey}</Title>
                    <InstructionStatusBadge status={data.status} testId="instruction-status" />
                  </Group>
                  <Text size="sm" c="dimmed">
                    {t(`registry.types.${data.type}`)} · {subject(data)} ·{' '}
                    {t('registry.viaChannel', { channel: data.channel })}
                  </Text>
                </Stack>
                <Group gap="xs">
                  {openTasks.map((task) => (
                    <Button
                      key={task.id}
                      component={Link}
                      to={`/inbox/${task.id}`}
                      variant="light"
                      leftSection={<IconInbox size={16} />}
                      data-testid={`task-${task.type}`}
                    >
                      {t(`registry.tasks.${task.type}`)}
                    </Button>
                  ))}
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
                  {actions.retry && (
                    <Can op="registry_retry">
                      <Button
                        variant="light"
                        leftSection={<IconRefresh size={16} />}
                        loading={retry.isPending}
                        onClick={() => retry.mutate(path)}
                        data-testid="instruction-retry"
                      >
                        {t('registry.retry')}
                      </Button>
                    </Can>
                  )}
                  {actions.fail && (
                    <Can op="registry_fail">
                      <Button
                        variant="light"
                        color="red"
                        onClick={() => setDialog('fail')}
                        data-testid="instruction-fail"
                      >
                        {t('registry.markFailed')}
                      </Button>
                    </Can>
                  )}
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
                </Group>
              </Group>
              {data.lastError && (
                <Alert color={data.status === 'FAILED' ? 'red' : 'orange'} mt="md" data-testid="instruction-error">
                  {data.lastError}
                </Alert>
              )}
              <SimpleGrid cols={{ base: 2, md: 4 }} mt="md">
                <Field label={t('registry.fromRegistry')}>{data.registryCode}</Field>
                <Field label={t('registry.toRegistry')}>
                  {data.toRegistryCode ?? '—'} {data.toAccountRef ?? ''}
                </Field>
                <Field label={t('registry.lot')}>
                  {data.lotId ? (
                    <Anchor component={Link} to={`/inventory/lots/${data.lotId}`} size="sm">
                      {t('registry.openLot')}
                    </Anchor>
                  ) : (
                    (data.productCode ?? '—')
                  )}
                </Field>
                <Field label={t('registry.plan')}>
                  {data.planRef ? (
                    <Anchor component={Link} to={`/allocation/plans/${data.planRef}`} size="sm">
                      {t('registry.openPlan')}
                    </Anchor>
                  ) : (
                    t('registry.unplanned')
                  )}
                </Field>
                <Field label={t('registry.externalRef')}>{data.externalRef ?? '—'}</Field>
                <Field label={t('registry.attempts')}>{data.attempts}</Field>
                <Field label={t('registry.nextAttempt')}>{formatDateTime(data.nextAttemptAt) || '—'}</Field>
                <Field label={t('registry.expectedEffective')}>{formatDateTime(data.expectedEffectiveAt) || '—'}</Field>
              </SimpleGrid>
            </Card>
            <Card withBorder>
              <Title order={5} mb="md">
                {t('registry.lifecycle')}
              </Title>
              <Timeline
                active={lifecycle(data).filter((step) => step.done).length - 1}
                bulletSize={18}
                lineWidth={2}
                data-testid="instruction-lifecycle"
              >
                {lifecycle(data).map((step) => (
                  <Timeline.Item
                    key={step.key}
                    title={t(`registry.steps.${step.key}`)}
                    color={step.key === 'failed' || step.key === 'cancelled' ? 'red' : undefined}
                  >
                    <Text size="xs" c="dimmed">
                      {step.done
                        ? [formatDateTime(step.at), step.by].filter(Boolean).join(' · ')
                        : t('registry.notYet')}
                    </Text>
                  </Timeline.Item>
                ))}
              </Timeline>
            </Card>
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
          </>
        )}
      </QueryState>
    </Stack>
  );
}
