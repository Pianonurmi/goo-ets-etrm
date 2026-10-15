import { Button, Group, Stack, Switch } from '@mantine/core';
import { IconGauge, IconPlus } from '@tabler/icons-react';
import type { ColDef, ICellRendererParams } from 'ag-grid-community';
import { useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useNavigate } from 'react-router';
import { $api } from '@/shared/api/client';
import { Can } from '@/shared/auth/Can';
import { notifySuccess } from '@/shared/components/notify';
import { ProblemAlert } from '@/shared/components/ProblemAlert';
import { QueryState } from '@/shared/components/QueryState';
import { TableSkeleton } from '@/shared/components/Skeletons';
import { formatDecimal } from '@/shared/format/format';
import { DataGrid } from '@/shared/grid/DataGrid';
import { useScopeNames, useUtilisations } from './hooks';
import { limitUnit, scopeLabel, type Limit } from './model/limits';
import { RequestLimitModal, useInvalidateRisk } from './RequestLimitModal';
import { LimitStatusBadge, UtilisationCell, type Utilisation } from './shared';

export interface LimitRow extends Limit {
  scopeText: string;
  current?: Utilisation;
}

export function LimitsTab({ entityId }: { entityId: string }) {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const names = useScopeNames(entityId);
  const invalidate = useInvalidateRisk();
  const [all, setAll] = useState(false);
  const [requesting, setRequesting] = useState(false);
  const limits = $api.useQuery('get', '/api/v1/risk/limits', {
    params: { query: { legalEntityId: entityId, activeOnly: !all } },
  });
  const utilisations = useUtilisations(limits.data ?? []);
  const measure = $api.useMutation('post', '/api/v1/risk/measure', {
    meta: { notify: false },
    onSuccess: (measured) => {
      notifySuccess(t('risk.measured', { count: measured.length }));
      void invalidate();
    },
  });
  const rows = useMemo<LimitRow[]>(
    () =>
      (limits.data ?? []).map((limit) => ({
        ...limit,
        scopeText: scopeLabel(limit.scope, names) || t('risk.wholeEntity'),
        current: utilisations.get(limit.id),
      })),
    [limits.data, names, utilisations, t],
  );
  const columns = useMemo<ColDef<LimitRow>[]>(
    () => [
      {
        field: 'type',
        headerName: t('risk.limitType'),
        width: 190,
        valueFormatter: (p) => t(`risk.types.${String(p.value)}`),
      },
      { field: 'scopeText', headerName: t('risk.scope'), flex: 1, minWidth: 200 },
      {
        field: 'amount',
        headerName: t('risk.amount'),
        width: 160,
        type: 'rightAligned',
        valueFormatter: (p) => (p.data ? `${formatDecimal(p.data.amount, 0, 2)} ${limitUnit(p.data)}` : ''),
      },
      { field: 'warningPct', headerName: t('risk.warningPct'), width: 110, type: 'rightAligned' },
      {
        colId: 'utilisation',
        headerName: t('risk.utilisation'),
        width: 170,
        valueGetter: (p) => p.data?.current?.utilisation,
        cellStyle: { display: 'flex', alignItems: 'center' },
        cellRenderer: (p: ICellRendererParams<LimitRow>) => <UtilisationCell value={p.data?.current} />,
      },
      {
        colId: 'value',
        headerName: t('risk.measuredValue'),
        width: 150,
        type: 'rightAligned',
        valueGetter: (p) => p.data?.current?.value,
        valueFormatter: (p) => (p.value == null ? '' : formatDecimal(p.value as number, 0, 2)),
      },
      {
        field: 'status',
        headerName: t('allocation.status'),
        width: 150,
        cellRenderer: (p: ICellRendererParams<LimitRow>) => p.data && <LimitStatusBadge status={p.data.status} />,
      },
      { field: 'validFrom', headerName: t('risk.validFrom'), width: 120 },
      { field: 'requestedBy', headerName: t('risk.requestedBy'), width: 130 },
    ],
    [t],
  );
  return (
    <Stack>
      <Group justify="space-between">
        <Switch label={t('risk.showInactive')} checked={all} onChange={(e) => setAll(e.currentTarget.checked)} />
        <Group gap="xs">
          <Can op="risk_measure">
            <Button
              variant="default"
              leftSection={<IconGauge size={16} />}
              loading={measure.isPending}
              onClick={() => measure.mutate({ params: { query: { legalEntityId: entityId } } })}
              data-testid="measure"
            >
              {t('risk.measure')}
            </Button>
          </Can>
          <Can op="risk_requestLimit">
            <Button
              leftSection={<IconPlus size={16} />}
              onClick={() => setRequesting(true)}
              data-testid="request-limit"
            >
              {t('risk.requestLimit')}
            </Button>
          </Can>
        </Group>
      </Group>
      <ProblemAlert error={measure.error} />
      <QueryState isPending={false} error={limits.error}>
        {limits.isPending ? (
          <TableSkeleton />
        ) : (
          <DataGrid<LimitRow>
            rowData={rows}
            columnDefs={columns}
            rowId={(row) => row.id}
            fitRows={10}
            onRowClicked={(event) => {
              if (event.data) void navigate(`/risk/limits/${event.data.id}`);
            }}
            rowStyle={{ cursor: 'pointer' }}
            overlayNoRowsTemplate={t('risk.noLimits')}
          />
        )}
      </QueryState>
      {requesting && <RequestLimitModal opened onClose={() => setRequesting(false)} entityId={entityId} />}
    </Stack>
  );
}
