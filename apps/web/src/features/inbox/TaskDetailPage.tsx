import {
  Alert,
  Anchor,
  Badge,
  Button,
  Card,
  Code,
  Grid,
  Group,
  SimpleGrid,
  Stack,
  Table,
  Text,
  Textarea,
  TextInput,
  Timeline,
  Title,
} from '@mantine/core';
import { IconArrowLeft, IconInfoCircle } from '@tabler/icons-react';
import { useState, type ReactNode } from 'react';
import { useTranslation } from 'react-i18next';
import { Link, useParams } from 'react-router';
import { $api } from '@/shared/api/client';
import { useSession } from '@/shared/auth/SessionContext';
import { notifySuccess } from '@/shared/components/notify';
import { QueryState } from '@/shared/components/QueryState';
import { useEntity } from '@/shared/context/EntityContext';
import { formatDateTime } from '@/shared/format/format';
import { useInvalidateWorkflow, useTaskTypes } from './hooks';
import {
  canWorkOn,
  decisionBlock,
  decisionsOf,
  reasonRequired,
  type Decision,
  type Task,
  type TaskType,
} from './model/decisions';
import { SubjectLink } from './SubjectLink';
import { TaskBadges } from './TaskBadges';

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

function formatValue(value: unknown): string {
  if (value === null || value === undefined) return '';
  if (typeof value === 'string') return value;
  if (typeof value === 'number' || typeof value === 'boolean') return value.toString();
  return JSON.stringify(value);
}

const DECISION_PATHS = {
  APPROVE: '/api/v1/workflow/tasks/{id}/approve',
  REJECT: '/api/v1/workflow/tasks/{id}/reject',
  ACKNOWLEDGE: '/api/v1/workflow/tasks/{id}/acknowledge',
} as const;

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

  return (
    <Card withBorder>
      <Stack>
        <Title order={4}>{t('inbox.decide')}</Title>
        {block === 'own-request' && (
          <Alert color="yellow" icon={<IconInfoCircle size={18} />} data-testid="own-request">
            {t('inbox.ownRequest')}
          </Alert>
        )}
        {block === 'not-candidate' && (
          <Alert color="gray" icon={<IconInfoCircle size={18} />} data-testid="not-candidate">
            {t('inbox.notCandidate', { roles: task.candidateRoles.join(', ') })}
          </Alert>
        )}
        <Textarea
          label={t('inbox.reason')}
          description={t('inbox.reasonHint')}
          value={reason}
          onChange={(event) => setReason(event.currentTarget.value)}
          disabled={block !== null}
          autosize
          minRows={2}
        />
        <Group>
          {decisionsOf(type).map((decision) => (
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
    </Card>
  );
}

function AssignmentPanel({ task }: { task: Task }) {
  const { t } = useTranslation();
  const { me } = useSession();
  const invalidate = useInvalidateWorkflow();
  const [assignee, setAssignee] = useState('');
  const options = { onSuccess: () => void invalidate() };
  const claim = $api.useMutation('post', '/api/v1/workflow/tasks/{id}/claim', options);
  const release = $api.useMutation('post', '/api/v1/workflow/tasks/{id}/release', options);
  const assign = $api.useMutation('post', '/api/v1/workflow/tasks/{id}/assign', options);
  if (!canWorkOn(task, me)) return null;
  const path = { params: { path: { id: task.id } } };
  return (
    <Card withBorder>
      <Stack gap="sm">
        <Title order={5}>{t('inbox.assignment')}</Title>
        <Group>
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
        <Group align="flex-end" gap="xs">
          <TextInput
            label={t('inbox.assignTo')}
            size="xs"
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
      </Stack>
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
    <Card withBorder>
      <Stack gap="sm">
        <Title order={5}>{t('inbox.comments')}</Title>
        {comments.length === 0 && (
          <Text size="sm" c="dimmed">
            {t('inbox.noComments')}
          </Text>
        )}
        {comments.map((entry) => (
          <Stack key={entry.id} gap={0}>
            <Text size="xs" c="dimmed">
              {entry.author} · {formatDateTime(entry.createdAt)}
            </Text>
            <Text size="sm" style={{ whiteSpace: 'pre-wrap' }}>
              {entry.body}
            </Text>
          </Stack>
        ))}
        <Textarea
          aria-label={t('inbox.addComment')}
          placeholder={t('inbox.addComment')}
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
    </Card>
  );
}

export function TaskDetailPage() {
  const { t } = useTranslation();
  const { taskId = '' } = useParams();
  const types = useTaskTypes();
  const { entities } = useEntity();
  const details = $api.useQuery('get', '/api/v1/workflow/tasks/{id}', { params: { path: { id: taskId } } });

  return (
    <Stack>
      <Anchor component={Link} to="/inbox" size="sm">
        <Group gap={4}>
          <IconArrowLeft size={14} />
          {t('inbox.back')}
        </Group>
      </Anchor>
      <QueryState isPending={details.isPending} error={details.error}>
        {details.data &&
          (() => {
            const { task, comments, history } = details.data;
            const type = types.get(task.type);
            const entity = entities.find((candidate) => candidate.id === task.legalEntityId);
            const payload = Object.entries(task.payload);
            return (
              <Grid>
                <Grid.Col span={{ base: 12, lg: 8 }}>
                  <Stack>
                    <Card withBorder>
                      <Stack gap="sm">
                        <Group justify="space-between" align="flex-start">
                          <Stack gap={2}>
                            <Text size="sm" c="dimmed">
                              {type?.displayName ?? task.type}
                            </Text>
                            <Title order={3} data-testid="task-summary">
                              {task.summary}
                            </Title>
                          </Stack>
                          <TaskBadges task={task} />
                        </Group>
                        <SimpleGrid cols={{ base: 2, md: 4 }}>
                          <Field label={t('inbox.columns.subject')}>
                            <SubjectLink subjectType={task.subjectType} subjectRef={task.subjectRef} />
                          </Field>
                          <Field label={t('inbox.columns.requestedBy')}>{task.requestedBy}</Field>
                          <Field label={t('inbox.columns.requestedAt')}>{formatDateTime(task.requestedAt)}</Field>
                          <Field label={t('inbox.columns.dueAt')}>{formatDateTime(task.dueAt)}</Field>
                          <Field label={t('inbox.candidates')}>
                            <Group gap={4}>
                              {task.candidateRoles.map((role) => (
                                <Badge key={role} size="sm" variant="light">
                                  {role}
                                </Badge>
                              ))}
                            </Group>
                          </Field>
                          <Field label={t('inbox.columns.assignee')}>{task.assignee ?? '—'}</Field>
                          <Field label={t('header.entity')}>{entity?.code ?? task.legalEntityId ?? '—'}</Field>
                          <Field label={t('inbox.fourEyes')}>
                            {type?.fourEyes === false ? t('common.no') : t('common.yes')}
                          </Field>
                        </SimpleGrid>
                        {task.decidedBy && (
                          <Alert
                            color={task.status === 'REJECTED' ? 'red' : 'teal'}
                            title={t(`inbox.status.${task.status}`)}
                          >
                            {t('inbox.decidedBy', { user: task.decidedBy, at: formatDateTime(task.decidedAt) })}
                            {task.reason ? ` — ${task.reason}` : ''}
                          </Alert>
                        )}
                      </Stack>
                    </Card>
                    {payload.length > 0 && (
                      <Card withBorder>
                        <Title order={5} mb="xs">
                          {t('inbox.details')}
                        </Title>
                        <Table striped withRowBorders={false}>
                          <Table.Tbody>
                            {payload.map(([key, value]) => (
                              <Table.Tr key={key}>
                                <Table.Td w="35%">
                                  <Text size="sm" c="dimmed">
                                    {key}
                                  </Text>
                                </Table.Td>
                                <Table.Td>
                                  {typeof value === 'object' && value !== null ? (
                                    <Code block>{JSON.stringify(value, null, 2)}</Code>
                                  ) : (
                                    <Text size="sm">{formatValue(value)}</Text>
                                  )}
                                </Table.Td>
                              </Table.Tr>
                            ))}
                          </Table.Tbody>
                        </Table>
                      </Card>
                    )}
                    <Comments taskId={task.id} comments={comments} />
                  </Stack>
                </Grid.Col>
                <Grid.Col span={{ base: 12, lg: 4 }}>
                  <Stack>
                    <DecisionPanel task={task} type={type} />
                    <AssignmentPanel task={task} />
                    <Card withBorder>
                      <Title order={5} mb="sm">
                        {t('inbox.history')}
                      </Title>
                      <Timeline bulletSize={14} lineWidth={2}>
                        {history.map((event) => (
                          <Timeline.Item key={event.seq} title={event.eventType}>
                            <Text size="xs" c="dimmed">
                              {event.actor} · {formatDateTime(event.at)}
                            </Text>
                          </Timeline.Item>
                        ))}
                      </Timeline>
                    </Card>
                  </Stack>
                </Grid.Col>
              </Grid>
            );
          })()}
      </QueryState>
    </Stack>
  );
}
