import { Button, Group, Stack, TextInput } from '@mantine/core';
import { IconPlayerPlay } from '@tabler/icons-react';
import { useMutation } from '@tanstack/react-query';
import type { ColDef, ICellRendererParams } from 'ag-grid-community';
import dayjs from 'dayjs';
import { useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useNavigate } from 'react-router';
import { awaitRun } from '@/shared/api/asyncRun';
import { api } from '@/shared/api/client';
import type { Body } from '@/shared/api/types';
import { Can } from '@/shared/auth/Can';
import { ProblemAlert } from '@/shared/components/ProblemAlert';
import { QueryState } from '@/shared/components/QueryState';
import { TableSkeleton } from '@/shared/components/Skeletons';
import { StatusBadge } from '@/shared/components/StatusBadge';
import { DataGrid } from '@/shared/grid/DataGrid';
import { useRecentRuns } from './hooks';
import { MtmCell, type Run } from './shared';

export function Runs({ entityId }: { entityId: string }) {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const [date, setDate] = useState(dayjs().subtract(1, 'day').format('YYYY-MM-DD'));
  const runs = useRecentRuns(entityId);
  const start = useMutation({
    meta: { notify: false },
    mutationFn: async (body: Body<'RunRequest'>) => {
      const { data } = await api.POST('/api/v1/valuation/runs', { body });
      if (!data) throw new Error('The valuation run did not start');
      return awaitRun(data);
    },
    onSuccess: (valuationRunId) => void navigate(`/valuation/runs/${valuationRunId}`),
  });
  const columns = useMemo<ColDef<Run>[]>(
    () => [
      { field: 'valuationDate', headerName: t('valuation.date'), width: 140, sort: 'desc' },
      {
        field: 'kind',
        headerName: t('marketData.kind'),
        width: 130,
        valueFormatter: (p) => t(`valuation.kinds.${String(p.value)}`),
      },
      {
        field: 'status',
        headerName: t('allocation.status'),
        width: 170,
        cellRenderer: (p: ICellRendererParams<Run>) => p.data && <StatusBadge status={p.data.status} />,
      },
      {
        field: 'totalMtmBase',
        headerName: t('valuation.totalMtm'),
        flex: 1,
        minWidth: 170,
        type: 'rightAligned',
        cellRenderer: (p: ICellRendererParams<Run>) =>
          p.data && <MtmCell value={p.data.totalMtmBase} currency={p.data.baseCurrency} />,
      },
      {
        field: 'exceptions',
        headerName: t('valuation.exceptions'),
        width: 130,
        type: 'rightAligned',
        cellStyle: (p) => ((p.value as number) > 0 ? { color: 'var(--mantine-color-orange-text)' } : null),
      },
      { field: 'createdBy', headerName: t('marketData.recordedBy'), width: 130 },
      { field: 'signedOffBy', headerName: t('curves.signedOffBy'), width: 140 },
    ],
    [t],
  );
  return (
    <Stack>
      <Can op="valuation_run">
        <Group align="flex-end">
          <TextInput
            type="date"
            label={t('valuation.date')}
            value={date}
            onChange={(e) => setDate(e.currentTarget.value)}
          />
          <Button
            leftSection={<IconPlayerPlay size={16} />}
            loading={start.isPending}
            onClick={() => start.mutate({ legalEntityId: entityId, valuationDate: date, kind: 'EOD' })}
            data-testid="start-run"
          >
            {t('valuation.start')}
          </Button>
        </Group>
      </Can>
      <ProblemAlert error={start.error} />
      <QueryState isPending={false} error={runs.error}>
        {runs.isPending ? (
          <TableSkeleton />
        ) : (
          <DataGrid<Run>
            rowData={runs.data ?? []}
            columnDefs={columns}
            rowId={(run) => run.id}
            height={440}
            onRowClicked={(event) => {
              if (event.data) void navigate(`/valuation/runs/${event.data.id}`);
            }}
            rowStyle={{ cursor: 'pointer' }}
            overlayNoRowsTemplate={t('valuation.noRuns')}
          />
        )}
      </QueryState>
    </Stack>
  );
}
