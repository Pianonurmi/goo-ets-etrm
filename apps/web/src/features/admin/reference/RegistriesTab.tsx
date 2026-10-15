import { Badge, Button, Modal, Paper, Select, Stack, Switch, Table, Text, Textarea } from '@mantine/core';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { $api } from '@/shared/api/client';
import { Can } from '@/shared/auth/Can';
import { notifySuccess } from '@/shared/components/notify';
import { ProblemAlert } from '@/shared/components/ProblemAlert';
import { QueryState } from '@/shared/components/QueryState';
import { statusLabel } from '@/shared/components/StatusBadge';
import { useInvalidateReference } from '../PartyDetails';
import { useRegistries } from './hooks';
import { type Registry, ActiveBadge } from './shared';

export function RegistryChangeModal({ registry, onClose }: { registry: Registry; onClose: () => void }) {
  const { t } = useTranslation();
  const invalidate = useInvalidateReference();
  const [integration, setIntegration] = useState<Registry['integration']>(registry.integration);
  const [active, setActive] = useState(registry.active);
  const [reason, setReason] = useState('');
  const request = $api.useMutation('post', '/api/v1/reference/registries/{code}/change-requests', {
    meta: { notify: false },
    onSuccess: () => {
      notifySuccess(t('admin.changeRequested'));
      void invalidate();
      onClose();
    },
  });
  return (
    <Modal opened onClose={onClose} title={t('admin.changeRegistry', { code: registry.code })}>
      <Stack>
        <ProblemAlert error={request.error} />
        <Text size="sm" c="dimmed">
          {t('risk.fourEyesHint')}
        </Text>
        <Select
          label={t('admin.integration')}
          data={['MANUAL', 'FILE', 'API', 'SIMULATOR']}
          value={integration}
          allowDeselect={false}
          onChange={(value) => value && setIntegration(value)}
        />
        <Switch label={t('marketData.active')} checked={active} onChange={(e) => setActive(e.currentTarget.checked)} />
        <Textarea label={t('risk.reason')} value={reason} onChange={(e) => setReason(e.currentTarget.value)} autosize />
        <Button
          disabled={!reason.trim()}
          loading={request.isPending}
          onClick={() =>
            request.mutate({
              params: { path: { code: registry.code } },
              body: { integration, active, reason, expectedVersion: registry.version },
            })
          }
        >
          {t('risk.sendForApproval')}
        </Button>
      </Stack>
    </Modal>
  );
}

export function RegistriesTab() {
  const { t } = useTranslation();
  const registries = useRegistries();
  const [changing, setChanging] = useState<Registry | null>(null);
  return (
    <QueryState isPending={registries.isPending} error={registries.error}>
      <Paper withBorder>
        <Table highlightOnHover verticalSpacing="sm">
          <Table.Thead>
            <Table.Tr>
              <Table.Th>{t('marketData.code')}</Table.Th>
              <Table.Th>{t('ets.name')}</Table.Th>
              <Table.Th>{t('marketData.kind')}</Table.Th>
              <Table.Th>{t('admin.integration')}</Table.Th>
              <Table.Th>{t('admin.hub')}</Table.Th>
              <Table.Th>{t('marketData.active')}</Table.Th>
              <Table.Th />
            </Table.Tr>
          </Table.Thead>
          <Table.Tbody>
            {(registries.data ?? []).map((registry) => (
              <Table.Tr key={registry.id}>
                <Table.Td ff="monospace">{registry.code}</Table.Td>
                <Table.Td>{registry.name}</Table.Td>
                <Table.Td>{statusLabel(registry.registryType)}</Table.Td>
                <Table.Td>
                  <Badge variant="light" color="gray" tt="none">
                    {t(`admin.integrationModes.${registry.integration}`, { defaultValue: registry.integration })}
                  </Badge>
                </Table.Td>
                <Table.Td>{registry.hubMember ? t('common.yes') : t('common.no')}</Table.Td>
                <Table.Td>
                  <ActiveBadge active={registry.active} />
                </Table.Td>
                <Table.Td>
                  <Can op="changeRequest_requestRegistryChange">
                    <Button size="compact-xs" variant="subtle" onClick={() => setChanging(registry)}>
                      {t('admin.requestChange')}
                    </Button>
                  </Can>
                </Table.Td>
              </Table.Tr>
            ))}
          </Table.Tbody>
        </Table>
      </Paper>
      {changing && <RegistryChangeModal registry={changing} onClose={() => setChanging(null)} />}
    </QueryState>
  );
}
