import { Alert, Button, Group, Modal, Paper, Progress, Stack, Text, Textarea, TextInput, Title } from '@mantine/core';
import { IconAlertTriangle, IconInfoCircle, IconRefresh } from '@tabler/icons-react';
import { useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { $api } from '@/shared/api/client';
import { Can } from '@/shared/auth/Can';
import { useSession } from '@/shared/auth/SessionContext';
import type { UiConfig } from '@/shared/auth/session';
import { FactGrid } from '@/shared/components/DetailLayout';
import { PageHeader } from '@/shared/components/PageHeader';
import { ProblemAlert } from '@/shared/components/ProblemAlert';
import { StatusBadge } from '@/shared/components/StatusBadge';
import { formatDateTime } from '@/shared/format/format';
import { resetPhase, type ResetPhase } from './model/demoReset';

const POLL_MS = 3_000;

/** Waits for the restart after a reset: the server goes away, comes back seeding, then the sign-in page. */
function ResetProgress({ requestedAt }: { requestedAt: string }) {
  const { t } = useTranslation();
  const [phase, setPhase] = useState<ResetPhase>({ kind: 'stopping' });
  useEffect(() => {
    const timer = window.setInterval(() => {
      fetch('/api/v1/ui-config', { headers: { Accept: 'application/json' }, cache: 'no-store' })
        .then((response) => (response.ok ? (response.json() as Promise<UiConfig>) : null))
        .catch(() => null)
        .then((config) => {
          const next = resetPhase(config, requestedAt);
          setPhase(next);
          if (next.kind === 'done') window.location.assign('/');
        })
        .catch(() => undefined);
    }, POLL_MS);
    return () => window.clearInterval(timer);
  }, [requestedAt]);
  return (
    <Modal
      opened
      onClose={() => undefined}
      withCloseButton={false}
      closeOnEscape={false}
      closeOnClickOutside={false}
      title={t('demo.resetting')}
    >
      <Stack data-testid="reset-progress">
        <Text size="sm">
          {t(`demo.phase.${phase.kind}`, {
            done: phase.kind === 'seeding' ? phase.done : 0,
            total: phase.kind === 'seeding' ? phase.total : 0,
          })}
        </Text>
        <Progress
          value={
            phase.kind === 'seeding' ? (phase.done / Math.max(phase.total, 1)) * 100 : phase.kind === 'done' ? 100 : 5
          }
          animated
        />
        <Text size="xs" c="dimmed">
          {t('demo.resetHint')}
        </Text>
      </Stack>
    </Modal>
  );
}

function ResetModal({ onClose, onDone }: { onClose: () => void; onDone: (requestedAt: string) => void }) {
  const { t } = useTranslation();
  const [reason, setReason] = useState('');
  const [confirm, setConfirm] = useState('');
  const reset = $api.useMutation('post', '/api/v1/showcase/reset', { meta: { notify: false } });
  return (
    <Modal opened onClose={onClose} title={t('demo.resetTitle')}>
      <Stack>
        <ProblemAlert error={reset.error} />
        <Text size="sm">{t('demo.resetExplain')}</Text>
        <Textarea
          label={t('common.reason')}
          value={reason}
          onChange={(event) => setReason(event.currentTarget.value)}
          autosize
          minRows={2}
          data-testid="reset-reason"
        />
        <TextInput
          label={t('demo.typeReset')}
          value={confirm}
          onChange={(event) => setConfirm(event.currentTarget.value)}
          data-testid="reset-confirm"
        />
        <Group justify="flex-end">
          <Button variant="default" onClick={onClose}>
            {t('common.cancel')}
          </Button>
          <Button
            color="red"
            leftSection={<IconRefresh size={16} />}
            disabled={!reason.trim() || confirm !== 'RESET'}
            loading={reset.isPending}
            data-testid="reset-submit"
            onClick={() =>
              reset.mutate(
                { body: { reason: reason.trim() } },
                { onSuccess: (accepted) => onDone(accepted.requestedAt) },
              )
            }
          >
            {t('demo.reset')}
          </Button>
        </Group>
      </Stack>
    </Modal>
  );
}

/** Administration → Demo: what the public demo holds, when it resets, and a reset for the administrator (ADR 0033). */
export function DemoPage() {
  const { t } = useTranslation();
  const { config } = useSession();
  const [asking, setAsking] = useState(false);
  const [requestedAt, setRequestedAt] = useState<string | null>(null);
  // Read the clock once per visit; the cool-down notice need not tick.
  const [openedAt] = useState(() => Date.now());
  const showcase = config.showcase;
  const seed = config.demoSeed;
  const cooling = showcase?.resetAllowedAt ? new Date(showcase.resetAllowedAt).getTime() > openedAt : false;
  return (
    <Stack>
      <PageHeader title={t('demo.title')} description={t('demo.description')} />
      <Paper withBorder p="lg">
        <Stack>
          <Group justify="space-between">
            <Title order={3} size="h4">
              {t('demo.data')}
            </Title>
            {seed && <StatusBadge status={seed.state} data-testid="seed-state" />}
          </Group>
          <FactGrid
            facts={[
              { label: t('demo.seededAt'), value: formatDateTime(seed?.finishedAt) || '—' },
              { label: t('demo.steps'), value: seed ? `${seed.completedSteps} / ${seed.stepCount}` : '—' },
              { label: t('demo.nextReset'), value: formatDateTime(showcase?.nextResetAt) || '—', testId: 'next-reset' },
              { label: t('demo.resetAllowed'), value: formatDateTime(showcase?.resetAllowedAt) || '—' },
            ]}
          />
        </Stack>
      </Paper>
      {showcase ? (
        <Paper withBorder p="lg">
          <Stack align="flex-start">
            <Title order={3} size="h4">
              {t('demo.resetTitle')}
            </Title>
            <Text size="sm" c="dimmed" maw={640}>
              {t('demo.resetExplain')}
            </Text>
            {cooling && (
              <Alert color="yellow" icon={<IconAlertTriangle size={18} />}>
                {t('demo.cooling', { at: formatDateTime(showcase.resetAllowedAt) })}
              </Alert>
            )}
            <Can op="showcase_reset">
              <Button
                color="red"
                variant="light"
                leftSection={<IconRefresh size={16} />}
                onClick={() => setAsking(true)}
                data-testid="reset-demo"
              >
                {t('demo.reset')}
              </Button>
            </Can>
          </Stack>
        </Paper>
      ) : (
        <Alert color="blue" variant="light" icon={<IconInfoCircle size={18} />}>
          {t('demo.notShowcase')}
        </Alert>
      )}
      {asking && (
        <ResetModal
          onClose={() => setAsking(false)}
          onDone={(at) => {
            setAsking(false);
            setRequestedAt(at);
          }}
        />
      )}
      {requestedAt && <ResetProgress requestedAt={requestedAt} />}
    </Stack>
  );
}

export default DemoPage;
