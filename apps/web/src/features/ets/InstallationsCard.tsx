import { Anchor, Card, Group, Switch, Table, Text, Title } from '@mantine/core';
import { IconBuildingFactory2 } from '@tabler/icons-react';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link } from 'react-router';
import { $api } from '@/shared/api/client';
import { ScrollTable } from '@/shared/components/ScrollTable';
import { EmptyState } from '@/shared/components/EmptyState';
import { QueryState } from '@/shared/components/QueryState';
import { StatusBadge } from '@/shared/components/StatusBadge';
import { formatDate } from '@/shared/format/format';

/** Every installation and aircraft operator of the entity, including those no longer active when asked. */
export function InstallationsCard({ entityId, year }: { entityId: string; year: number }) {
  const { t } = useTranslation();
  const [showInactive, setShowInactive] = useState(false);
  const installations = $api.useQuery('get', '/api/v1/ets/installations', {
    params: { query: { legalEntityId: entityId, activeOnly: !showInactive } },
  });
  const rows = installations.data ?? [];
  return (
    <Card withBorder data-testid="installation-register">
      <Group justify="space-between" mb="sm">
        <Title order={5}>{t('ets.installationRegister')}</Title>
        <Switch
          label={t('admin.showInactive')}
          checked={showInactive}
          onChange={(e) => setShowInactive(e.currentTarget.checked)}
          data-testid="installations-show-inactive"
        />
      </Group>
      <QueryState isPending={installations.isPending} error={installations.error}>
        {rows.length === 0 ? (
          <EmptyState
            title={t('ets.noInstallations')}
            description={t('ets.noInstallationsHint')}
            icon={<IconBuildingFactory2 size={22} />}
            testId="installations-empty"
          />
        ) : (
          <ScrollTable label={t('ets.installationRegister')} minWidth={900}>
            <Table striped highlightOnHover data-testid="installation-list">
              <Table.Thead>
                <Table.Tr>
                  <Table.Th>{t('ets.name')}</Table.Th>
                  <Table.Th>{t('ets.kind')}</Table.Th>
                  <Table.Th>{t('ets.eutlId')}</Table.Th>
                  <Table.Th>{t('ets.permitId')}</Table.Th>
                  <Table.Th>{t('ets.activityCode')}</Table.Th>
                  <Table.Th>{t('ets.eligibleProducts')}</Table.Th>
                  <Table.Th>{t('ets.validPeriod')}</Table.Th>
                  <Table.Th>{t('ets.state')}</Table.Th>
                </Table.Tr>
              </Table.Thead>
              <Table.Tbody>
                {rows.map((installation) => (
                  <Table.Tr key={installation.id} data-testid={`installation-${installation.eutlId}`}>
                    <Table.Td>
                      <Anchor component={Link} to={`/ets/installations/${installation.id}?year=${year}`} size="sm">
                        {installation.name}
                      </Anchor>
                    </Table.Td>
                    <Table.Td>{t(`ets.kinds.${installation.kind}`)}</Table.Td>
                    <Table.Td style={{ whiteSpace: 'nowrap' }}>{installation.eutlId}</Table.Td>
                    <Table.Td>{installation.permitId ?? '–'}</Table.Td>
                    <Table.Td>{installation.activityCode ?? '–'}</Table.Td>
                    <Table.Td>{installation.eligibleProducts.join(', ')}</Table.Td>
                    <Table.Td style={{ whiteSpace: 'nowrap' }}>
                      <Text size="sm">
                        {formatDate(installation.validFrom)} – {formatDate(installation.validTo)}
                      </Text>
                    </Table.Td>
                    <Table.Td>
                      <StatusBadge status={installation.active ? 'ACTIVE' : 'INACTIVE'} />
                    </Table.Td>
                  </Table.Tr>
                ))}
              </Table.Tbody>
            </Table>
          </ScrollTable>
        )}
      </QueryState>
    </Card>
  );
}
