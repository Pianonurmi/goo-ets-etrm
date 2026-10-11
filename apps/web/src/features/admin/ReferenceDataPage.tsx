import {
  Alert,
  Badge,
  Button,
  Divider,
  Drawer,
  Group,
  Modal,
  NumberInput,
  Select,
  Stack,
  Switch,
  Table,
  Tabs,
  Text,
  TextInput,
  Textarea,
  Title,
} from '@mantine/core';
import { IconHistory, IconPlus } from '@tabler/icons-react';
import type { ColDef } from 'ag-grid-community';
import dayjs from 'dayjs';
import { useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useSearchParams } from 'react-router';
import { $api } from '@/shared/api/client';
import type { Body, Schema } from '@/shared/api/types';
import { Can } from '@/shared/auth/Can';
import { notifySuccess } from '@/shared/components/notify';
import { PageHeader } from '@/shared/components/PageHeader';
import { ProblemAlert } from '@/shared/components/ProblemAlert';
import { QueryState } from '@/shared/components/QueryState';
import { DataGrid } from '@/shared/grid/DataGrid';
import { HistoryDrawer } from './HistoryDrawer';
import { PartyDetails, useInvalidateReference } from './PartyDetails';

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
    <Badge size="sm" variant="light" color={active ? 'teal' : 'gray'}>
      {active ? t('marketData.active') : t('admin.inactive')}
    </Badge>
  );
}

function useRegistries() {
  return $api.useQuery('get', '/api/v1/reference/registries', {}, { staleTime: 5 * 60_000 });
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
    <Stack gap="xs">
      <Group justify="space-between">
        <Title order={6}>{t('admin.registryAccounts')}</Title>
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
      </Group>
      {(accounts.data ?? []).map((account) => (
        <Group key={account.id} gap="xs">
          <Text size="sm" ff="monospace">
            {account.registryCode}:{account.accountNumber}
          </Text>
          <Text size="sm">
            {account.name} · {account.accountType}
          </Text>
          {account.trusted && (
            <Badge size="xs" variant="light">
              {t('admin.trusted')}
            </Badge>
          )}
          {!account.active && <ActiveBadge active={false} />}
        </Group>
      ))}
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
    </Stack>
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
    <Stack gap="xs">
      <Group justify="space-between">
        <Title order={6}>{t('admin.books')}</Title>
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
      </Group>
      {(books.data ?? []).map((book) => (
        <Text size="sm" key={book.id}>
          {book.code} · {book.name} · {book.bookType}
        </Text>
      ))}
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
    </Stack>
  );
}

function EntityDrawer({ entity, onClose }: { entity: LegalEntity; onClose: () => void }) {
  const { t } = useTranslation();
  const [history, setHistory] = useState(false);
  return (
    <Drawer opened onClose={onClose} position="right" size="xl" title={`${entity.code} · ${entity.name}`}>
      <Stack>
        <Group gap="xs">
          <ActiveBadge active={entity.active} />
          <Text size="sm">
            {entity.country} · {entity.baseCurrency} · {entity.timeZone} · LEI {entity.lei ?? '–'}
          </Text>
          <Button
            size="compact-sm"
            variant="subtle"
            leftSection={<IconHistory size={14} />}
            onClick={() => setHistory(true)}
          >
            {t('admin.history')}
          </Button>
        </Group>
        <Divider />
        <Books entityId={entity.id} />
        <Divider />
        <RegistryAccounts holderType="LEGAL_ENTITY" holderId={entity.id} />
        <Divider />
        <PartyDetails partyType="LEGAL_ENTITY" partyId={entity.id} />
      </Stack>
      {history && (
        <HistoryDrawer table="legal_entity" id={entity.id} title={entity.code} onClose={() => setHistory(false)} />
      )}
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
  const [open, setOpen] = useState<LegalEntity | null>(null);
  const [creating, setCreating] = useState(false);
  const entities = $api.useQuery('get', '/api/v1/reference/legal-entities');
  const columns = useMemo<ColDef<LegalEntity>[]>(
    () => [
      { field: 'code', headerName: t('marketData.code'), width: 140 },
      { field: 'name', headerName: t('ets.name'), flex: 1, minWidth: 200 },
      { field: 'country', headerName: t('ets.country'), width: 100 },
      { field: 'baseCurrency', headerName: t('admin.baseCurrency'), width: 130 },
      { field: 'lei', headerName: 'LEI', width: 210 },
      { field: 'emirClassification', headerName: 'EMIR', width: 120 },
      { field: 'active', headerName: t('marketData.active'), width: 100 },
      { field: 'version', headerName: t('trades.version'), width: 100 },
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
      <QueryState isPending={entities.isPending} error={entities.error}>
        <DataGrid<LegalEntity>
          rowData={entities.data ?? []}
          columnDefs={columns}
          rowId={(row) => row.id}
          height={460}
          onRowClicked={(event) => setOpen(event.data ?? null)}
        />
      </QueryState>
      {open && <EntityDrawer entity={open} onClose={() => setOpen(null)} />}
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

function CounterpartyDrawer({ counterparty, onClose }: { counterparty: Counterparty; onClose: () => void }) {
  const { t } = useTranslation();
  const [history, setHistory] = useState(false);
  return (
    <Drawer opened onClose={onClose} position="right" size="xl" title={`${counterparty.code} · ${counterparty.name}`}>
      <Stack>
        <Group gap="xs">
          <ActiveBadge active={counterparty.active} />
          <Text size="sm">
            {counterparty.counterpartyType} · {counterparty.country} · {counterparty.settlementCurrency} ·{' '}
            {t('admin.paymentTermsDays', { days: counterparty.paymentTermsDays })}
          </Text>
          <Button
            size="compact-sm"
            variant="subtle"
            leftSection={<IconHistory size={14} />}
            onClick={() => setHistory(true)}
          >
            {t('admin.history')}
          </Button>
        </Group>
        {counterparty.masterAgreements.length > 0 && (
          <Text size="sm">
            {t('admin.masterAgreements')}:{' '}
            {counterparty.masterAgreements.map((a) => `${a.type} ${a.reference}`).join(', ')}
          </Text>
        )}
        <Divider />
        <RegistryAccounts holderType="COUNTERPARTY" holderId={counterparty.id} />
        <Divider />
        <PartyDetails partyType="COUNTERPARTY" partyId={counterparty.id} />
      </Stack>
      {history && (
        <HistoryDrawer
          table="counterparty"
          id={counterparty.id}
          title={counterparty.code}
          onClose={() => setHistory(false)}
        />
      )}
    </Drawer>
  );
}

function CounterpartiesTab() {
  const { t } = useTranslation();
  const [open, setOpen] = useState<Counterparty | null>(null);
  const [creating, setCreating] = useState(false);
  const counterparties = $api.useQuery('get', '/api/v1/reference/counterparties');
  const columns = useMemo<ColDef<Counterparty>[]>(
    () => [
      { field: 'code', headerName: t('marketData.code'), width: 160 },
      { field: 'name', headerName: t('ets.name'), flex: 1, minWidth: 220 },
      { field: 'counterpartyType', headerName: t('marketData.kind'), width: 140 },
      { field: 'country', headerName: t('ets.country'), width: 100 },
      { field: 'lei', headerName: 'LEI', width: 210 },
      { field: 'settlementCurrency', headerName: t('trades.form.currency'), width: 110 },
      { field: 'active', headerName: t('marketData.active'), width: 100 },
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
      <QueryState isPending={counterparties.isPending} error={counterparties.error}>
        <DataGrid<Counterparty>
          rowData={counterparties.data ?? []}
          columnDefs={columns}
          rowId={(row) => row.id}
          height={460}
          onRowClicked={(event) => setOpen(event.data ?? null)}
        />
      </QueryState>
      {open && <CounterpartyDrawer counterparty={open} onClose={() => setOpen(null)} />}
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
      <Table striped withTableBorder>
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
              <Table.Td>{registry.code}</Table.Td>
              <Table.Td>{registry.name}</Table.Td>
              <Table.Td>{registry.registryType}</Table.Td>
              <Table.Td>
                <Badge variant="light">{registry.integration}</Badge>
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
      {changing && <RegistryChangeModal registry={changing} onClose={() => setChanging(null)} />}
    </QueryState>
  );
}

function DevicesTab() {
  const { t } = useTranslation();
  const [own, setOwn] = useState(true);
  const [history, setHistory] = useState<Device | null>(null);
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
      { field: 'active', headerName: t('marketData.active'), width: 100 },
    ],
    [t],
  );
  return (
    <Stack>
      <Switch label={t('admin.ownDevices')} checked={own} onChange={(e) => setOwn(e.currentTarget.checked)} />
      <QueryState isPending={devices.isPending} error={devices.error}>
        <DataGrid<Device>
          rowData={devices.data ?? []}
          columnDefs={columns}
          rowId={(row) => row.id}
          height={460}
          onRowClicked={(event) => setHistory(event.data ?? null)}
          overlayNoRowsTemplate={t('common.empty')}
        />
      </QueryState>
      {history && (
        <HistoryDrawer
          table="production_device"
          id={history.id}
          title={history.name}
          onClose={() => setHistory(null)}
        />
      )}
    </Stack>
  );
}

const TABS = ['entities', 'counterparties', 'registries', 'devices'] as const;

/** Legal entities, counterparties, registries and devices, with their history and four-eyes changes. */
export function ReferenceDataPage() {
  const { t } = useTranslation();
  const [params, setParams] = useSearchParams();
  const requested = params.get('tab');
  const tab = TABS.find((value) => value === requested) ?? 'entities';
  return (
    <Stack>
      <PageHeader title={t('admin.referenceTitle')} description={t('admin.referenceDescription')} />
      <Alert color="blue" variant="light">
        {t('admin.fourEyesNote')}
      </Alert>
      <Tabs
        value={tab}
        onChange={(value) => setParams(value && value !== 'entities' ? { tab: value } : {}, { replace: true })}
        keepMounted={false}
      >
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
