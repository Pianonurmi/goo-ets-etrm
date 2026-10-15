import { useUrlState, useUrlTab } from '@/shared/useUrlState';
import {
  Alert,
  Button,
  Code,
  Group,
  Modal,
  NumberInput,
  Paper,
  SegmentedControl,
  Select,
  SimpleGrid,
  Stack,
  Switch,
  Table,
  Tabs,
  Text,
} from '@mantine/core';
import { IconArrowsTransferUp, IconCoins, IconPencil, IconPlus } from '@tabler/icons-react';
import type { ColDef, ICellRendererParams } from 'ag-grid-community';
import { useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useNavigate } from 'react-router';
import { $api } from '@/shared/api/client';
import { decimal, type Schema } from '@/shared/api/types';
import { Can } from '@/shared/auth/Can';
import { EmptyState } from '@/shared/components/EmptyState';
import { ScrollTable } from '@/shared/components/ScrollTable';
import { JsonEditorModal } from '@/shared/components/JsonEditorModal';
import { KpiCard } from '@/shared/components/KpiCard';
import { notifySuccess } from '@/shared/components/notify';
import { PageHeader } from '@/shared/components/PageHeader';
import { ProblemAlert } from '@/shared/components/ProblemAlert';
import { QueryState } from '@/shared/components/QueryState';
import { TableSkeleton } from '@/shared/components/Skeletons';
import { StatusBadge, statusLabel } from '@/shared/components/StatusBadge';
import { useEntity } from '@/shared/context/EntityContext';
import { formatDateTime, formatDecimal } from '@/shared/format/format';
import { DataGrid } from '@/shared/grid/DataGrid';
import { oneOfOrNull } from '@/shared/urlParams';
import {
  QUEUE_VIEWS,
  asQueueView,
  queueStatuses,
  subject,
  type Instruction,
  type InstructionStatus,
  type Route,
} from './model/instructions';
import { AllowanceTransferModal } from './AllowanceTransferModal';
import { NEW_FORMAT, formatKey, formatToUpsert, upsertBody } from './model/formats';
import { TransferModal, useInvalidateRegistry, useRegistries } from './TransferModal';

const TABS = ['instructions', 'routes', 'formats'] as const;
const ASSET_KINDS: NonNullable<Instruction['assetKind']>[] = ['GOO_LOT', 'ALLOWANCE'];
const PAGE = 100;
/** The figures read a page of their own: a plain query must not share the infinite list's cache entry. */
const KPI_PAGE = 200;

export function InstructionStatusBadge({ status, testId }: { status: InstructionStatus; testId?: string }) {
  return <StatusBadge status={status} data-testid={testId} />;
}

const SENT: InstructionStatus[] = ['DISPATCHING', 'SENT', 'ACKNOWLEDGED'];

/** Open work in the registry queue, counted from the open and the attention views (first page of each). */
function RegistryKpis({ entityId }: { entityId: string }) {
  const { t } = useTranslation();
  const query = (view: 'open' | 'attention') => ({
    params: { query: { holderEntityId: entityId, status: queueStatuses(view), limit: KPI_PAGE } },
  });
  const open = $api.useQuery('get', '/api/v1/registry/instructions/search', query('open'));
  const attention = $api.useQuery('get', '/api/v1/registry/instructions/search', query('attention'));
  const shown = (count: number, more: boolean) => (more ? `${count}+` : count);
  const items = open.data?.items ?? [];
  const more = Boolean(open.data?.nextCursor);
  const pending = items.filter((row) => row.status === 'PENDING_APPROVAL').length;
  const sent = items.filter((row) => SENT.includes(row.status)).length;
  const problems = attention.data?.items.length ?? 0;
  return (
    <SimpleGrid cols={{ base: 1, xs: 2, lg: 4 }} spacing="md" data-testid="registry-kpis">
      <KpiCard
        label={t('registry.kpi.open')}
        value={shown(items.length, more)}
        loading={open.isPending}
        trend={t('registry.kpi.openHint')}
        to="/registry"
      />
      <KpiCard
        label={t('registry.kpi.pending')}
        value={shown(pending, more)}
        loading={open.isPending}
        trend={pending > 0 ? t('registry.kpi.pendingHint') : t('registry.kpi.nothingPending')}
        trendTone={pending > 0 ? 'warning' : 'neutral'}
      />
      <KpiCard
        label={t('registry.kpi.sent')}
        value={shown(sent, more)}
        loading={open.isPending}
        trend={t('registry.kpi.sentHint')}
      />
      <KpiCard
        label={t('registry.kpi.attention')}
        value={shown(problems, Boolean(attention.data?.nextCursor))}
        loading={attention.isPending}
        trend={problems > 0 ? t('registry.kpi.attentionHint') : t('registry.kpi.nothingWrong')}
        trendTone={problems > 0 ? 'down' : 'up'}
        to="/registry?view=attention"
      />
    </SimpleGrid>
  );
}

function InstructionsTab({ entityId }: { entityId: string }) {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const { params, set: patchUrl } = useUrlState();
  const registries = useRegistries();
  const view = asQueueView(params.get('view'));
  const registryCode = params.get('registry');
  const assetKind = oneOfOrNull(params.get('asset'), ASSET_KINDS);
  const set = (key: string, value: string | null) => patchUrl({ [key]: value });
  const search = $api.useInfiniteQuery(
    'get',
    '/api/v1/registry/instructions/search',
    {
      params: {
        query: {
          holderEntityId: entityId,
          status: queueStatuses(view),
          registryCode: registryCode ?? undefined,
          assetKind: assetKind ?? undefined,
          limit: PAGE,
        },
      },
    },
    {
      initialPageParam: null as string | null,
      getNextPageParam: (page) => page.nextCursor ?? undefined,
      pageParamName: 'cursor',
    },
  );
  const rows = useMemo(() => search.data?.pages.flatMap((page) => page.items) ?? [], [search.data]);
  const columns = useMemo<ColDef<Instruction>[]>(
    () => [
      { field: 'instructionKey', headerName: t('registry.key'), width: 220, cellStyle: { fontWeight: 600 } },
      {
        field: 'status',
        headerName: t('allocation.status'),
        width: 160,
        cellRenderer: (p: ICellRendererParams<Instruction>) =>
          p.data && <InstructionStatusBadge status={p.data.status} />,
      },
      {
        field: 'type',
        headerName: t('registry.type'),
        width: 170,
        valueFormatter: (p) => t(`registry.types.${String(p.value)}`),
      },
      {
        colId: 'subject',
        headerName: t('registry.what'),
        width: 180,
        valueGetter: (p) => (p.data ? subject(p.data) : ''),
      },
      {
        colId: 'route',
        headerName: t('registry.route'),
        flex: 1,
        minWidth: 220,
        valueGetter: (p) =>
          p.data ? `${p.data.registryCode} → ${p.data.toRegistryCode ?? ''} ${p.data.toAccountRef ?? ''}`.trim() : '',
      },
      {
        field: 'channel',
        headerName: t('registry.channel'),
        width: 120,
        valueFormatter: (p) => (p.value ? statusLabel(String(p.value)) : ''),
      },
      { field: 'attempts', headerName: t('registry.attempts'), width: 100, type: 'rightAligned' },
      { field: 'lastError', headerName: t('registry.lastError'), width: 240, tooltipField: 'lastError' },
      {
        field: 'expectedEffectiveAt',
        headerName: t('registry.expectedEffective'),
        width: 170,
        valueFormatter: (p) => formatDateTime(p.value as string | null),
      },
      {
        field: 'createdAt',
        headerName: t('registry.submitted'),
        width: 160,
        valueFormatter: (p) => formatDateTime(p.value as string),
      },
      { field: 'submittedBy', headerName: t('registry.submittedBy'), width: 130 },
    ],
    [t],
  );
  return (
    <Stack gap="sm">
      <Group align="flex-end" gap="sm">
        <SegmentedControl
          value={view}
          onChange={(value) => set('view', value === 'open' ? null : value)}
          data={QUEUE_VIEWS.map((value) => ({ value, label: t(`registry.views.${value}`) }))}
          data-testid="queue-view"
        />
        <Select
          aria-label={t('registry.registry')}
          placeholder={t('registry.allRegistries')}
          data={(registries.data ?? []).map((registry) => ({ value: registry.code, label: registry.code }))}
          value={registryCode}
          onChange={(value) => set('registry', value)}
          clearable
          w={200}
        />
        <Select
          aria-label={t('registry.assetKind')}
          placeholder={t('registry.allAssets')}
          data={[
            { value: 'GOO_LOT', label: t('registry.assets.GOO_LOT') },
            { value: 'ALLOWANCE', label: t('registry.assets.ALLOWANCE') },
          ]}
          value={assetKind}
          onChange={(value) => set('asset', value)}
          clearable
          w={200}
        />
      </Group>
      <QueryState isPending={false} error={search.error}>
        {search.isPending ? (
          <Paper withBorder radius="lg">
            <TableSkeleton rows={8} />
          </Paper>
        ) : rows.length === 0 ? (
          <Paper withBorder radius="lg">
            <EmptyState
              title={t(`registry.empty.${view}`)}
              description={t('registry.emptyHint')}
              testId="instructions-empty"
            />
          </Paper>
        ) : (
          <DataGrid<Instruction>
            rowData={rows}
            columnDefs={columns}
            rowId={(row) => row.id}
            height={Math.min(520, 46 + rows.length * 40 + 2)}
            onRowClicked={(event) => event.data && void navigate(`/registry/instructions/${event.data.id}`)}
            rowStyle={{ cursor: 'pointer' }}
          />
        )}
        <Group justify="space-between">
          <Text size="sm" c="dimmed" data-testid="instruction-count">
            {t('registry.shown', { count: rows.length })}
          </Text>
          {search.hasNextPage && (
            <Button variant="light" loading={search.isFetchingNextPage} onClick={() => void search.fetchNextPage()}>
              {t('inbox.loadMore')}
            </Button>
          )}
        </Group>
      </QueryState>
    </Stack>
  );
}

/** Feasibility, fee and expected days of a route; changed by middle or back office. */
function RouteModal({ route, onClose }: { route: Route; onClose: () => void }) {
  const { t } = useTranslation();
  const invalidate = useInvalidateRegistry();
  const [feasible, setFeasible] = useState(route.feasible);
  const [fee, setFee] = useState(String(route.feePerMwh));
  const [days, setDays] = useState<number>(route.expectedDays);
  const save = $api.useMutation('put', '/api/v1/registry/routes', {
    meta: { notify: false },
    onSuccess: () => {
      notifySuccess(t('registry.routeSaved'));
      void invalidate();
      onClose();
    },
  });
  return (
    <Modal opened onClose={onClose} title={`${route.fromRegistryCode} → ${route.toRegistryCode}`}>
      <Stack>
        <ProblemAlert error={save.error} />
        <Switch
          label={t('registry.feasible')}
          checked={feasible}
          onChange={(e) => setFeasible(e.currentTarget.checked)}
        />
        <Group grow>
          <NumberInput
            label={t('registry.feePerMwh')}
            value={fee}
            onChange={(value) => setFee(String(value))}
            decimalScale={4}
            min={0}
          />
          <NumberInput
            label={t('registry.expectedDays')}
            value={days}
            onChange={(value) => setDays(Number(value))}
            min={0}
            allowDecimal={false}
          />
        </Group>
        <Button
          loading={save.isPending}
          onClick={() =>
            save.mutate({
              body: {
                fromRegistryCode: route.fromRegistryCode,
                toRegistryCode: route.toRegistryCode,
                feasible,
                feePerMwh: decimal(fee),
                expectedDays: days,
              },
            })
          }
        >
          {t('registry.saveRoute')}
        </Button>
      </Stack>
    </Modal>
  );
}

function RoutesTab() {
  const { t } = useTranslation();
  const registries = useRegistries();
  const { params, set: patchUrl } = useUrlState();
  const from = params.get('from') ?? registries.data?.[0]?.code ?? null;
  const [editing, setEditing] = useState<Route | null>(null);
  const routes = $api.useQuery(
    'get',
    '/api/v1/registry/routes',
    { params: { query: { from: from ?? '' } } },
    { enabled: Boolean(from) },
  );
  const rows = Object.values(routes.data ?? {}).sort((a, b) => a.toRegistryCode.localeCompare(b.toRegistryCode));
  return (
    <Stack>
      <Select
        label={t('registry.fromRegistry')}
        data={(registries.data ?? []).map((registry) => ({
          value: registry.code,
          label: `${registry.code} · ${registry.name}`,
        }))}
        value={from}
        onChange={(value) => {
          if (value) patchUrl({ from: value });
        }}
        allowDeselect={false}
        w={320}
      />
      <Text size="sm" c="dimmed">
        {t('registry.routesHint')}
      </Text>
      <QueryState isPending={routes.isPending && Boolean(from)} error={routes.error}>
        <Paper withBorder radius="lg">
          <Table verticalSpacing="sm" highlightOnHover data-testid="routes-table">
            <Table.Thead>
              <Table.Tr>
                <Table.Th>{t('registry.toRegistry')}</Table.Th>
                <Table.Th>{t('registry.feasible')}</Table.Th>
                <Table.Th ta="right">{t('registry.feePerMwh')}</Table.Th>
                <Table.Th ta="right">{t('registry.expectedDays')}</Table.Th>
                <Table.Th />
              </Table.Tr>
            </Table.Thead>
            <Table.Tbody>
              {rows.map((route) => (
                <Table.Tr key={route.toRegistryCode}>
                  <Table.Td>{route.toRegistryCode}</Table.Td>
                  <Table.Td>
                    <StatusBadge
                      status={route.feasible ? 'OK' : 'REJECTED'}
                      label={route.feasible ? t('registry.feasibleYes') : t('registry.feasibleNo')}
                    />
                  </Table.Td>
                  <Table.Td ta="right">{formatDecimal(route.feePerMwh, 2, 4)}</Table.Td>
                  <Table.Td ta="right">{route.expectedDays}</Table.Td>
                  <Table.Td ta="right">
                    <Can op="registry_upsertRoute">
                      <Button
                        size="compact-xs"
                        variant="subtle"
                        leftSection={<IconPencil size={12} />}
                        onClick={() => setEditing(route)}
                      >
                        {t('registry.edit')}
                      </Button>
                    </Can>
                  </Table.Td>
                </Table.Tr>
              ))}
            </Table.Tbody>
          </Table>
        </Paper>
      </QueryState>
      {editing && <RouteModal route={editing} onClose={() => setEditing(null)} />}
    </Stack>
  );
}

type Format = Schema<'StatementFormatView'>;

/**
 * Edits a statement format as JSON: an existing one (keyed by registry, profile and kind), or a new profile from a
 * template. The server checks the column mapping against the kind and the date pattern.
 */
function FormatEditor({ format, onClose }: { format: Format | null; onClose: () => void }) {
  const { t } = useTranslation();
  const invalidate = useInvalidateRegistry();
  const [code, setCode] = useState('');
  const save = $api.useMutation('put', '/api/v1/registry/statement-formats', {
    meta: { notify: false },
    onSuccess: (saved) => {
      notifySuccess(t('registry.formatSaved', { profile: saved.profileCode, version: saved.version }));
      void invalidate();
      onClose();
    },
  });
  return (
    <JsonEditorModal
      title={format ? t('registry.editFormat', { profile: format.profileCode }) : t('registry.newFormat')}
      hint={t('registry.formatEditorHint')}
      initial={format ? formatToUpsert(format) : NEW_FORMAT}
      {...(format ? {} : { code, onCodeChange: setCode, codeLabel: t('registry.profile') })}
      pending={save.isPending}
      error={save.error}
      onSubmit={(json) => save.mutate({ body: upsertBody(json, format ? undefined : code) })}
      onClose={onClose}
    />
  );
}

function FormatsTab() {
  const { t } = useTranslation();
  const formats = $api.useQuery('get', '/api/v1/registry/statement-formats', {});
  const [editing, setEditing] = useState<Format | 'new' | null>(null);
  return (
    <Stack>
      <Group justify="space-between">
        <Text size="sm" c="dimmed">
          {t('registry.formatsHint')}
        </Text>
        <Can op="registry_upsertFormat">
          <Button
            variant="light"
            leftSection={<IconPlus size={16} />}
            onClick={() => setEditing('new')}
            data-testid="new-format"
          >
            {t('registry.newFormat')}
          </Button>
        </Can>
      </Group>
      <QueryState isPending={formats.isPending} error={formats.error}>
        <Paper withBorder radius="lg">
          <ScrollTable label={t('registry.tabs.formats')} minWidth={900}>
            <Table verticalSpacing="sm" highlightOnHover data-testid="formats-table">
              <Table.Thead>
                <Table.Tr>
                  <Table.Th>{t('registry.registry')}</Table.Th>
                  <Table.Th>{t('registry.profile')}</Table.Th>
                  <Table.Th>{t('registry.kind')}</Table.Th>
                  <Table.Th>{t('registry.layout')}</Table.Th>
                  <Table.Th>{t('registry.columns')}</Table.Th>
                  <Table.Th>{t('registry.updated')}</Table.Th>
                  <Table.Th />
                </Table.Tr>
              </Table.Thead>
              <Table.Tbody>
                {(formats.data ?? []).map((format) => (
                  <Table.Tr key={format.id} data-testid={`format-row-${formatKey(format)}`}>
                    <Table.Td fw={600}>{format.registryCode}</Table.Td>
                    <Table.Td>
                      {format.profileCode}
                      {!format.active && <StatusBadge ml="xs" status="INACTIVE" label={t('registry.inactive')} />}
                    </Table.Td>
                    <Table.Td>{t(`registry.statementKinds.${format.kind}`)}</Table.Td>
                    <Table.Td>
                      <Text size="xs">
                        {t('registry.layoutText', {
                          delimiter: format.delimiter,
                          skip: format.skipLines,
                          date: format.dateFormat,
                        })}
                      </Text>
                    </Table.Td>
                    <Table.Td>
                      <Stack gap={2}>
                        {Object.entries(format.columnMapping).map(([field, column]) => (
                          <Text size="xs" key={field}>
                            <Code>{column}</Code> → {field}
                          </Text>
                        ))}
                      </Stack>
                    </Table.Td>
                    <Table.Td>
                      <Text size="xs">
                        v{format.version} · {format.updatedBy} · {formatDateTime(format.updatedAt)}
                      </Text>
                    </Table.Td>
                    <Table.Td ta="right">
                      <Can op="registry_upsertFormat">
                        <Button
                          size="compact-xs"
                          variant="subtle"
                          leftSection={<IconPencil size={12} />}
                          onClick={() => setEditing(format)}
                          data-testid={`format-edit-${formatKey(format)}`}
                        >
                          {t('registry.edit')}
                        </Button>
                      </Can>
                    </Table.Td>
                  </Table.Tr>
                ))}
              </Table.Tbody>
            </Table>
          </ScrollTable>
        </Paper>
      </QueryState>
      {editing && <FormatEditor format={editing === 'new' ? null : editing} onClose={() => setEditing(null)} />}
    </Stack>
  );
}

/** Registry instructions of the selected entity, transfer routes between registries, and statement formats. */
export function RegistryPage() {
  const { t } = useTranslation();
  const { entity } = useEntity();
  const [tab, setTab] = useUrlTab(TABS, 'instructions');
  const [transferring, setTransferring] = useState<'goo' | 'allowances' | null>(null);
  return (
    <Stack gap="lg">
      <PageHeader
        title={t('registry.title')}
        description={t('registry.description', { entity: entity?.code ?? '' })}
        actions={
          entity && (
            <Group gap="xs">
              <Can op="registry_allowanceTransfer">
                <Button
                  variant="default"
                  leftSection={<IconCoins size={16} />}
                  onClick={() => setTransferring('allowances')}
                  data-testid="new-allowance-transfer"
                >
                  {t('registry.allowanceTransfer')}
                </Button>
              </Can>
              <Can op="registry_transfer">
                <Button
                  leftSection={<IconArrowsTransferUp size={16} />}
                  onClick={() => setTransferring('goo')}
                  data-testid="new-transfer"
                >
                  {t('registry.newTransfer')}
                </Button>
              </Can>
            </Group>
          )
        }
      />
      {!entity ? (
        <Alert color="gray">{t('header.noEntity')}</Alert>
      ) : (
        <>
          <RegistryKpis entityId={entity.id} />
          <Tabs value={tab} onChange={(value) => setTab(value)} keepMounted={false}>
            <Tabs.List>
              <Tabs.Tab value="instructions">{t('registry.tabs.instructions')}</Tabs.Tab>
              <Tabs.Tab value="routes">{t('registry.tabs.routes')}</Tabs.Tab>
              <Tabs.Tab value="formats">{t('registry.tabs.formats')}</Tabs.Tab>
            </Tabs.List>
            <Tabs.Panel value="instructions" pt="md">
              <InstructionsTab entityId={entity.id} />
            </Tabs.Panel>
            <Tabs.Panel value="routes" pt="md">
              <RoutesTab />
            </Tabs.Panel>
            <Tabs.Panel value="formats" pt="md">
              <FormatsTab />
            </Tabs.Panel>
          </Tabs>
        </>
      )}
      {transferring === 'goo' && entity && <TransferModal entityId={entity.id} onClose={() => setTransferring(null)} />}
      {transferring === 'allowances' && entity && (
        <AllowanceTransferModal entityId={entity.id} onClose={() => setTransferring(null)} />
      )}
    </Stack>
  );
}
