import { Button, Group, Modal, Paper, Select, Stack, Text, TextInput } from '@mantine/core';
import { IconPlus } from '@tabler/icons-react';
import type { ColDef, ICellRendererParams } from 'ag-grid-community';
import { useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { $api } from '@/shared/api/client';
import { asProblemError } from '@/shared/api/problem';
import { Can } from '@/shared/auth/Can';
import { EmptyState } from '@/shared/components/EmptyState';
import { Money } from '@/shared/components/Figures';
import { notifySuccess } from '@/shared/components/notify';
import { ProblemAlert } from '@/shared/components/ProblemAlert';
import { TableSkeleton } from '@/shared/components/Skeletons';
import { StatusBadge } from '@/shared/components/StatusBadge';
import { applyProblemToForm } from '@/shared/forms/applyProblemToForm';
import { formatDate } from '@/shared/format/format';
import { DataGrid } from '@/shared/grid/DataGrid';
import type { LegalEntity } from '@/shared/context/EntityContext';
import { useClearingHouses, useInvalidateClearing } from './hooks';
import { postedFor, type Account, type Posted } from './model/clearing';

/** Field errors of a 422 answer, placed on the inputs (the alert above the form repeats them). */
function useFieldErrors() {
  const [errors, setErrors] = useState<Record<string, string>>({});
  const sink = {
    setFieldError: (path: string, message: string) => setErrors((current) => ({ ...current, [path]: message })),
  };
  return {
    errors,
    reset: () => setErrors({}),
    onError: (error: unknown) => {
      const problem = asProblemError(error);
      if (problem.status === 422) applyProblemToForm(sink, problem);
    },
  };
}

/** A clearing account: one per legal entity and clearing house, through a clearing member, in one currency. */
function NewAccountModal({
  entity,
  onClose,
  onCreated,
}: {
  entity: LegalEntity;
  onClose: () => void;
  onCreated: (account: Account) => void;
}) {
  const { t } = useTranslation();
  const invalidate = useInvalidateClearing();
  const { houses } = useClearingHouses();
  const fields = useFieldErrors();
  const [code, setCode] = useState('');
  const [clearingHouseId, setClearingHouseId] = useState<string | null>(null);
  const [clearingMember, setClearingMember] = useState('');
  const [currency, setCurrency] = useState(entity.baseCurrency);
  const create = $api.useMutation('post', '/api/v1/clearing/accounts', {
    meta: { notify: false },
    onSuccess: (account) => {
      notifySuccess(t('clearing.accountCreated', { code: account.code }));
      void invalidate();
      onCreated(account);
      onClose();
    },
    onError: fields.onError,
  });
  const ready = code.trim() && clearingHouseId && clearingMember.trim() && currency.trim().length === 3;
  return (
    <Modal opened onClose={onClose} title={t('clearing.newAccount')} data-testid="clearing-account-modal">
      <Stack>
        <ProblemAlert error={create.error} />
        <Text size="sm" c="dimmed">
          {t('clearing.newAccountHint', { entity: entity.code })}
        </Text>
        <TextInput
          label={t('clearing.fields.code')}
          description={t('clearing.fields.codeHint')}
          value={code}
          error={fields.errors.code}
          onChange={(e) => setCode(e.currentTarget.value.toUpperCase())}
          required
          data-testid="clearing-account-code"
        />
        <Select
          label={t('clearing.fields.clearingHouse')}
          placeholder={houses.length === 0 ? t('clearing.noClearingHouses') : undefined}
          data={houses.map((house) => ({ value: house.id, label: house.name }))}
          value={clearingHouseId}
          error={fields.errors.clearingHouseId}
          onChange={setClearingHouseId}
          searchable
          required
          data-testid="clearing-account-house"
        />
        <TextInput
          label={t('clearing.fields.clearingMember')}
          description={t('clearing.fields.clearingMemberHint')}
          value={clearingMember}
          error={fields.errors.clearingMember}
          onChange={(e) => setClearingMember(e.currentTarget.value)}
          required
          data-testid="clearing-account-member"
        />
        <TextInput
          label={t('clearing.fields.currency')}
          description={t('clearing.fields.currencyHint')}
          value={currency}
          maxLength={3}
          error={fields.errors.currency}
          onChange={(e) => setCurrency(e.currentTarget.value.toUpperCase())}
          w={160}
          data-testid="clearing-account-currency"
        />
        <Button
          disabled={!ready}
          loading={create.isPending}
          onClick={() => {
            fields.reset();
            create.mutate({
              body: {
                code: code.trim(),
                legalEntityId: entity.id,
                clearingHouseId: clearingHouseId ?? '',
                clearingMember: clearingMember.trim(),
                currency: currency.trim(),
              },
            });
          }}
          data-testid="clearing-account-submit"
        >
          {t('clearing.createAccount')}
        </Button>
      </Stack>
    </Modal>
  );
}

/** The entity's clearing accounts with the margin held and the cash at the member; a row selects the account. */
export function AccountsTab({
  entity,
  accounts,
  loading,
  posted,
  selectedId,
  onSelect,
}: {
  entity: LegalEntity;
  accounts: readonly Account[];
  loading: boolean;
  posted: readonly Posted[];
  selectedId: string | null;
  onSelect: (id: string) => void;
}) {
  const { t } = useTranslation();
  const { byId } = useClearingHouses();
  const [adding, setAdding] = useState(false);
  const columns = useMemo<ColDef<Account>[]>(
    () => [
      { field: 'code', headerName: t('clearing.fields.code'), width: 180, cellStyle: { fontWeight: 600 } },
      {
        colId: 'house',
        headerName: t('clearing.fields.clearingHouse'),
        flex: 1,
        minWidth: 200,
        valueGetter: (p) => (p.data ? (byId.get(p.data.clearingHouseId)?.name ?? '') : ''),
      },
      { field: 'clearingMember', headerName: t('clearing.fields.clearingMember'), flex: 1, minWidth: 180 },
      { field: 'currency', headerName: t('clearing.fields.currency'), width: 110 },
      {
        field: 'initialMargin',
        headerName: t('clearing.initialMargin'),
        width: 170,
        type: 'rightAligned',
        cellRenderer: (p: ICellRendererParams<Account>) =>
          p.data && <Money value={p.data.initialMargin} currency={p.data.currency} />,
      },
      {
        colId: 'cash',
        headerName: t('clearing.cashAtMember'),
        width: 170,
        type: 'rightAligned',
        valueGetter: (p) => postedFor(posted, p.data?.id)?.cashBalance ?? null,
        cellRenderer: (p: ICellRendererParams<Account>) => {
          const cash = postedFor(posted, p.data?.id)?.cashBalance;
          return p.data && cash != null ? <Money value={cash} currency={p.data.currency} /> : '—';
        },
      },
      {
        colId: 'calls',
        headerName: t('clearing.openCallsAmount'),
        width: 170,
        type: 'rightAligned',
        valueGetter: (p) => postedFor(posted, p.data?.id)?.openCalls ?? null,
        cellRenderer: (p: ICellRendererParams<Account>) => {
          const open = postedFor(posted, p.data?.id)?.openCalls;
          return p.data && open != null ? <Money value={open} currency={p.data.currency} /> : '—';
        },
      },
      {
        field: 'active',
        headerName: t('allocation.status'),
        width: 120,
        cellRenderer: (p: ICellRendererParams<Account>) =>
          p.data && <StatusBadge status={p.data.active ? 'ACTIVE' : 'INACTIVE'} />,
      },
      {
        field: 'createdAt',
        headerName: t('clearing.fields.created'),
        width: 130,
        valueFormatter: (p) => formatDate(p.value as string),
      },
    ],
    [t, byId, posted],
  );
  return (
    <Stack gap="sm">
      <Group justify="space-between" align="flex-end">
        <Text size="sm" c="dimmed" maw={640}>
          {t('clearing.accountsHint')}
        </Text>
        <Can op="clearing_createAccount">
          <Button
            leftSection={<IconPlus size={16} />}
            onClick={() => setAdding(true)}
            data-testid="clearing-new-account"
          >
            {t('clearing.newAccount')}
          </Button>
        </Can>
      </Group>
      {loading ? (
        <Paper withBorder radius="lg">
          <TableSkeleton rows={3} />
        </Paper>
      ) : accounts.length === 0 ? (
        <Paper withBorder radius="lg">
          <EmptyState
            title={t('clearing.noAccounts')}
            description={t('clearing.noAccountsHint', { entity: entity.code })}
            testId="clearing-accounts-empty"
          />
        </Paper>
      ) : (
        <div data-testid="clearing-accounts-grid">
          <DataGrid<Account>
            rowData={[...accounts]}
            columnDefs={columns}
            rowId={(row) => row.id}
            fitRows={8}
            getRowStyle={(p) => ({ cursor: 'pointer', fontWeight: p.data?.id === selectedId ? 600 : 400 })}
            onRowClicked={(event) => event.data && onSelect(event.data.id)}
          />
        </div>
      )}
      {adding && <NewAccountModal entity={entity} onClose={() => setAdding(false)} onCreated={(a) => onSelect(a.id)} />}
    </Stack>
  );
}
