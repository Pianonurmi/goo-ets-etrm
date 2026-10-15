import { useUrlState, useUrlTab } from '@/shared/useUrlState';
import {
  Alert,
  Badge,
  Button,
  Drawer,
  Group,
  Modal,
  NumberInput,
  Paper,
  Select,
  Stack,
  Switch,
  Table,
  Tabs,
  Text,
  TextInput,
  Textarea,
} from '@mantine/core';
import { IconHistory, IconPencil, IconPlus, IconShieldCheck } from '@tabler/icons-react';
import type { ColDef, ICellRendererParams } from 'ag-grid-community';
import dayjs from 'dayjs';
import { useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { $api } from '@/shared/api/client';
import type { Body, Schema } from '@/shared/api/types';
import { Can, useCan } from '@/shared/auth/Can';
import { FactGrid } from '@/shared/components/DetailLayout';
import { notifySuccess } from '@/shared/components/notify';
import { PageHeader } from '@/shared/components/PageHeader';
import { ProblemAlert } from '@/shared/components/ProblemAlert';
import { QueryState } from '@/shared/components/QueryState';
import { TableSkeleton } from '@/shared/components/Skeletons';
import { StatusBadge, statusLabel } from '@/shared/components/StatusBadge';
import { formatDate, formatDecimal } from '@/shared/format/format';
import { DataGrid } from '@/shared/grid/DataGrid';
import { DeviceForecast, LoadFactorsSection } from './DeviceForecast';
import { HistoryDrawer } from './HistoryDrawer';
import { Section } from './Section';
import { defaultBookAssignable } from './model/referenceEdits';
import { PartyDetails, useInvalidateReference } from './PartyDetails';
import { EditCounterpartyModal, EditDeviceModal, EditEntityModal, NewDeviceModal } from './ReferenceEditModals';

type LegalEntity = Schema<'LegalEntityView'>;
type Counterparty = Schema<'CounterpartyView'>;
type Registry = Schema<'RegistryView'>;
type Device = Schema<'ProductionDeviceView'>;
type AccountType = Body<'CreateRegistryAccount'>['accountType'];
type BookType = Body<'CreateBook'>['bookType'];
type CounterpartyType = NonNullable<Body<'CreateCounterparty'>['counterpartyType']>;

const ACCOUNT_TYPES: AccountType[] = [
  'HOLDING',
  'PRODUCTION',
  'CANCELLATION_ONLY',
  'OPERATOR_HOLDING',
  'AIRCRAFT_OPERATOR_HOLDING',
  'TRADING',
  'PERSON_HOLDING',
];
const BOOK_TYPES: BookType[] = ['TRADING', 'OWN_USE', 'HEDGING', 'COMPLIANCE'];
const COUNTERPARTY_TYPES: CounterpartyType[] = ['EXTERNAL', 'INTERCOMPANY', 'BROKER', 'EXCHANGE', 'CLEARING_HOUSE'];

function ActiveBadge({ active }: { active: boolean }) {
  const { t } = useTranslation();
  return (
    <StatusBadge status={active ? 'ACTIVE' : 'INACTIVE'} label={active ? t('admin.active') : t('admin.inactive')} />
  );
}

/** The active column of the lists, as a badge. */
function activeColumn<Row extends { active: boolean }>(headerName: string): ColDef<Row> {
  return {
    field: 'active' as ColDef<Row>['field'],
    headerName,
    width: 110,
    cellRenderer: (p: ICellRendererParams<Row>) => (p.data ? <ActiveBadge active={p.data.active} /> : null),
  };
}

function useRegistries() {
  return $api.useQuery('get', '/api/v1/reference/registries', {}, { staleTime: 5 * 60_000 });
}

/** The record whose drawer is open, kept in the URL (`?open=<id>`) so it can be linked and survives a reload. */
function useOpenRecord(): [string | null, (id: string | null) => void] {
  const { params, set: patchUrl } = useUrlState();
  const set = (id: string | null) => patchUrl({ open: id });
  return [params.get('open'), set];
}

const NO_BOOK = 'NONE';

/** The book that inventory on an own registry account belongs to (position limits); middle or back office set it. */
function DefaultBook({ account, entityId }: { account: Schema<'RegistryAccountView'>; entityId: string }) {
  const { t } = useTranslation();
  const invalidate = useInvalidateReference();
  const canAssign = useCan('referenceData_assignDefaultBook');
  const books = $api.useQuery('get', '/api/v1/reference/legal-entities/{id}/books', {
    params: { path: { id: entityId } },
  });
  const assign = $api.useMutation('put', '/api/v1/reference/registry-accounts/{id}/default-book', {
    onSuccess: () => {
      notifySuccess(t('admin.defaultBookSaved', { account: `${account.registryCode}:${account.accountNumber}` }));
      void invalidate();
    },
  });
  const current = (books.data ?? []).find((book) => book.id === account.defaultBookId);
  if (!canAssign) {
    return (
      <Text size="sm" c="dimmed" data-testid={`default-book-${account.id}`}>
        {t('admin.defaultBook')}: {current?.code ?? t('admin.noDefaultBook')}
      </Text>
    );
  }
  return (
    <Select
      size="xs"
      w={220}
      aria-label={t('admin.defaultBook')}
      placeholder={t('admin.defaultBook')}
      data={[
        { value: NO_BOOK, label: t('admin.noDefaultBook') },
        ...(books.data ?? [])
          .filter((book) => book.active || book.id === account.defaultBookId)
          .map((book) => ({ value: book.id, label: `${book.code} · ${book.name}` })),
      ]}
      value={account.defaultBookId ?? NO_BOOK}
      allowDeselect={false}
      disabled={assign.isPending}
      onChange={(value) =>
        value &&
        assign.mutate({
          params: { path: { id: account.id } },
          body: { bookId: value === NO_BOOK ? null : value },
        })
      }
      data-testid={`default-book-${account.id}`}
    />
  );
}

function RegistryAccounts({ holderType, holderId }: { holderType: 'LEGAL_ENTITY' | 'COUNTERPARTY'; holderId: string }) {
  const { t } = useTranslation();
  const invalidate = useInvalidateReference();
  const registries = useRegistries();
  const [adding, setAdding] = useState(false);
  const [registryCode, setRegistryCode] = useState<string | null>(null);
  const [accountNumber, setAccountNumber] = useState('');
  const [name, setName] = useState('');
  const [accountType, setAccountType] = useState<AccountType>('HOLDING');
  const [reason, setReason] = useState('');
  const accounts = $api.useQuery('get', '/api/v1/reference/registry-accounts', {
    params: { query: { holderType, holderId } },
  });
  const done = () => {
    notifySuccess(holderType === 'COUNTERPARTY' ? t('admin.changeRequested') : t('admin.saved'));
    setAdding(false);
    void invalidate();
  };
  const create = $api.useMutation('post', '/api/v1/reference/registry-accounts', {
    meta: { notify: false },
    onSuccess: done,
  });
  const request = $api.useMutation('post', '/api/v1/reference/counterparties/{id}/registry-accounts/change-requests', {
    meta: { notify: false },
    onSuccess: done,
  });
  const submit = () =>
    holderType === 'LEGAL_ENTITY'
      ? create.mutate({
          body: { holderType, holderId, registryCode: registryCode ?? '', accountNumber, name, accountType },
        })
      : request.mutate({
          params: { path: { id: holderId } },
          body: { action: 'CREATE', registryCode, accountNumber, name, accountType, reason },
        });
  return (
    <Section
      title={t('admin.registryAccounts')}
      action={
        <Can
          op={
            holderType === 'LEGAL_ENTITY'
              ? 'referenceData_createRegistryAccount'
              : 'changeRequest_requestCounterpartyAccountChange'
          }
        >
          <Button
            size="compact-sm"
            variant="subtle"
            leftSection={<IconPlus size={14} />}
            onClick={() => setAdding(true)}
          >
            {t('admin.addAccount')}
          </Button>
        </Can>
      }
    >
      <Stack gap="xs">
        {accounts.data?.length === 0 && (
          <Text size="sm" c="dimmed">
            {t('admin.noAccounts')}
          </Text>
        )}
        {(accounts.data ?? []).map((account) => (
          <Group
            key={account.id}
            gap="sm"
            justify="space-between"
            wrap="nowrap"
            className="etrm-task-row"
            data-testid={`registry-account-${account.id}`}
          >
            <Stack gap={2} miw={0}>
              <Group gap={6} wrap="nowrap">
                <Text size="sm" fw={500} truncate>
                  {account.name}
                </Text>
                {account.trusted && (
                  <Badge size="xs" variant="light" tt="none">
                    {t('admin.trusted')}
                  </Badge>
                )}
                {!account.active && <ActiveBadge active={false} />}
              </Group>
              <Text size="xs" c="dimmed" truncate>
                <Text span ff="monospace" size="xs">
                  {account.registryCode}:{account.accountNumber}
                </Text>{' '}
                · {statusLabel(account.accountType)}
              </Text>
            </Stack>
            {defaultBookAssignable(account) && <DefaultBook account={account} entityId={holderId} />}
          </Group>
        ))}
      </Stack>
      <Modal opened={adding} onClose={() => setAdding(false)} title={t('admin.addAccount')}>
        <Stack>
          <ProblemAlert error={create.error ?? request.error} />
          {holderType === 'COUNTERPARTY' && (
            <Text size="sm" c="dimmed">
              {t('risk.fourEyesHint')}
            </Text>
          )}
          <Select
            label={t('admin.registry')}
            data={(registries.data ?? []).map((r) => ({ value: r.code, label: `${r.code} · ${r.name}` }))}
            value={registryCode}
            onChange={setRegistryCode}
          />
          <Group grow>
            <TextInput
              label={t('admin.accountNumber')}
              value={accountNumber}
              onChange={(e) => setAccountNumber(e.currentTarget.value)}
            />
            <Select
              label={t('admin.accountType')}
              data={ACCOUNT_TYPES}
              value={accountType}
              allowDeselect={false}
              onChange={(value) => value && setAccountType(value)}
            />
          </Group>
          <TextInput label={t('ets.name')} value={name} onChange={(e) => setName(e.currentTarget.value)} />
          {holderType === 'COUNTERPARTY' && (
            <Textarea
              label={t('risk.reason')}
              value={reason}
              onChange={(e) => setReason(e.currentTarget.value)}
              autosize
            />
          )}
          <Button
            disabled={
              !registryCode ||
              !accountNumber.trim() ||
              !name.trim() ||
              (holderType === 'COUNTERPARTY' && !reason.trim())
            }
            loading={create.isPending || request.isPending}
            onClick={submit}
          >
            {holderType === 'COUNTERPARTY' ? t('risk.sendForApproval') : t('ets.save')}
          </Button>
        </Stack>
      </Modal>
    </Section>
  );
}

function Books({ entityId }: { entityId: string }) {
  const { t } = useTranslation();
  const invalidate = useInvalidateReference();
  const [adding, setAdding] = useState(false);
  const [code, setCode] = useState('');
  const [name, setName] = useState('');
  const [bookType, setBookType] = useState<BookType>('TRADING');
  const books = $api.useQuery('get', '/api/v1/reference/legal-entities/{id}/books', {
    params: { path: { id: entityId } },
  });
  const create = $api.useMutation('post', '/api/v1/reference/books', {
    meta: { notify: false },
    onSuccess: () => {
      notifySuccess(t('admin.saved'));
      setAdding(false);
      void invalidate();
    },
  });
  return (
    <Section
      title={t('admin.books')}
      action={
        <Can op="referenceData_createBook">
          <Button
            size="compact-sm"
            variant="subtle"
            leftSection={<IconPlus size={14} />}
            onClick={() => setAdding(true)}
          >
            {t('admin.addBook')}
          </Button>
        </Can>
      }
    >
      <Stack gap={6}>
        {books.data?.length === 0 && (
          <Text size="sm" c="dimmed">
            {t('admin.noBooks')}
          </Text>
        )}
        {(books.data ?? []).map((book) => (
          <Group key={book.id} gap="xs" wrap="nowrap" justify="space-between">
            <Text size="sm" truncate>
              <Text span ff="monospace" size="sm">
                {book.code}
              </Text>{' '}
              · {book.name}
            </Text>
            <Badge size="sm" variant="light" color="gray" tt="none" style={{ flexShrink: 0 }}>
              {statusLabel(book.bookType)}
            </Badge>
          </Group>
        ))}
      </Stack>
      <Modal opened={adding} onClose={() => setAdding(false)} title={t('admin.addBook')}>
        <Stack>
          <ProblemAlert error={create.error} />
          <Group grow>
            <TextInput
              label={t('marketData.code')}
              value={code}
              onChange={(e) => setCode(e.currentTarget.value.toUpperCase())}
            />
            <Select
              label={t('monthEnd.bookType')}
              data={BOOK_TYPES}
              value={bookType}
              allowDeselect={false}
              onChange={(value) => value && setBookType(value)}
            />
          </Group>
          <TextInput label={t('ets.name')} value={name} onChange={(e) => setName(e.currentTarget.value)} />
          <Button
            disabled={!code.trim() || !name.trim()}
            loading={create.isPending}
            onClick={() => create.mutate({ body: { legalEntityId: entityId, code, name, bookType } })}
          >
            {t('ets.save')}
          </Button>
        </Stack>
      </Modal>
    </Section>
  );
}

function EditButton({ onClick }: { onClick: () => void }) {
  const { t } = useTranslation();
  return (
    <Button
      size="compact-sm"
      variant="subtle"
      leftSection={<IconPencil size={14} />}
      onClick={onClick}
      data-testid="record-edit"
    >
      {t('admin.edit')}
    </Button>
  );
}

function HistoryButton({ onClick }: { onClick: () => void }) {
  const { t } = useTranslation();
  return (
    <Button
      size="compact-sm"
      variant="subtle"
      leftSection={<IconHistory size={14} />}
      onClick={onClick}
      data-testid="record-history"
    >
      {t('admin.history')}
    </Button>
  );
}

function EntityDrawer({ entityId, onClose }: { entityId: string; onClose: () => void }) {
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

function CreateEntityModal({ onClose }: { onClose: () => void }) {
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

function EntitiesTab() {
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

function CreateCounterpartyModal({ onClose }: { onClose: () => void }) {
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

function CounterpartyDrawer({ counterpartyId, onClose }: { counterpartyId: string; onClose: () => void }) {
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

function CounterpartiesTab() {
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

function RegistryChangeModal({ registry, onClose }: { registry: Registry; onClose: () => void }) {
  const { t } = useTranslation();
  const invalidate = useInvalidateReference();
  const [integration, setIntegration] = useState<Registry['integration']>(registry.integration);
  const [active, setActive] = useState(registry.active);
  const [reason, setReason] = useState('');
  const request = $api.useMutation('post', '/api/v1/reference/registries/{code}/change-requests', {
    meta: { notify: false },
    onSuccess: () => {
      notifySuccess(t('admin.changeRequested'));
      void invalidate();
      onClose();
    },
  });
  return (
    <Modal opened onClose={onClose} title={t('admin.changeRegistry', { code: registry.code })}>
      <Stack>
        <ProblemAlert error={request.error} />
        <Text size="sm" c="dimmed">
          {t('risk.fourEyesHint')}
        </Text>
        <Select
          label={t('admin.integration')}
          data={['MANUAL', 'FILE', 'API', 'SIMULATOR']}
          value={integration}
          allowDeselect={false}
          onChange={(value) => value && setIntegration(value)}
        />
        <Switch label={t('marketData.active')} checked={active} onChange={(e) => setActive(e.currentTarget.checked)} />
        <Textarea label={t('risk.reason')} value={reason} onChange={(e) => setReason(e.currentTarget.value)} autosize />
        <Button
          disabled={!reason.trim()}
          loading={request.isPending}
          onClick={() =>
            request.mutate({
              params: { path: { code: registry.code } },
              body: { integration, active, reason, expectedVersion: registry.version },
            })
          }
        >
          {t('risk.sendForApproval')}
        </Button>
      </Stack>
    </Modal>
  );
}

function RegistriesTab() {
  const { t } = useTranslation();
  const registries = useRegistries();
  const [changing, setChanging] = useState<Registry | null>(null);
  return (
    <QueryState isPending={registries.isPending} error={registries.error}>
      <Paper withBorder>
        <Table highlightOnHover verticalSpacing="sm">
          <Table.Thead>
            <Table.Tr>
              <Table.Th>{t('marketData.code')}</Table.Th>
              <Table.Th>{t('ets.name')}</Table.Th>
              <Table.Th>{t('marketData.kind')}</Table.Th>
              <Table.Th>{t('admin.integration')}</Table.Th>
              <Table.Th>{t('admin.hub')}</Table.Th>
              <Table.Th>{t('marketData.active')}</Table.Th>
              <Table.Th />
            </Table.Tr>
          </Table.Thead>
          <Table.Tbody>
            {(registries.data ?? []).map((registry) => (
              <Table.Tr key={registry.id}>
                <Table.Td ff="monospace">{registry.code}</Table.Td>
                <Table.Td>{registry.name}</Table.Td>
                <Table.Td>{statusLabel(registry.registryType)}</Table.Td>
                <Table.Td>
                  <Badge variant="light" color="gray" tt="none">
                    {t(`admin.integrationModes.${registry.integration}`, { defaultValue: registry.integration })}
                  </Badge>
                </Table.Td>
                <Table.Td>{registry.hubMember ? t('common.yes') : t('common.no')}</Table.Td>
                <Table.Td>
                  <ActiveBadge active={registry.active} />
                </Table.Td>
                <Table.Td>
                  <Can op="changeRequest_requestRegistryChange">
                    <Button size="compact-xs" variant="subtle" onClick={() => setChanging(registry)}>
                      {t('admin.requestChange')}
                    </Button>
                  </Can>
                </Table.Td>
              </Table.Tr>
            ))}
          </Table.Tbody>
        </Table>
      </Paper>
      {changing && <RegistryChangeModal registry={changing} onClose={() => setChanging(null)} />}
    </QueryState>
  );
}

function DeviceDrawer({ deviceId, onClose }: { deviceId: string; onClose: () => void }) {
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

function DevicesTab() {
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

const TABS = ['entities', 'counterparties', 'registries', 'devices'] as const;

/** Legal entities, counterparties, registries and devices, with their history and four-eyes changes. */
export function ReferenceDataPage() {
  const { t } = useTranslation();
  const [tab, setTab] = useUrlTab(TABS, 'entities');
  return (
    <Stack>
      <PageHeader title={t('admin.referenceTitle')} description={t('admin.referenceDescription')} />
      <Alert color="blue" variant="light" icon={<IconShieldCheck size={18} />} py="xs">
        <Text size="sm">{t('admin.fourEyesNote')}</Text>
      </Alert>
      <Tabs value={tab} onChange={(value) => setTab(value)} keepMounted={false}>
        <Tabs.List>
          <Tabs.Tab value="entities">{t('admin.tabs.entities')}</Tabs.Tab>
          <Tabs.Tab value="counterparties">{t('admin.tabs.counterparties')}</Tabs.Tab>
          <Tabs.Tab value="registries">{t('admin.tabs.registries')}</Tabs.Tab>
          <Tabs.Tab value="devices">{t('admin.tabs.devices')}</Tabs.Tab>
        </Tabs.List>
        <Tabs.Panel value="entities" pt="md">
          <EntitiesTab />
        </Tabs.Panel>
        <Tabs.Panel value="counterparties" pt="md">
          <CounterpartiesTab />
        </Tabs.Panel>
        <Tabs.Panel value="registries" pt="md">
          <RegistriesTab />
        </Tabs.Panel>
        <Tabs.Panel value="devices" pt="md">
          <DevicesTab />
        </Tabs.Panel>
      </Tabs>
    </Stack>
  );
}
