import { Button, Drawer, Group, Modal, NumberInput, Paper, Select, Stack, Text, TextInput } from '@mantine/core';
import { IconPlus } from '@tabler/icons-react';
import type { ColDef } from 'ag-grid-community';
import { useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { $api } from '@/shared/api/client';
import { Can } from '@/shared/auth/Can';
import { FactGrid } from '@/shared/components/DetailLayout';
import { notifySuccess } from '@/shared/components/notify';
import { ProblemAlert } from '@/shared/components/ProblemAlert';
import { QueryState } from '@/shared/components/QueryState';
import { TableSkeleton } from '@/shared/components/Skeletons';
import { statusLabel } from '@/shared/components/StatusBadge';
import { DataGrid } from '@/shared/grid/DataGrid';
import { HistoryDrawer } from '../HistoryDrawer';
import { PartyDetails, useInvalidateReference } from '../PartyDetails';
import { EditCounterpartyModal } from '../ReferenceEditModals';
import { useOpenRecord } from './hooks';
import { RegistryAccounts } from './RegistryAccounts';
import {
  type Counterparty,
  type CounterpartyType,
  ActiveBadge,
  activeColumn,
  COUNTERPARTY_TYPES,
  EditButton,
  HistoryButton,
} from './shared';

export function CreateCounterpartyModal({ onClose }: { onClose: () => void }) {
  const { t } = useTranslation();
  const invalidate = useInvalidateReference();
  const [values, setValues] = useState({
    code: '',
    name: '',
    country: '',
    counterpartyType: 'EXTERNAL' as CounterpartyType,
    lei: '',
    settlementCurrency: 'EUR',
    paymentTermsDays: 14,
  });
  const create = $api.useMutation('post', '/api/v1/reference/counterparties', {
    meta: { notify: false },
    onSuccess: () => {
      notifySuccess(t('admin.saved'));
      void invalidate();
      onClose();
    },
  });
  const set = (patch: Partial<typeof values>) => setValues({ ...values, ...patch });
  return (
    <Modal opened onClose={onClose} title={t('admin.newCounterparty')}>
      <Stack>
        <ProblemAlert error={create.error} />
        <Group grow>
          <TextInput
            label={t('marketData.code')}
            value={values.code}
            onChange={(e) => set({ code: e.currentTarget.value.toUpperCase() })}
          />
          <Select
            label={t('marketData.kind')}
            data={COUNTERPARTY_TYPES}
            value={values.counterpartyType}
            allowDeselect={false}
            onChange={(value) => value && set({ counterpartyType: value })}
          />
        </Group>
        <TextInput label={t('ets.name')} value={values.name} onChange={(e) => set({ name: e.currentTarget.value })} />
        <Group grow>
          <TextInput
            label={t('ets.country')}
            value={values.country}
            maxLength={2}
            onChange={(e) => set({ country: e.currentTarget.value.toUpperCase() })}
          />
          <TextInput
            label="LEI"
            value={values.lei}
            onChange={(e) => set({ lei: e.currentTarget.value.toUpperCase() })}
          />
        </Group>
        <Group grow>
          <TextInput
            label={t('admin.settlementCurrency')}
            value={values.settlementCurrency}
            maxLength={3}
            onChange={(e) => set({ settlementCurrency: e.currentTarget.value.toUpperCase() })}
          />
          <NumberInput
            label={t('admin.paymentTerms')}
            value={values.paymentTermsDays}
            onChange={(v) => set({ paymentTermsDays: Number(v) })}
            min={0}
            allowDecimal={false}
          />
        </Group>
        <Button
          disabled={!values.code.trim() || !values.name.trim() || values.country.length !== 2}
          loading={create.isPending}
          onClick={() =>
            create.mutate({
              body: { ...values, lei: values.lei || undefined },
            })
          }
        >
          {t('ets.save')}
        </Button>
      </Stack>
    </Modal>
  );
}

export function CounterpartyDrawer({ counterpartyId, onClose }: { counterpartyId: string; onClose: () => void }) {
  const { t } = useTranslation();
  const [history, setHistory] = useState(false);
  const [editing, setEditing] = useState(false);
  const detail = $api.useQuery('get', '/api/v1/reference/counterparties/{id}', {
    params: { path: { id: counterpartyId } },
  });
  const counterparties = $api.useQuery('get', '/api/v1/reference/counterparties');
  const counterparty = detail.data;
  const parent = (counterparties.data ?? []).find((c) => c.id === counterparty?.parentCounterpartyId);
  return (
    <Drawer
      opened
      onClose={onClose}
      position="right"
      size="xl"
      title={counterparty ? `${counterparty.code} · ${counterparty.name}` : ''}
      data-testid="counterparty-drawer"
    >
      <QueryState isPending={detail.isPending} error={detail.error}>
        {counterparty && (
          <Stack>
            <Group gap="xs" justify="space-between">
              <Group gap="xs">
                <ActiveBadge active={counterparty.active} />
                <Text size="sm" c="dimmed">
                  {statusLabel(counterparty.counterpartyType)} · {counterparty.country} ·{' '}
                  {counterparty.settlementCurrency}
                </Text>
              </Group>
              <Group gap={4}>
                <Can op="referenceData_updateCounterparty">
                  <EditButton onClick={() => setEditing(true)} />
                </Can>
                <HistoryButton onClick={() => setHistory(true)} />
              </Group>
            </Group>
            <Paper withBorder p="md">
              <FactGrid
                cols={3}
                facts={[
                  {
                    label: t('admin.paymentTerms'),
                    value: t('admin.paymentTermsDays', { days: counterparty.paymentTermsDays }),
                    testId: 'counterparty-payment-terms',
                  },
                  {
                    label: t('admin.invoiceEmail'),
                    value: counterparty.invoiceEmail ?? '–',
                    testId: 'counterparty-invoice-email',
                  },
                  { label: 'LEI', value: counterparty.lei ?? '–' },
                  { label: t('admin.vatNumber'), value: counterparty.vatNumber ?? '–' },
                  { label: t('admin.emirClassification'), value: counterparty.emirClassification ?? '–' },
                  { label: t('admin.parentCounterparty'), value: parent ? parent.code : '–' },
                  {
                    label: t('admin.nettingAgreed'),
                    value: counterparty.nettingAgreed ? t('common.yes') : t('common.no'),
                  },
                  {
                    label: t('admin.selfBilling'),
                    value: counterparty.selfBilling ? t('common.yes') : t('common.no'),
                  },
                  { label: t('admin.recordVersion'), value: counterparty.version },
                  {
                    label: t('admin.masterAgreements'),
                    value:
                      counterparty.masterAgreements.map((a) => `${statusLabel(a.type)} ${a.reference}`).join(', ') ||
                      '–',
                  },
                ]}
              />
            </Paper>
            <RegistryAccounts holderType="COUNTERPARTY" holderId={counterparty.id} />
            <PartyDetails partyType="COUNTERPARTY" partyId={counterparty.id} />
          </Stack>
        )}
      </QueryState>
      {counterparty && history && (
        <HistoryDrawer
          table="counterparty"
          id={counterparty.id}
          title={counterparty.code}
          onClose={() => setHistory(false)}
        />
      )}
      {counterparty && editing && (
        <EditCounterpartyModal counterparty={counterparty} onClose={() => setEditing(false)} />
      )}
    </Drawer>
  );
}

export function CounterpartiesTab() {
  const { t } = useTranslation();
  const [open, setOpen] = useOpenRecord();
  const [creating, setCreating] = useState(false);
  const counterparties = $api.useQuery('get', '/api/v1/reference/counterparties');
  const columns = useMemo<ColDef<Counterparty>[]>(
    () => [
      { field: 'code', headerName: t('marketData.code'), width: 160 },
      { field: 'name', headerName: t('ets.name'), flex: 1, minWidth: 220 },
      {
        field: 'counterpartyType',
        headerName: t('marketData.kind'),
        width: 150,
        valueFormatter: (p) => statusLabel(p.value as string),
      },
      { field: 'country', headerName: t('ets.country'), width: 100 },
      { field: 'lei', headerName: t('admin.lei'), width: 210 },
      { field: 'settlementCurrency', headerName: t('trades.form.currency'), width: 110 },
      activeColumn<Counterparty>(t('admin.active')),
    ],
    [t],
  );
  return (
    <Stack>
      <Group justify="flex-end">
        <Can op="referenceData_createCounterparty">
          <Button leftSection={<IconPlus size={16} />} onClick={() => setCreating(true)}>
            {t('admin.newCounterparty')}
          </Button>
        </Can>
      </Group>
      <QueryState isPending={false} error={counterparties.error}>
        {counterparties.isPending ? (
          <Paper withBorder>
            <TableSkeleton rows={4} />
          </Paper>
        ) : (
          <DataGrid<Counterparty>
            rowData={counterparties.data ?? []}
            columnDefs={columns}
            rowId={(row) => row.id}
            fitRows={12}
            onRowClicked={(event) => setOpen(event.data?.id ?? null)}
          />
        )}
      </QueryState>
      {open && <CounterpartyDrawer counterpartyId={open} onClose={() => setOpen(null)} />}
      {creating && <CreateCounterpartyModal onClose={() => setCreating(false)} />}
    </Stack>
  );
}
