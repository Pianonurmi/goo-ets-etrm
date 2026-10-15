import { useUrlState } from '@/shared/useUrlState';
import { Alert, Anchor, Button, Drawer, Group, Modal, Paper, Stack, Table, Text, TextInput } from '@mantine/core';
import { IconCalculator } from '@tabler/icons-react';
import type { ColDef, ICellRendererParams } from 'ag-grid-community';
import dayjs from 'dayjs';
import { useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link } from 'react-router';
import { $api } from '@/shared/api/client';
import { Can } from '@/shared/auth/Can';
import { FactGrid } from '@/shared/components/DetailLayout';
import { EmptyState } from '@/shared/components/EmptyState';
import { Money, Quantity } from '@/shared/components/Figures';
import { notifySuccess } from '@/shared/components/notify';
import { ProblemAlert } from '@/shared/components/ProblemAlert';
import { StatusBadge } from '@/shared/components/StatusBadge';
import { QueryState } from '@/shared/components/QueryState';
import { ScrollTable } from '@/shared/components/ScrollTable';
import { TableSkeleton } from '@/shared/components/Skeletons';
import { formatDate, formatDateTime } from '@/shared/format/format';
import { DataGrid } from '@/shared/grid/DataGrid';
import { Price, RangeFilter } from './components';
import { useInvalidateClearing } from './hooks';
import {
  lastBusinessDay,
  newestFirst,
  rangeIsValid,
  readRange,
  tradedContracts,
  type Account,
  type Run,
} from './model/clearing';

/** Computes the margin of one business day; the answer opens as the run, the same day again changes nothing. */
function ComputeMarginModal({
  account,
  onClose,
  onComputed,
}: {
  account: Account;
  onClose: () => void;
  onComputed: (run: Run) => void;
}) {
  const { t } = useTranslation();
  const invalidate = useInvalidateClearing();
  const [businessDate, setBusinessDate] = useState(() => lastBusinessDay(dayjs().format('YYYY-MM-DD')));
  const compute = $api.useMutation('post', '/api/v1/clearing/accounts/{id}/margin', {
    meta: { notify: false },
    onSuccess: (run) => {
      notifySuccess(t('clearing.marginComputed', { date: formatDate(run.businessDate), version: run.version }));
      void invalidate();
      onComputed(run);
      onClose();
    },
  });
  return (
    <Modal opened onClose={onClose} title={t('clearing.computeMargin')} data-testid="clearing-compute-modal">
      <Stack>
        <ProblemAlert error={compute.error} />
        <Text size="sm" c="dimmed">
          {t('clearing.computeMarginHint', { account: account.code })}
        </Text>
        <TextInput
          type="date"
          label={t('clearing.businessDate')}
          value={businessDate}
          onChange={(e) => setBusinessDate(e.currentTarget.value)}
          w={200}
          required
          data-testid="clearing-compute-date"
        />
        <Button
          disabled={!businessDate}
          loading={compute.isPending}
          onClick={() => compute.mutate({ params: { path: { id: account.id } }, body: { businessDate } })}
          data-testid="clearing-compute-submit"
        >
          {t('clearing.computeMargin')}
        </Button>
      </Stack>
    </Modal>
  );
}

/** One margin run: the total and, contract by contract, the position, the settlement prices and the margin. */
function RunDrawer({ runId, onClose }: { runId: string; onClose: () => void }) {
  const { t } = useTranslation();
  const detail = $api.useQuery('get', '/api/v1/clearing/margin/{runId}', { params: { path: { runId } } });
  const run = detail.data;
  return (
    <Drawer
      opened
      onClose={onClose}
      position="right"
      size="xl"
      title={run ? t('clearing.runTitle', { date: formatDate(run.businessDate), version: run.version }) : ''}
      data-testid="clearing-run-drawer"
    >
      <QueryState isPending={detail.isPending} error={detail.error}>
        {run && (
          <Stack>
            <Text size="sm" c="dimmed">
              {t('clearing.runHint')}
            </Text>
            {run.supersededAt && (
              <Alert color="gray" data-testid="clearing-run-superseded">
                {t('clearing.runSuperseded', { at: formatDateTime(run.supersededAt) })}
              </Alert>
            )}
            {run.staleReason && (
              <Alert color="yellow" title={t('clearing.runStale')} data-testid="clearing-run-stale">
                {run.staleReason}
              </Alert>
            )}
            <FactGrid
              cols={3}
              facts={[
                { label: t('clearing.businessDate'), value: formatDate(run.businessDate) },
                { label: t('clearing.version'), value: run.version },
                {
                  label: t('clearing.variationMargin'),
                  value: <Money value={run.variationMargin} currency={run.currency} signed />,
                  testId: 'clearing-run-total',
                },
                { label: t('clearing.computedBy'), value: run.computedBy },
                { label: t('clearing.computedAt'), value: formatDateTime(run.computedAt) },
                {
                  label: t('clearing.inputsHash'),
                  value: (
                    <Text span ff="monospace" size="xs" title={run.inputsHash}>
                      {run.inputsHash.slice(0, 12)}
                    </Text>
                  ),
                },
              ]}
            />
            <ScrollTable label={t('clearing.runLines')} minWidth={680}>
              <Table data-testid="clearing-run-lines" verticalSpacing="xs">
                <Table.Thead>
                  <Table.Tr>
                    <Table.Th>{t('clearing.contract')}</Table.Th>
                    <Table.Th ta="right">{t('clearing.positionBefore')}</Table.Th>
                    <Table.Th ta="right">{t('clearing.traded')}</Table.Th>
                    <Table.Th ta="right">{t('clearing.positionAfter')}</Table.Th>
                    <Table.Th ta="right">{t('clearing.previousPrice')}</Table.Th>
                    <Table.Th ta="right">{t('clearing.settlementPrice')}</Table.Th>
                    <Table.Th ta="right">{t('clearing.variationMargin')}</Table.Th>
                  </Table.Tr>
                </Table.Thead>
                <Table.Tbody>
                  {run.lines.map((line) => (
                    <Table.Tr key={line.contractCode}>
                      <Table.Td>{line.contractCode}</Table.Td>
                      <Table.Td ta="right">
                        <Quantity value={line.positionBefore} />
                      </Table.Td>
                      <Table.Td ta="right">
                        <Quantity value={line.tradedQuantity} />
                      </Table.Td>
                      <Table.Td ta="right">
                        <Quantity value={line.positionAfter} />
                      </Table.Td>
                      <Table.Td ta="right">
                        <Price value={line.previousSettlementPrice} />
                      </Table.Td>
                      <Table.Td ta="right">
                        <Price value={line.settlementPrice} />
                      </Table.Td>
                      <Table.Td ta="right">
                        <Money value={line.variationMargin} currency={run.currency} signed />
                      </Table.Td>
                    </Table.Tr>
                  ))}
                </Table.Tbody>
              </Table>
            </ScrollTable>
            <Anchor
              component={Link}
              to={`/clearing?tab=statements&account=${run.accountId}&from=${run.businessDate}&to=${run.businessDate}`}
              size="sm"
              data-testid="clearing-run-statement-link"
            >
              {t('clearing.runStatementLink')}
            </Anchor>
          </Stack>
        )}
      </QueryState>
    </Drawer>
  );
}

/** Daily variation margin of the account: the current run of each day, newest first. */
export function MarginTab({ account }: { account: Account | null }) {
  const { t } = useTranslation();
  const { params, set } = useUrlState();
  const [computing, setComputing] = useState(false);
  const today = dayjs().format('YYYY-MM-DD');
  const range = readRange(params.get('from'), params.get('to'), today);
  const valid = rangeIsValid(range);
  const runId = params.get('run');
  const runs = $api.useQuery(
    'get',
    '/api/v1/clearing/accounts/{id}/margin',
    { params: { path: { id: account?.id ?? '' }, query: range } },
    { enabled: Boolean(account) && valid },
  );
  const rows = useMemo(() => newestFirst(runs.data ?? []), [runs.data]);
  const columns = useMemo<ColDef<Run>[]>(
    () => [
      { field: 'businessDate', headerName: t('clearing.businessDate'), width: 140, sort: 'desc' },
      { field: 'version', headerName: t('clearing.version'), width: 100, type: 'rightAligned' },
      {
        colId: 'stale',
        headerName: t('clearing.state'),
        width: 120,
        valueGetter: (p) => (p.data?.staleReason ? 'STALE' : ''),
        cellRenderer: (p: ICellRendererParams<Run>) => (p.data?.staleReason ? <StatusBadge status="STALE" /> : null),
      },
      {
        field: 'variationMargin',
        headerName: t('clearing.variationMargin'),
        width: 180,
        type: 'rightAligned',
        cellRenderer: (p: ICellRendererParams<Run>) =>
          p.data && <Money value={p.data.variationMargin} currency={p.data.currency} signed />,
      },
      {
        colId: 'contracts',
        headerName: t('clearing.contracts'),
        width: 120,
        type: 'rightAligned',
        valueGetter: (p) => p.data?.lines.length ?? 0,
      },
      {
        colId: 'traded',
        headerName: t('clearing.contractsTraded'),
        width: 150,
        type: 'rightAligned',
        valueGetter: (p) => (p.data ? tradedContracts(p.data) : 0),
      },
      { field: 'computedBy', headerName: t('clearing.computedBy'), width: 150 },
      {
        field: 'computedAt',
        headerName: t('clearing.computedAt'),
        flex: 1,
        minWidth: 160,
        valueFormatter: (p) => formatDateTime(p.value as string),
      },
    ],
    [t],
  );
  if (!account) {
    return (
      <Paper withBorder radius="lg">
        <EmptyState title={t('clearing.chooseAccount')} description={t('clearing.chooseAccountHint')} />
      </Paper>
    );
  }
  return (
    <Stack gap="sm">
      <Group justify="space-between" align="flex-end">
        <RangeFilter from={range.from} to={range.to} invalid={!valid} onChange={(patch) => set(patch)} />
        <Can op="clearing_computeMargin">
          <Button
            leftSection={<IconCalculator size={16} />}
            onClick={() => setComputing(true)}
            data-testid="clearing-compute-margin"
          >
            {t('clearing.computeMargin')}
          </Button>
        </Can>
      </Group>
      <Text size="sm" c="dimmed" maw={760}>
        {t('clearing.marginHint')}
      </Text>
      <QueryState isPending={false} error={runs.error}>
        {runs.isPending && valid ? (
          <Paper withBorder radius="lg">
            <TableSkeleton rows={6} />
          </Paper>
        ) : rows.length === 0 ? (
          <Paper withBorder radius="lg">
            <EmptyState
              title={t('clearing.noRuns')}
              description={t('clearing.noRunsHint')}
              testId="clearing-runs-empty"
            />
          </Paper>
        ) : (
          <div data-testid="clearing-runs-grid">
            <DataGrid<Run>
              rowData={rows}
              columnDefs={columns}
              rowId={(row) => row.id}
              height={Math.min(520, 46 + rows.length * 40 + 2)}
              onRowClicked={(event) => event.data && set({ run: event.data.id })}
              rowStyle={{ cursor: 'pointer' }}
            />
          </div>
        )}
      </QueryState>
      {computing && (
        <ComputeMarginModal
          account={account}
          onClose={() => setComputing(false)}
          onComputed={(run) => set({ run: run.id })}
        />
      )}
      {runId && <RunDrawer runId={runId} onClose={() => set({ run: null })} />}
    </Stack>
  );
}
