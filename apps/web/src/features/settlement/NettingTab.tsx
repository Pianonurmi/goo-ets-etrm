import { Button, Group, Modal, Select, Stack, Text, TextInput } from '@mantine/core';
import { IconArrowsShuffle } from '@tabler/icons-react';
import type { ColDef, ICellRendererParams } from 'ag-grid-community';
import dayjs from 'dayjs';
import { useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useNavigate } from 'react-router';
import { $api } from '@/shared/api/client';
import { Can } from '@/shared/auth/Can';
import { notifySuccess } from '@/shared/components/notify';
import { ProblemAlert } from '@/shared/components/ProblemAlert';
import { QueryState } from '@/shared/components/QueryState';
import { formatMoney } from '@/shared/format/format';
import { DataGrid } from '@/shared/grid/DataGrid';
import { useCounterparties } from '@/features/trades/hooks';
import { useInvalidateSettlement } from './hooks';
import { NETTING_STATUS_COLORS, type Netting } from './model/payments';
import { StatusBadge } from './SettlementPage';

/** Nets the issued, unpaid invoices with a counterparty we have a netting agreement with. */
function PrepareNettingModal({ entityId, onClose }: { entityId: string; onClose: () => void }) {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const counterparties = useCounterparties();
  const invalidate = useInvalidateSettlement();
  const [counterpartyId, setCounterpartyId] = useState<string | null>(null);
  const [currency, setCurrency] = useState('EUR');
  const [valueDate, setValueDate] = useState(dayjs().add(30, 'day').format('YYYY-MM-DD'));
  const prepare = $api.useMutation('post', '/api/v1/settlement/netting', {
    meta: { notify: false },
    onSuccess: (statement) => {
      notifySuccess(t('settlement.nettingPrepared'));
      void invalidate();
      void navigate(`/settlement/netting/${statement.id}`);
    },
  });
  const agreed = counterparties.list.filter((counterparty) => counterparty.nettingAgreed);
  return (
    <Modal opened onClose={onClose} title={t('settlement.prepareNetting')}>
      <Stack>
        <ProblemAlert error={prepare.error} />
        <Text size="sm" c="dimmed">
          {t('settlement.prepareNettingHint')}
        </Text>
        <Select
          label={t('trades.columns.counterparty')}
          description={t('settlement.nettingAgreedOnly')}
          data={agreed.map((c) => ({ value: c.id, label: `${c.code} · ${c.name}` }))}
          value={counterpartyId}
          onChange={setCounterpartyId}
          searchable
          required
          data-testid="netting-counterparty"
        />
        <Group grow>
          <TextInput
            label={t('trades.form.currency')}
            value={currency}
            maxLength={3}
            onChange={(e) => setCurrency(e.currentTarget.value.toUpperCase())}
          />
          <TextInput
            type="date"
            label={t('settlement.valueDate')}
            description={t('settlement.nettingValueDateHint')}
            value={valueDate}
            onChange={(e) => setValueDate(e.currentTarget.value)}
            required
          />
        </Group>
        <Button
          disabled={!counterpartyId || currency.length !== 3 || !valueDate}
          loading={prepare.isPending}
          data-testid="netting-submit"
          onClick={() =>
            counterpartyId && prepare.mutate({ body: { legalEntityId: entityId, counterpartyId, currency, valueDate } })
          }
        >
          {t('settlement.prepare')}
        </Button>
      </Stack>
    </Modal>
  );
}

export function NettingTab({ entityId }: { entityId: string }) {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const counterparties = useCounterparties();
  const [preparing, setPreparing] = useState(false);
  const statements = $api.useQuery('get', '/api/v1/settlement/netting', {
    params: { query: { legalEntityId: entityId } },
  });
  const columns = useMemo<ColDef<Netting>[]>(
    () => [
      {
        colId: 'counterparty',
        headerName: t('trades.columns.counterparty'),
        flex: 1,
        minWidth: 220,
        valueGetter: (p) => (p.data ? (counterparties.byId.get(p.data.counterpartyId)?.name ?? '') : ''),
      },
      {
        field: 'status',
        headerName: t('allocation.status'),
        width: 140,
        cellRenderer: (p: ICellRendererParams<Netting>) =>
          p.data && <StatusBadge label={p.data.status} color={NETTING_STATUS_COLORS[p.data.status]} />,
      },
      { field: 'valueDate', headerName: t('settlement.valueDate'), width: 120 },
      {
        field: 'receivableTotal',
        headerName: t('settlement.receivable'),
        width: 150,
        type: 'rightAligned',
        valueFormatter: (p) => (p.data ? formatMoney(p.data.receivableTotal, p.data.currency) : ''),
      },
      {
        field: 'payableTotal',
        headerName: t('settlement.payable'),
        width: 150,
        type: 'rightAligned',
        valueFormatter: (p) => (p.data ? formatMoney(p.data.payableTotal, p.data.currency) : ''),
      },
      {
        field: 'netAmount',
        headerName: t('settlement.net'),
        width: 150,
        type: 'rightAligned',
        valueFormatter: (p) => (p.data ? formatMoney(p.data.netAmount, p.data.currency) : ''),
      },
      { field: 'createdBy', headerName: t('settlement.preparedBy'), width: 130 },
      { field: 'agreedBy', headerName: t('settlement.agreementRecordedBy'), width: 160 },
    ],
    [t, counterparties.byId],
  );
  return (
    <Stack>
      <Group justify="flex-end">
        <Can op="payment_prepareNetting">
          <Button
            leftSection={<IconArrowsShuffle size={16} />}
            onClick={() => setPreparing(true)}
            data-testid="prepare-netting"
          >
            {t('settlement.prepareNetting')}
          </Button>
        </Can>
      </Group>
      <QueryState isPending={statements.isPending} error={statements.error}>
        <DataGrid<Netting>
          rowData={statements.data ?? []}
          columnDefs={columns}
          rowId={(row) => row.id}
          height={420}
          onRowClicked={(event) => event.data && void navigate(`/settlement/netting/${event.data.id}`)}
          rowStyle={{ cursor: 'pointer' }}
        />
      </QueryState>
      {preparing && <PrepareNettingModal entityId={entityId} onClose={() => setPreparing(false)} />}
    </Stack>
  );
}
