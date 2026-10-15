import { Button, Group, Stack } from '@mantine/core';
import { IconLink, IconPlus } from '@tabler/icons-react';
import type { ColDef } from 'ag-grid-community';
import { useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { $api } from '@/shared/api/client';
import { Can } from '@/shared/auth/Can';
import { QueryState } from '@/shared/components/QueryState';
import { statusLabel } from '@/shared/components/StatusBadge';
import { DataGrid } from '@/shared/grid/DataGrid';
import { AliasModal, CreateInstrumentModal } from './InstrumentActions';
import { type Instrument } from './shared';

export function InstrumentsTab() {
  const { t } = useTranslation();
  const instruments = $api.useQuery('get', '/api/v1/market-data/instruments', {
    params: { query: { activeOnly: false } },
  });
  const [creating, setCreating] = useState(false);
  const [aliasFor, setAliasFor] = useState<string | null>(null);
  const columns = useMemo<ColDef<Instrument>[]>(
    () => [
      { field: 'code', headerName: t('marketData.code'), width: 230 },
      {
        field: 'kind',
        headerName: t('marketData.kind'),
        width: 140,
        valueFormatter: (p) => statusLabel(String(p.value)),
      },
      { field: 'description', headerName: t('marketData.descriptionColumn'), flex: 1 },
      { field: 'bucketCode', headerName: t('positions.bucket'), width: 150 },
      { field: 'productionYear', headerName: t('positions.vintage'), width: 100 },
      { field: 'allowanceProductCode', headerName: t('trades.form.allowanceProduct'), width: 150 },
      { field: 'currency', headerName: t('trades.form.currency'), width: 100 },
      { field: 'tenor', headerName: t('marketData.tenor'), width: 90 },
      { field: 'active', headerName: t('marketData.active'), width: 90 },
    ],
    [t],
  );
  return (
    <Stack>
      <Group justify="flex-end">
        <Can op="marketData_addAlias">
          <Button
            variant="default"
            leftSection={<IconLink size={16} />}
            onClick={() => setAliasFor('')}
            data-testid="add-alias"
          >
            {t('marketData.addAlias')}
          </Button>
        </Can>
        <Can op="marketData_createInstrument">
          <Button leftSection={<IconPlus size={16} />} onClick={() => setCreating(true)} data-testid="new-instrument">
            {t('marketData.newInstrument')}
          </Button>
        </Can>
      </Group>
      <QueryState isPending={instruments.isPending} error={instruments.error}>
        <DataGrid<Instrument>
          rowData={instruments.data ?? []}
          columnDefs={columns}
          rowId={(i) => i.code}
          height={520}
        />
      </QueryState>
      {creating && <CreateInstrumentModal onClose={() => setCreating(false)} />}
      {aliasFor !== null && (
        <AliasModal
          instruments={(instruments.data ?? []).filter((i) => i.active).map((i) => i.code)}
          initialInstrument={aliasFor || undefined}
          onClose={() => setAliasFor(null)}
        />
      )}
    </Stack>
  );
}
