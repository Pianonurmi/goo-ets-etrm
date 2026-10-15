import { Badge, Button, Drawer, Group, Paper, Stack, Switch } from '@mantine/core';
import { IconPlus } from '@tabler/icons-react';
import type { ColDef } from 'ag-grid-community';
import { useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { $api } from '@/shared/api/client';
import { Can } from '@/shared/auth/Can';
import { FactGrid } from '@/shared/components/DetailLayout';
import { QueryState } from '@/shared/components/QueryState';
import { TableSkeleton } from '@/shared/components/Skeletons';
import { StatusBadge } from '@/shared/components/StatusBadge';
import { formatDate, formatDecimal } from '@/shared/format/format';
import { DataGrid } from '@/shared/grid/DataGrid';
import { DeviceForecast, LoadFactorsSection } from '../DeviceForecast';
import { HistoryDrawer } from '../HistoryDrawer';
import { EditDeviceModal, NewDeviceModal } from '../ReferenceEditModals';
import { useOpenRecord, useRegistries } from './hooks';
import { type Device, activeColumn, EditButton, HistoryButton } from './shared';

export function DeviceDrawer({ deviceId, onClose }: { deviceId: string; onClose: () => void }) {
  const { t } = useTranslation();
  const [history, setHistory] = useState(false);
  const [editing, setEditing] = useState(false);
  const detail = $api.useQuery('get', '/api/v1/reference/production-devices/{id}', {
    params: { path: { id: deviceId } },
  });
  const registries = useRegistries();
  const device = detail.data;
  const registry = (registries.data ?? []).find((candidate) => candidate.id === device?.registryId);
  const codes = Object.entries(device?.externalCodes ?? {});
  return (
    <Drawer
      opened
      onClose={onClose}
      position="right"
      size="xl"
      title={device ? `${device.gsrn} · ${device.name}` : ''}
      data-testid="device-drawer"
    >
      <QueryState isPending={detail.isPending} error={detail.error}>
        {device && (
          <Stack>
            <Group gap="xs" justify="space-between">
              <Group gap="xs">
                <StatusBadge status={device.active ? 'ACTIVE' : 'INACTIVE'} data-testid="device-status" />
                {device.ownDevice && (
                  <Badge size="sm" variant="light" tt="none">
                    {t('admin.ownDevice')}
                  </Badge>
                )}
              </Group>
              <Group gap={4}>
                <Can op="referenceData_updateProductionDevice">
                  <EditButton onClick={() => setEditing(true)} />
                </Can>
                <HistoryButton onClick={() => setHistory(true)} />
              </Group>
            </Group>
            <Paper withBorder p="md">
              <FactGrid
                cols={3}
                facts={[
                  { label: t('ets.name'), value: device.name, testId: 'device-fact-name' },
                  {
                    label: t('admin.capacityKw'),
                    value: formatDecimal(device.capacityKw, 0, 3),
                    testId: 'device-fact-capacity',
                  },
                  { label: t('ets.country'), value: device.country },
                  { label: t('admin.energySource'), value: device.energySourceCode },
                  { label: t('admin.technology'), value: device.technologyCode },
                  { label: t('admin.commissioned'), value: formatDate(device.commissioningDate) },
                  { label: t('admin.decommissioned'), value: formatDate(device.decommissioningDate) || '–' },
                  { label: t('admin.supportSchemes'), value: device.supportSchemeCodes.join(', ') || '–' },
                  { label: t('admin.labels'), value: device.labelCodes.join(', ') || '–' },
                  { label: t('admin.ownerType'), value: t(`admin.ownerTypes.${device.ownerType}`) },
                  {
                    label: t('admin.issuanceLagDays'),
                    value: t('admin.days', { count: device.issuanceLagDays ?? 0 }),
                    testId: 'device-fact-issuance-lag',
                  },
                  { label: t('admin.registry'), value: registry?.code ?? '–' },
                  { label: t('admin.recordVersion'), value: device.version },
                  {
                    label: t('admin.externalCodes'),
                    value: codes.map(([key, value]) => `${key} ${value}`).join(', ') || '–',
                  },
                ]}
              />
            </Paper>
            <DeviceForecast deviceId={device.id} issuanceLagDays={device.issuanceLagDays} />
          </Stack>
        )}
      </QueryState>
      {device && history && (
        <HistoryDrawer table="production_device" id={device.id} title={device.name} onClose={() => setHistory(false)} />
      )}
      {device && editing && <EditDeviceModal device={device} onClose={() => setEditing(false)} />}
    </Drawer>
  );
}

export function DevicesTab() {
  const { t } = useTranslation();
  const [own, setOwn] = useState(true);
  const [open, setOpen] = useOpenRecord();
  const [creating, setCreating] = useState(false);
  const devices = $api.useQuery('get', '/api/v1/reference/production-devices', { params: { query: { own } } });
  const columns = useMemo<ColDef<Device>[]>(
    () => [
      { field: 'gsrn', headerName: 'GSRN', width: 200 },
      { field: 'name', headerName: t('ets.name'), flex: 1, minWidth: 200 },
      { field: 'country', headerName: t('ets.country'), width: 100 },
      { field: 'energySourceCode', headerName: t('admin.energySource'), width: 190 },
      { field: 'technologyCode', headerName: t('admin.technology'), width: 200 },
      { field: 'capacityKw', headerName: t('admin.capacityKw'), width: 130, type: 'rightAligned' },
      { field: 'commissioningDate', headerName: t('admin.commissioned'), width: 140 },
      activeColumn<Device>(t('admin.active')),
    ],
    [t],
  );
  return (
    <Stack>
      <Group justify="space-between">
        <Switch label={t('admin.ownDevices')} checked={own} onChange={(e) => setOwn(e.currentTarget.checked)} />
        <Can op="referenceData_createProductionDevice">
          <Button leftSection={<IconPlus size={16} />} onClick={() => setCreating(true)} data-testid="new-device">
            {t('admin.newDevice')}
          </Button>
        </Can>
      </Group>
      <QueryState isPending={false} error={devices.error}>
        {devices.isPending ? (
          <Paper withBorder>
            <TableSkeleton rows={4} />
          </Paper>
        ) : (
          <DataGrid<Device>
            rowData={devices.data ?? []}
            columnDefs={columns}
            rowId={(row) => row.id}
            fitRows={12}
            onRowClicked={(event) => setOpen(event.data?.id ?? null)}
            overlayNoRowsTemplate={t('common.empty')}
          />
        )}
      </QueryState>
      <LoadFactorsSection />
      {open && <DeviceDrawer deviceId={open} onClose={() => setOpen(null)} />}
      {creating && (
        <NewDeviceModal
          onClose={() => setCreating(false)}
          onCreated={(id) => {
            setCreating(false);
            setOpen(id);
          }}
        />
      )}
    </Stack>
  );
}
