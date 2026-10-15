import { Button, Group, Modal, NumberInput, Paper, Select, Stack, Switch, Table, Text } from '@mantine/core';
import { IconPencil } from '@tabler/icons-react';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { $api } from '@/shared/api/client';
import { decimal } from '@/shared/api/types';
import { Can } from '@/shared/auth/Can';
import { notifySuccess } from '@/shared/components/notify';
import { ProblemAlert } from '@/shared/components/ProblemAlert';
import { QueryState } from '@/shared/components/QueryState';
import { StatusBadge } from '@/shared/components/StatusBadge';
import { formatDecimal } from '@/shared/format/format';
import { useUrlState } from '@/shared/useUrlState';
import { type Route } from './model/instructions';
import { useInvalidateRegistry, useRegistries } from './TransferModal';

/** Feasibility, fee and expected days of a route; changed by middle or back office. */
export function RouteModal({ route, onClose }: { route: Route; onClose: () => void }) {
  const { t } = useTranslation();
  const invalidate = useInvalidateRegistry();
  const [feasible, setFeasible] = useState(route.feasible);
  const [fee, setFee] = useState(String(route.feePerMwh));
  const [days, setDays] = useState<number>(route.expectedDays);
  const save = $api.useMutation('put', '/api/v1/registry/routes', {
    meta: { notify: false },
    onSuccess: () => {
      notifySuccess(t('registry.routeSaved'));
      void invalidate();
      onClose();
    },
  });
  return (
    <Modal opened onClose={onClose} title={`${route.fromRegistryCode} → ${route.toRegistryCode}`}>
      <Stack>
        <ProblemAlert error={save.error} />
        <Switch
          label={t('registry.feasible')}
          checked={feasible}
          onChange={(e) => setFeasible(e.currentTarget.checked)}
        />
        <Group grow>
          <NumberInput
            label={t('registry.feePerMwh')}
            value={fee}
            onChange={(value) => setFee(String(value))}
            decimalScale={4}
            min={0}
          />
          <NumberInput
            label={t('registry.expectedDays')}
            value={days}
            onChange={(value) => setDays(Number(value))}
            min={0}
            allowDecimal={false}
          />
        </Group>
        <Button
          loading={save.isPending}
          onClick={() =>
            save.mutate({
              body: {
                fromRegistryCode: route.fromRegistryCode,
                toRegistryCode: route.toRegistryCode,
                feasible,
                feePerMwh: decimal(fee),
                expectedDays: days,
              },
            })
          }
        >
          {t('registry.saveRoute')}
        </Button>
      </Stack>
    </Modal>
  );
}

export function RoutesTab() {
  const { t } = useTranslation();
  const registries = useRegistries();
  const { params, set: patchUrl } = useUrlState();
  const from = params.get('from') ?? registries.data?.[0]?.code ?? null;
  const [editing, setEditing] = useState<Route | null>(null);
  const routes = $api.useQuery(
    'get',
    '/api/v1/registry/routes',
    { params: { query: { from: from ?? '' } } },
    { enabled: Boolean(from) },
  );
  const rows = Object.values(routes.data ?? {}).sort((a, b) => a.toRegistryCode.localeCompare(b.toRegistryCode));
  return (
    <Stack>
      <Select
        label={t('registry.fromRegistry')}
        data={(registries.data ?? []).map((registry) => ({
          value: registry.code,
          label: `${registry.code} · ${registry.name}`,
        }))}
        value={from}
        onChange={(value) => {
          if (value) patchUrl({ from: value });
        }}
        allowDeselect={false}
        w={320}
      />
      <Text size="sm" c="dimmed">
        {t('registry.routesHint')}
      </Text>
      <QueryState isPending={routes.isPending && Boolean(from)} error={routes.error}>
        <Paper withBorder radius="lg">
          <Table verticalSpacing="sm" highlightOnHover data-testid="routes-table">
            <Table.Thead>
              <Table.Tr>
                <Table.Th>{t('registry.toRegistry')}</Table.Th>
                <Table.Th>{t('registry.feasible')}</Table.Th>
                <Table.Th ta="right">{t('registry.feePerMwh')}</Table.Th>
                <Table.Th ta="right">{t('registry.expectedDays')}</Table.Th>
                <Table.Th />
              </Table.Tr>
            </Table.Thead>
            <Table.Tbody>
              {rows.map((route) => (
                <Table.Tr key={route.toRegistryCode}>
                  <Table.Td>{route.toRegistryCode}</Table.Td>
                  <Table.Td>
                    <StatusBadge
                      status={route.feasible ? 'OK' : 'REJECTED'}
                      label={route.feasible ? t('registry.feasibleYes') : t('registry.feasibleNo')}
                    />
                  </Table.Td>
                  <Table.Td ta="right">{formatDecimal(route.feePerMwh, 2, 4)}</Table.Td>
                  <Table.Td ta="right">{route.expectedDays}</Table.Td>
                  <Table.Td ta="right">
                    <Can op="registry_upsertRoute">
                      <Button
                        size="compact-xs"
                        variant="subtle"
                        leftSection={<IconPencil size={12} />}
                        onClick={() => setEditing(route)}
                      >
                        {t('registry.edit')}
                      </Button>
                    </Can>
                  </Table.Td>
                </Table.Tr>
              ))}
            </Table.Tbody>
          </Table>
        </Paper>
      </QueryState>
      {editing && <RouteModal route={editing} onClose={() => setEditing(null)} />}
    </Stack>
  );
}
