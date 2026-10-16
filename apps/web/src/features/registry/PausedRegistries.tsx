import { Alert, Button, Group, Stack, Text } from '@mantine/core';
import { IconPlayerPause } from '@tabler/icons-react';
import { useQueryClient } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { $api } from '@/shared/api/client';
import { Can } from '@/shared/auth/Can';
import { notifySuccess } from '@/shared/components/notify';
import { ProblemAlert } from '@/shared/components/ProblemAlert';
import { formatDateTime } from '@/shared/format/format';

/**
 * Registries whose dispatch paused after transport failures in a row (increment 16 M3). Their instructions wait; one
 * goes as a probe after the cool-down, or an operator resumes the registry here.
 */
export function PausedRegistries() {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const circuits = $api.useQuery('get', '/api/v1/registry/circuits', {}, { refetchInterval: 60_000 });
  const resume = $api.useMutation('post', '/api/v1/registry/circuits/{registryCode}/resume', {
    meta: { notify: false },
    onSuccess: (circuit) => {
      notifySuccess(t('registry.circuit.resumed', { registry: circuit.registryCode }));
      void queryClient.invalidateQueries({
        predicate: (q) => typeof q.queryKey[1] === 'string' && q.queryKey[1].startsWith('/api/v1/registry'),
      });
    },
  });
  const paused = (circuits.data ?? []).filter((circuit) => circuit.paused);
  if (paused.length === 0) return null;
  return (
    <Stack gap="xs">
      {paused.map((circuit) => (
        <Alert
          key={circuit.registryCode}
          color="orange"
          icon={<IconPlayerPause size={18} />}
          title={t('registry.circuit.title', { registry: circuit.registryCode })}
          data-testid={`paused-registry-${circuit.registryCode}`}
        >
          <Group justify="space-between" align="flex-start" wrap="nowrap">
            <Text size="sm">
              {t('registry.circuit.detail', {
                count: circuit.consecutiveFailures,
                reason: circuit.lastFailure ?? '',
                probe: circuit.probeAfter ? formatDateTime(circuit.probeAfter) : '',
              })}
            </Text>
            <Can op="registryCircuit_resume">
              <Button
                size="xs"
                variant="light"
                color="orange"
                loading={resume.isPending && resume.variables?.params.path.registryCode === circuit.registryCode}
                onClick={() => resume.mutate({ params: { path: { registryCode: circuit.registryCode } } })}
                data-testid={`resume-registry-${circuit.registryCode}`}
              >
                {t('registry.circuit.resume')}
              </Button>
            </Can>
          </Group>
        </Alert>
      ))}
      <ProblemAlert error={resume.error} />
    </Stack>
  );
}
