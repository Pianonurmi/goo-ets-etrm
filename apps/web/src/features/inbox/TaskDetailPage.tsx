import {
  Alert,
  Badge,
  Button,
  Code,
  Group,
  List,
  Paper,
  Stack,
  Table,
  Text,
  Textarea,
  TextInput,
  ThemeIcon,
  Title,
} from '@mantine/core';
import { IconBan, IconCheck, IconX } from '@tabler/icons-react';
import { useState, type ReactNode } from 'react';
import { useTranslation } from 'react-i18next';
import { useParams } from 'react-router';
import { $api } from '@/shared/api/client';
import { useSession } from '@/shared/auth/SessionContext';
import { DetailLayout } from '@/shared/components/DetailLayout';
import { LifecycleTimeline } from '@/shared/components/LifecycleTimeline';
import { notifySuccess } from '@/shared/components/notify';
import { PageHeader } from '@/shared/components/PageHeader';
import { QueryState } from '@/shared/components/QueryState';
import { ReasonModal } from '@/shared/components/ReasonModal';
import { CardSkeleton } from '@/shared/components/Skeletons';
import { StatusBadge } from '@/shared/components/StatusBadge';
import { useEntity } from '@/shared/context/EntityContext';
import { formatDateTime } from '@/shared/format/format';
import { eventLabel } from '@/features/home/model/home';
import { useInvalidateWorkflow, useTaskTypes } from './hooks';
import {
  canCancel,
  canWorkOn,
  decisionBlock,
  decisionsOf,
  reasonRequired,
  type Decision,
  type DecisionBlock,
  type Task,
  type TaskType,
} from './model/decisions';
import { decisionChecks, taskSteps } from './model/history';
import { SubjectLink } from './SubjectLink';
import { DueLabel, TaskBadges } from './TaskBadges';

function Card({ title, children, testId }: { title?: string; children: ReactNode; testId?: string }) {
  return (
    <Paper withBorder p="lg" data-testid={testId}>
      {title && (
        <Title order={3} size="h5" mb="md">
          {title}
        </Title>
      )}
      {children}
    </Paper>
  );
}

interface Finding {
  message: string;
  severity?: string;
  code?: string;
}

function isFindings(value: unknown): value is Finding[] {
  return (
    Array.isArray(value) &&
    value.every(
      (item) =>
        typeof item === 'object' && item !== null && typeof (item as { message?: unknown }).message === 'string',
    )
  );
}

function PayloadValue({ value }: { value: unknown }) {
  if (value === null || value === undefined || value === '') {
    return (
      <Text size="sm" c="dimmed">
        —
      </Text>
    );
  }
  if (isFindings(value)) {
    return (
      <Stack gap={6}>
        {value.map((finding, index) => (
          <Group key={`${finding.code ?? ''}-${index}`} gap="xs" wrap="nowrap" align="flex-start">
            {finding.severity && (
              <StatusBadge
                status={finding.severity === 'WARN' ? 'WARNING' : finding.severity}
                style={{ flexShrink: 0 }}
              />
            )}
            <Text size="sm">{finding.message}</Text>
          </Group>
        ))}
      </Stack>
    );
  }
  if (typeof value === 'object') return <Code block>{JSON.stringify(value, null, 2)}</Code>;
  return (
    <Text size="sm" className="etrm-figure" style={{ overflowWrap: 'anywhere' }}>
      {typeof value === 'string' ? value : JSON.stringify(value)}
    </Text>
  );
}

/** What the requester sent with the task: the facts the decision rests on. */
function Payload({ payload }: { payload: Record<string, unknown> }) {
  const { t } = useTranslation();
  const entries = Object.entries(payload);
  if (entries.length === 0) return null;
  return (
    <Card title={t('inbox.details')} testId="task-payload">
      <Table withRowBorders verticalSpacing={8}>
        <Table.Tbody>
          {entries.map(([key, value]) => (
            <Table.Tr key={key}>
              <Table.Td w="32%" valign="top">
                <Text size="sm" c="dimmed">
                  {eventLabel(key)}
                </Text>
              </Table.Td>
              <Table.Td>
                <PayloadValue value={value} />
              </Table.Td>
            </Table.Tr>
          ))}
        </Table.Tbody>
      </Table>
    </Card>
  );
}

const DECISION_PATHS = {
  APPROVE: '/api/v1/workflow/tasks/{id}/approve',
  REJECT: '/api/v1/workflow/tasks/{id}/reject',
  ACKNOWLEDGE: '/api/v1/workflow/tasks/{id}/acknowledge',
} as const;

/** Why the viewer may or may not decide: one line per rule, with its outcome. */
function FourEyes({ task, type, block }: { task: Task; type: TaskType | undefined; block: DecisionBlock | null }) {
  const { t } = useTranslation();
  const { me } = useSession();
  const roles = task.candidateRoles.map((role) => t(`roles.${role}`)).join(', ');
  return (
    <List spacing={6} size="sm" data-testid="decision-checks" listStyleType="none">
      {decisionChecks(task, type, me).map((check) => (
        <List.Item
          key={check.key}
          data-testid={
            check.key === 'role' && block === 'not-candidate'
              ? 'not-candidate'
              : check.key === 'fourEyes' && block === 'own-request'
                ? 'own-request'
                : undefined
          }
          icon={
            <ThemeIcon size={18} radius="xl" variant="light" color={check.ok ? 'green' : 'red'}>
              {check.ok ? <IconCheck size={12} /> : <IconX size={12} />}
            </ThemeIcon>
          }
        >
          {check.key === 'role'
            ? t(check.ok ? 'inbox.check.role' : 'inbox.check.roleMissing', { roles })
            : t(check.ok ? 'inbox.check.fourEyes' : 'inbox.check.fourEyesOwn', { who: task.requestedBy })}
        </List.Item>
      ))}
    </List>
  );
}

function DecisionPanel({ task, type }: { task: Task; type: TaskType | undefined }) {
  const { t } = useTranslation();
  const { me } = useSession();
  const invalidate = useInvalidateWorkflow();
  const [reason, setReason] = useState('');
  const approve = $api.useMutation('post', DECISION_PATHS.APPROVE);
  const reject = $api.useMutation('post', DECISION_PATHS.REJECT);
  const acknowledge = $api.useMutation('post', DECISION_PATHS.ACKNOWLEDGE);
  const mutations = { APPROVE: approve, REJECT: reject, ACKNOWLEDGE: acknowledge };
  const block = decisionBlock(task, type, me);

  if (block === 'closed') return null;
  const decide = (decision: Decision) =>
    mutations[decision].mutate(
      { params: { path: { id: task.id } }, body: { reason: reason.trim() || null } },
      {
        onSuccess: () => {
          notifySuccess(t(`inbox.decided.${decision}`));
          setReason('');
          void invalidate();
        },
      },
    );
  const decisions = decisionsOf(type);
  const required = decisions.filter((decision) => reasonRequired(type, decision));

  return (
    <Paper withBorder p="lg" style={{ borderColor: block === null ? 'var(--etrm-accent)' : undefined }}>
      <Stack gap="md">
        <Group justify="space-between" wrap="nowrap">
          <Title order={3} size="h5">
            {t('inbox.decide')}
          </Title>
          <DueLabel task={task} />
        </Group>
        <FourEyes task={task} type={type} block={block} />
        {block === null && (
          <Textarea
            label={t('inbox.reason')}
            description={
              required.length > 0
                ? t('inbox.reasonRequiredFor', {
                    decisions: required.map((decision) => t(`inbox.decision.${decision}`)).join(', '),
                  })
                : t('inbox.reasonHint')
            }
            value={reason}
            onChange={(event) => setReason(event.currentTarget.value)}
            autosize
            minRows={2}
          />
        )}
        <Group grow>
          {decisions.map((decision) => (
            <Button
              key={decision}
              color={decision === 'REJECT' ? 'red' : 'teal'}
              variant={decision === 'REJECT' ? 'light' : 'filled'}
              disabled={block !== null || (reasonRequired(type, decision) && !reason.trim())}
              loading={mutations[decision].isPending}
              onClick={() => decide(decision)}
              data-testid={`decide-${decision}`}
            >
              {t(`inbox.decision.${decision}`)}
            </Button>
          ))}
        </Group>
      </Stack>
    </Paper>
  );
}

/** The outcome of a closed task: who decided, when and why. */
function Outcome({ task }: { task: Task }) {
  const { t } = useTranslation();
  if (!task.decidedBy) return null;
  return (
    <Alert
      color={task.status === 'REJECTED' ? 'red' : task.status === 'CANCELLED' ? 'gray' : 'teal'}
      title={t(`inbox.status.${task.status}`)}
      data-testid="task-outcome"
    >
      {t('inbox.decidedBy', { user: task.decidedBy, at: formatDateTime(task.decidedAt) })}
      {task.reason ? ` — ${task.reason}` : ''}
    </Alert>
  );
}

/** Who works on the task, and withdrawing it: secondary actions below the decision. */
function WorkPanel({ task, type }: { task: Task; type: TaskType | undefined }) {
  const { t } = useTranslation();
  const { me } = useSession();
  const invalidate = useInvalidateWorkflow();
  const [assignee, setAssignee] = useState('');
  const [cancelling, setCancelling] = useState(false);
  const options = { onSuccess: () => void invalidate() };
  const claim = $api.useMutation('post', '/api/v1/workflow/tasks/{id}/claim', options);
  const release = $api.useMutation('post', '/api/v1/workflow/tasks/{id}/release', options);
  const assign = $api.useMutation('post', '/api/v1/workflow/tasks/{id}/assign', options);
  const cancel = $api.useMutation('post', '/api/v1/workflow/tasks/{id}/cancel', {
    meta: { notify: false },
    onSuccess: () => {
      notifySuccess(t('inbox.cancelled'));
      setCancelling(false);
      void invalidate();
    },
  });
  const work = canWorkOn(task, me);
  const withdraw = canCancel(task, type, me);
  if (!work && !withdraw) return null;
  const path = { params: { path: { id: task.id } } };
  return (
    <Card title={work ? t('inbox.assignment') : t('inbox.cancelTitle')}>
      <Stack gap="sm">
        {work && (
          <>
            <Group gap="xs" justify="space-between" wrap="nowrap">
              <Text size="sm" c={task.assignee ? undefined : 'dimmed'}>
                {task.assignee ? t('inbox.assignedTo', { user: task.assignee }) : t('inbox.unassigned')}
              </Text>
              <Group gap="xs" wrap="nowrap">
                {task.assignee !== me.userId && (
                  <Button
                    variant="light"
                    size="xs"
                    loading={claim.isPending}
                    onClick={() => claim.mutate(path)}
                    data-testid="claim"
                  >
                    {t('inbox.claim')}
                  </Button>
                )}
                {task.assignee && (
                  <Button variant="default" size="xs" loading={release.isPending} onClick={() => release.mutate(path)}>
                    {t('inbox.release')}
                  </Button>
                )}
              </Group>
            </Group>
            <Group align="flex-end" gap="xs" wrap="nowrap">
              <TextInput
                label={t('inbox.assignTo')}
                size="xs"
                style={{ flex: 1 }}
                value={assignee}
                onChange={(event) => setAssignee(event.currentTarget.value)}
              />
              <Button
                size="xs"
                variant="default"
                disabled={!assignee.trim()}
                loading={assign.isPending}
                onClick={() =>
                  assign.mutate({ ...path, body: { assignee: assignee.trim() } }, { onSuccess: () => setAssignee('') })
                }
              >
                {t('inbox.assign')}
              </Button>
            </Group>
          </>
        )}
        {withdraw && (
          <Stack gap={6} pt={work ? 'xs' : 0} style={work ? { borderTop: '1px solid var(--etrm-line)' } : undefined}>
            {work && (
              <Text size="sm" fw={500}>
                {t('inbox.cancelTitle')}
              </Text>
            )}
            <Text size="xs" c="dimmed">
              {task.requestedBy === me.userId ? t('inbox.cancelOwnHint') : t('inbox.cancelOtherHint')}
            </Text>
            <Button
              variant="subtle"
              color="red"
              size="xs"
              w="fit-content"
              px={0}
              leftSection={<IconBan size={14} />}
              onClick={() => setCancelling(true)}
              data-testid="cancel-task"
            >
              {t('inbox.cancelTask')}
            </Button>
          </Stack>
        )}
      </Stack>
      {cancelling && (
        <ReasonModal
          title={t('inbox.cancelTitle')}
          hint={t('inbox.cancelReasonHint')}
          action={t('inbox.cancelTask')}
          pending={cancel.isPending}
          error={cancel.error}
          onSubmit={(reason) => cancel.mutate({ params: { path: { id: task.id } }, body: { reason } })}
          onClose={() => {
            cancel.reset();
            setCancelling(false);
          }}
        />
      )}
    </Card>
  );
}

function Comments({
  taskId,
  comments,
}: {
  taskId: string;
  comments: { id: string; author: string; body: string; createdAt: string }[];
}) {
  const { t } = useTranslation();
  const invalidate = useInvalidateWorkflow();
  const [body, setBody] = useState('');
  const comment = $api.useMutation('post', '/api/v1/workflow/tasks/{id}/comments');
  return (
    <Card title={t('inbox.comments')}>
      <Stack gap="md">
        {comments.length === 0 ? (
          <Text size="sm" c="dimmed">
            {t('inbox.noComments')}
          </Text>
        ) : (
          comments.map((entry) => (
            <Stack key={entry.id} gap={2} pl="sm" style={{ borderLeft: '2px solid var(--etrm-line)' }}>
              <Text size="xs" c="dimmed">
                <Text span fw={600} c="var(--mantine-color-text)">
                  {entry.author}
                </Text>{' '}
                · {formatDateTime(entry.createdAt)}
              </Text>
              <Text size="sm" style={{ whiteSpace: 'pre-wrap' }}>
                {entry.body}
              </Text>
            </Stack>
          ))
        )}
        <Stack gap="xs">
          <Textarea
            aria-label={t('inbox.addComment')}
            placeholder={t('inbox.commentPlaceholder')}
            value={body}
            onChange={(event) => setBody(event.currentTarget.value)}
            autosize
            minRows={2}
          />
          <Button
            size="xs"
            variant="light"
            w="fit-content"
            disabled={!body.trim()}
            loading={comment.isPending}
            onClick={() =>
              comment.mutate(
                { params: { path: { id: taskId } }, body: { body: body.trim() } },
                {
                  onSuccess: () => {
                    setBody('');
                    void invalidate();
                  },
                },
              )
            }
          >
            {t('inbox.addComment')}
          </Button>
        </Stack>
      </Stack>
    </Card>
  );
}

export function TaskDetailPage() {
  const { t } = useTranslation();
  const { taskId = '' } = useParams();
  const types = useTaskTypes();
  const { entities } = useEntity();
  const details = $api.useQuery('get', '/api/v1/workflow/tasks/{id}', { params: { path: { id: taskId } } });

  if (details.isPending) {
    return (
      <Stack>
        <PageHeader title={t('inbox.loadingTask')} breadcrumbs={[{ label: t('inbox.title'), to: '/inbox' }]} />
        <CardSkeleton height={140} />
        <CardSkeleton height={220} />
      </Stack>
    );
  }
  return (
    <QueryState isPending={false} error={details.error}>
      {details.data &&
        (() => {
          const { task, comments, history } = details.data;
          const type = types.get(task.type);
          const typeName = type?.displayName ?? task.type;
          const entity = entities.find((candidate) => candidate.id === task.legalEntityId);
          return (
            <Stack>
              <PageHeader
                breadcrumbs={[{ label: t('inbox.title'), to: '/inbox' }, { label: typeName }]}
                title={<span data-testid="task-summary">{task.summary}</span>}
                description={<TaskBadges task={task} />}
              />
              <DetailLayout
                asideFirst
                summary={[
                  {
                    label: t('inbox.columns.subject'),
                    value: <SubjectLink subjectType={task.subjectType} subjectRef={task.subjectRef} />,
                  },
                  { label: t('inbox.columns.requestedBy'), value: task.requestedBy },
                  { label: t('inbox.columns.requestedAt'), value: formatDateTime(task.requestedAt) },
                  { label: t('inbox.columns.dueAt'), value: formatDateTime(task.dueAt) },
                  {
                    label: t('inbox.candidates'),
                    value: (
                      <Group gap={4}>
                        {task.candidateRoles.map((role) => (
                          <Badge key={role} size="sm" variant="light" tt="none">
                            {t(`roles.${role}`)}
                          </Badge>
                        ))}
                      </Group>
                    ),
                  },
                  { label: t('inbox.columns.assignee'), value: task.assignee ?? '—' },
                  { label: t('header.entity'), value: entity?.code ?? task.legalEntityId ?? '—' },
                  {
                    label: t('inbox.fourEyes'),
                    value: type?.fourEyes === false ? t('inbox.fourEyesNo') : t('inbox.fourEyesYes'),
                  },
                ]}
                aside={
                  <>
                    <Outcome task={task} />
                    <DecisionPanel task={task} type={type} />
                    <WorkPanel task={task} type={type} />
                    <LifecycleTimeline
                      title={t('inbox.history')}
                      steps={taskSteps(task, history, t)}
                      testId="task-history"
                    />
                  </>
                }
              >
                <Payload payload={task.payload} />
                <Comments taskId={task.id} comments={comments} />
              </DetailLayout>
            </Stack>
          );
        })()}
    </QueryState>
  );
}
