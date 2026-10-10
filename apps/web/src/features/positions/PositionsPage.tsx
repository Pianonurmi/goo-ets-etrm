import { BarChart } from '@mantine/charts';
import { Alert, Card, NumberInput, SimpleGrid, Stack, Table, Tabs, Text, Title } from '@mantine/core';
import type { ColDef } from 'ag-grid-community';
import { useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useSearchParams } from 'react-router';
import { $api } from '@/shared/api/client';
import type { Schema } from '@/shared/api/types';
import { PageHeader } from '@/shared/components/PageHeader';
import { QueryState } from '@/shared/components/QueryState';
import { useEntity } from '@/shared/context/EntityContext';
import { formatDate, formatDecimal } from '@/shared/format/format';
import { DataGrid } from '@/shared/grid/DataGrid';
import { ladderSeries } from './model/ladder';

type BucketPosition = Schema<'BucketPosition'>;

function Figure({ label, value, color }: { label: string; value: string; color?: string }) {
  return (
    <Card withBorder>
      <Text size="xs" c="dimmed">
        {label}
      </Text>
      <Text size="xl" fw={700} c={color}>
        {value}
      </Text>
    </Card>
  );
}

function GooPosition({ entityId }: { entityId: string }) {
  const { t } = useTranslation();
  const report = $api.useQuery('get', '/api/v1/positions/goo', { params: { query: { legalEntityId: entityId } } });
  const columns = useMemo<ColDef<BucketPosition>[]>(
    () => [
      { field: 'bucketCode', headerName: t('positions.bucket'), flex: 1, minWidth: 180 },
      { field: 'productionYear', headerName: t('positions.vintage'), width: 110 },
      { field: 'liveMwh', headerName: t('positions.live'), width: 120, type: 'rightAligned' },
      { field: 'heldMwh', headerName: t('positions.held'), width: 120, type: 'rightAligned' },
      { field: 'reservedMwh', headerName: t('positions.reserved'), width: 120, type: 'rightAligned' },
      { field: 'inTransferMwh', headerName: t('positions.inTransfer'), width: 130, type: 'rightAligned' },
      { field: 'expiringWithin90DaysMwh', headerName: t('positions.expiring90'), width: 160, type: 'rightAligned' },
      { field: 'lotCount', headerName: t('positions.lots'), width: 100, type: 'rightAligned' },
    ],
    [t],
  );
  return (
    <QueryState isPending={report.isPending} error={report.error}>
      {report.data && (
        <Stack>
          <SimpleGrid cols={{ base: 2, md: 4 }}>
            <Figure label={t('positions.liveTotal')} value={`${formatDecimal(report.data.totalLiveMwh)} MWh`} />
            <Figure
              label={t('positions.openObligations')}
              value={`${formatDecimal(report.data.totalOpenObligationMwh)} MWh`}
            />
            <Figure
              label={t('allocation.covered')}
              value={`${formatDecimal(report.data.coverage.totalCoveredMwh)} MWh`}
              color="teal"
            />
            <Figure
              label={t('allocation.shortfallLabel')}
              value={`${formatDecimal(report.data.coverage.totalShortfallMwh)} MWh`}
              color={report.data.coverage.totalShortfallMwh > 0 ? 'red' : undefined}
            />
          </SimpleGrid>
          <Card withBorder>
            <Title order={5} mb="sm">
              {t('positions.expiryLadder')}
            </Title>
            <BarChart
              h={260}
              data={ladderSeries(report.data.expiryLadder)}
              dataKey="month"
              series={[{ name: 'mwh', label: 'MWh', color: 'teal.6' }]}
              tickLine="y"
              data-testid="expiry-chart"
            />
          </Card>
          <DataGrid<BucketPosition>
            rowData={report.data.inventory}
            columnDefs={columns}
            rowId={(row) => `${row.bucketCode}-${row.productionYear}`}
            height={360}
            overlayNoRowsTemplate={t('common.empty')}
          />
        </Stack>
      )}
    </QueryState>
  );
}

function EtsPosition({ entityId }: { entityId: string }) {
  const { t } = useTranslation();
  const [year, setYear] = useState<number>(new Date().getFullYear());
  const ladder = $api.useQuery('get', '/api/v1/positions/ets', {
    params: { query: { legalEntityId: entityId, complianceYear: year } },
  });
  return (
    <Stack>
      <NumberInput
        label={t('positions.complianceYear')}
        value={year}
        onChange={(value) => setYear(Number(value))}
        w={180}
        min={2005}
      />
      <QueryState isPending={ladder.isPending} error={ladder.error}>
        {ladder.data && (
          <Stack>
            <SimpleGrid cols={{ base: 2, md: 4 }}>
              <Figure label={t('positions.emissions')} value={`${formatDecimal(ladder.data.emissionsBasis)} t`} />
              <Figure label={t('positions.heldAllowances')} value={`${formatDecimal(ladder.data.heldAllowances)} t`} />
              <Figure
                label={t('positions.netPosition')}
                value={`${formatDecimal(ladder.data.netPosition)} t`}
                color={ladder.data.netPosition < 0 ? 'red' : 'teal'}
              />
              <Figure label={t('positions.deadline')} value={formatDate(ladder.data.surrenderDeadline)} />
              <Figure label={t('positions.openPurchases')} value={`${formatDecimal(ladder.data.openPurchases)} t`} />
              <Figure label={t('positions.openSales')} value={`${formatDecimal(ladder.data.openSales)} t`} />
              <Figure
                label={t('positions.freeAllocation')}
                value={`${formatDecimal(ladder.data.freeAllocationReceived)} / ${formatDecimal(ladder.data.freeAllocationExpected)} t`}
              />
              <Figure label={t('positions.surrendered')} value={`${formatDecimal(ladder.data.surrendered)} t`} />
            </SimpleGrid>
            {(ladder.data.installations ?? []).length === 0 ? (
              <Alert color="gray">{t('positions.noInstallations')}</Alert>
            ) : (
              <Table withTableBorder striped>
                <Table.Thead>
                  <Table.Tr>
                    <Table.Th>{t('positions.installation')}</Table.Th>
                    <Table.Th>{t('positions.basis')}</Table.Th>
                    <Table.Th ta="right">{t('positions.emissions')}</Table.Th>
                    <Table.Th ta="right">{t('positions.held')}</Table.Th>
                    <Table.Th ta="right">{t('positions.surrendered')}</Table.Th>
                    <Table.Th>{t('allocation.status')}</Table.Th>
                  </Table.Tr>
                </Table.Thead>
                <Table.Tbody>
                  {(ladder.data.installations ?? []).map((line) => (
                    <Table.Tr key={line.installationId}>
                      <Table.Td>{line.name}</Table.Td>
                      <Table.Td>{line.basis}</Table.Td>
                      <Table.Td ta="right">{formatDecimal(line.emissions)}</Table.Td>
                      <Table.Td ta="right">{formatDecimal(line.held)}</Table.Td>
                      <Table.Td ta="right">{formatDecimal(line.surrendered)}</Table.Td>
                      <Table.Td>{line.status}</Table.Td>
                    </Table.Tr>
                  ))}
                </Table.Tbody>
              </Table>
            )}
          </Stack>
        )}
      </QueryState>
    </Stack>
  );
}

/** Coverage, expiry and ETS dashboards of the selected entity. */
export function PositionsPage() {
  const { t } = useTranslation();
  const { entity } = useEntity();
  const [params, setParams] = useSearchParams();
  const tab = params.get('tab') ?? 'goo';
  return (
    <Stack>
      <PageHeader
        title={t('positions.title')}
        description={t('positions.description', { entity: entity?.code ?? '' })}
      />
      {!entity ? (
        <Alert color="gray">{t('header.noEntity')}</Alert>
      ) : (
        <Tabs
          value={tab}
          onChange={(value) => setParams(value && value !== 'goo' ? { tab: value } : {}, { replace: true })}
          keepMounted={false}
        >
          <Tabs.List>
            <Tabs.Tab value="goo">{t('positions.goo')}</Tabs.Tab>
            <Tabs.Tab value="ets">{t('positions.ets')}</Tabs.Tab>
          </Tabs.List>
          <Tabs.Panel value="goo" pt="md">
            <GooPosition entityId={entity.id} />
          </Tabs.Panel>
          <Tabs.Panel value="ets" pt="md">
            <EtsPosition entityId={entity.id} />
          </Tabs.Panel>
        </Tabs>
      )}
    </Stack>
  );
}
