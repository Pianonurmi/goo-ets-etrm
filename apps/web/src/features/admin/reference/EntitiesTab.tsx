import { Button, Drawer, Group, Modal, Paper, Stack, TextInput } from '@mantine/core';
import { IconPlus } from '@tabler/icons-react';
import type { ColDef } from 'ag-grid-community';
import dayjs from 'dayjs';
import { useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { $api } from '@/shared/api/client';
import { Can } from '@/shared/auth/Can';
import { FactGrid } from '@/shared/components/DetailLayout';
import { notifySuccess } from '@/shared/components/notify';
import { ProblemAlert } from '@/shared/components/ProblemAlert';
import { QueryState } from '@/shared/components/QueryState';
import { TableSkeleton } from '@/shared/components/Skeletons';
import { DataGrid } from '@/shared/grid/DataGrid';
import { HistoryDrawer } from '../HistoryDrawer';
import { PartyDetails, useInvalidateReference } from '../PartyDetails';
import { EditEntityModal } from '../ReferenceEditModals';
import { Books } from './Books';
import { useOpenRecord } from './hooks';
import { RegistryAccounts } from './RegistryAccounts';
import { type LegalEntity, ActiveBadge, activeColumn, EditButton, HistoryButton } from './shared';

export function EntityDrawer({ entityId, onClose }: { entityId: string; onClose: () => void }) {
  const { t } = useTranslation();
  const [history, setHistory] = useState(false);
  const [editing, setEditing] = useState(false);
  const detail = $api.useQuery('get', '/api/v1/reference/legal-entities/{id}', { params: { path: { id: entityId } } });
  const entity = detail.data;
  return (
    <Drawer
      opened
      onClose={onClose}
      position="right"
      size="xl"
      title={entity ? `${entity.code} · ${entity.name}` : ''}
      data-testid="entity-drawer"
    >
      <QueryState isPending={detail.isPending} error={detail.error}>
        {entity && (
          <Stack>
            <Group gap="xs" justify="space-between">
              <ActiveBadge active={entity.active} />
              <Group gap={4}>
                <Can op="referenceData_updateLegalEntity">
                  <EditButton onClick={() => setEditing(true)} />
                </Can>
                <HistoryButton onClick={() => setHistory(true)} />
              </Group>
            </Group>
            <Paper withBorder p="md" data-testid="entity-facts">
              <FactGrid
                cols={3}
                facts={[
                  { label: t('ets.country'), value: entity.country },
                  { label: t('admin.baseCurrency'), value: entity.baseCurrency },
                  { label: t('admin.timeZone'), value: entity.timeZone },
                  { label: t('admin.lei'), value: entity.lei ?? '–' },
                  { label: t('admin.emirClassification'), value: entity.emirClassification ?? '–' },
                  { label: t('admin.recordVersion'), value: entity.version },
                ]}
              />
            </Paper>
            <Books entityId={entity.id} />
            <RegistryAccounts holderType="LEGAL_ENTITY" holderId={entity.id} />
            <PartyDetails partyType="LEGAL_ENTITY" partyId={entity.id} />
          </Stack>
        )}
      </QueryState>
      {entity && history && (
        <HistoryDrawer table="legal_entity" id={entity.id} title={entity.code} onClose={() => setHistory(false)} />
      )}
      {entity && editing && <EditEntityModal entity={entity} onClose={() => setEditing(false)} />}
    </Drawer>
  );
}

export function CreateEntityModal({ onClose }: { onClose: () => void }) {
  const { t } = useTranslation();
  const invalidate = useInvalidateReference();
  const [values, setValues] = useState({
    code: '',
    name: '',
    country: '',
    baseCurrency: 'EUR',
    timeZone: 'Europe/Helsinki',
    lei: '',
    validFrom: dayjs().startOf('year').format('YYYY-MM-DD'),
  });
  const create = $api.useMutation('post', '/api/v1/reference/legal-entities', {
    meta: { notify: false },
    onSuccess: () => {
      notifySuccess(t('admin.saved'));
      void invalidate();
      onClose();
    },
  });
  const set = (patch: Partial<typeof values>) => setValues({ ...values, ...patch });
  return (
    <Modal opened onClose={onClose} title={t('admin.newEntity')}>
      <Stack>
        <ProblemAlert error={create.error} />
        <Group grow>
          <TextInput
            label={t('marketData.code')}
            value={values.code}
            onChange={(e) => set({ code: e.currentTarget.value.toUpperCase() })}
          />
          <TextInput
            label={t('ets.country')}
            value={values.country}
            maxLength={2}
            onChange={(e) => set({ country: e.currentTarget.value.toUpperCase() })}
          />
        </Group>
        <TextInput label={t('ets.name')} value={values.name} onChange={(e) => set({ name: e.currentTarget.value })} />
        <Group grow>
          <TextInput
            label={t('admin.baseCurrency')}
            value={values.baseCurrency}
            maxLength={3}
            onChange={(e) => set({ baseCurrency: e.currentTarget.value.toUpperCase() })}
          />
          <TextInput
            label={t('admin.timeZone')}
            value={values.timeZone}
            onChange={(e) => set({ timeZone: e.currentTarget.value })}
          />
        </Group>
        <Group grow>
          <TextInput
            label="LEI"
            value={values.lei}
            onChange={(e) => set({ lei: e.currentTarget.value.toUpperCase() })}
          />
          <TextInput
            type="date"
            label={t('risk.validFrom')}
            value={values.validFrom}
            onChange={(e) => set({ validFrom: e.currentTarget.value })}
          />
        </Group>
        <Button
          disabled={!values.code.trim() || !values.name.trim() || values.country.length !== 2}
          loading={create.isPending}
          onClick={() => create.mutate({ body: { ...values, lei: values.lei || undefined } })}
        >
          {t('ets.save')}
        </Button>
      </Stack>
    </Modal>
  );
}

export function EntitiesTab() {
  const { t } = useTranslation();
  const [open, setOpen] = useOpenRecord();
  const [creating, setCreating] = useState(false);
  const entities = $api.useQuery('get', '/api/v1/reference/legal-entities');
  const columns = useMemo<ColDef<LegalEntity>[]>(
    () => [
      { field: 'code', headerName: t('marketData.code'), width: 140 },
      { field: 'name', headerName: t('ets.name'), flex: 1, minWidth: 200 },
      { field: 'country', headerName: t('ets.country'), width: 100 },
      { field: 'baseCurrency', headerName: t('admin.baseCurrency'), width: 130 },
      { field: 'lei', headerName: t('admin.lei'), width: 210 },
      { field: 'emirClassification', headerName: t('admin.emir'), width: 120 },
      activeColumn<LegalEntity>(t('admin.active')),
      { field: 'version', headerName: t('trades.version'), width: 100, type: 'rightAligned' },
    ],
    [t],
  );
  return (
    <Stack>
      <Group justify="flex-end">
        <Can op="referenceData_createLegalEntity">
          <Button leftSection={<IconPlus size={16} />} onClick={() => setCreating(true)}>
            {t('admin.newEntity')}
          </Button>
        </Can>
      </Group>
      <QueryState isPending={false} error={entities.error}>
        {entities.isPending ? (
          <Paper withBorder>
            <TableSkeleton rows={4} />
          </Paper>
        ) : (
          <DataGrid<LegalEntity>
            rowData={entities.data ?? []}
            columnDefs={columns}
            rowId={(row) => row.id}
            fitRows={12}
            onRowClicked={(event) => setOpen(event.data?.id ?? null)}
          />
        )}
      </QueryState>
      {open && <EntityDrawer entityId={open} onClose={() => setOpen(null)} />}
      {creating && <CreateEntityModal onClose={() => setCreating(false)} />}
    </Stack>
  );
}
