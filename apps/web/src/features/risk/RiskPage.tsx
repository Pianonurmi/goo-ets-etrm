import { BarChart } from '@mantine/charts';
import {
  Alert,
  Badge,
  Button,
  Card,
  Group,
  Modal,
  Progress,
  Select,
  SimpleGrid,
  Stack,
  Switch,
  Table,
  Tabs,
  Text,
  TextInput,
  Tooltip,
} from '@mantine/core';
import { IconGauge, IconPlus } from '@tabler/icons-react';
import { useQueries } from '@tanstack/react-query';
import type { ColDef, ICellRendererParams } from 'ag-grid-community';
import dayjs from 'dayjs';
import { useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useNavigate, useSearchParams } from 'react-router';
import { $api } from '@/shared/api/client';
import { decimal, type Schema } from '@/shared/api/types';
import { Can } from '@/shared/auth/Can';
import { KpiCard } from '@/shared/components/KpiCard';
import { notifySuccess } from '@/shared/components/notify';
import { PageHeader } from '@/shared/components/PageHeader';
import { ProblemAlert } from '@/shared/components/ProblemAlert';
import { QueryState } from '@/shared/components/QueryState';
import { TableSkeleton } from '@/shared/components/Skeletons';
import { StatusBadge, statusLabel } from '@/shared/components/StatusBadge';
import { useEntity } from '@/shared/context/EntityContext';
import { formatDate, formatDateTime, formatDecimal, formatMoney } from '@/shared/format/format';
import { DataGrid } from '@/shared/grid/DataGrid';
import { useBooks, useCounterparties } from '@/features/trades/hooks';
import { limitUnit, scopeLabel, utilisationBar, utilisationColor, type Limit, type ScopeNames } from './model/limits';
import { RequestLimitModal, useInvalidateRisk } from './RequestLimitModal';

type Utilisation = Schema<'LimitUtilisationView'>;
type Exposure = Schema<'CounterpartyExposureView'>;
type Breach = Schema<'LimitBreachView'>;
type CreditSupport = Schema<'CreditSupportView'>;
type CreditSupportKind = CreditSupport['kind'];
const CREDIT_SUPPORT_KINDS: CreditSupportKind[] = [
  'GUARANTEE_RECEIVED',
  'LETTER_OF_CREDIT_RECEIVED',
  'CASH_RECEIVED',
  'CASH_POSTED',
];

interface LimitRow extends Limit {
  scopeText: string;
  current?: Utilisation;
}

export function useScopeNames(entityId: string | undefined): ScopeNames {
  const { t } = useTranslation();
  const counterparties = useCounterparties();
  const books = useBooks(entityId);
  return useMemo(
    () => ({
      counterparty: (id: string) => counterparties.byId.get(id)?.name,
      book: (id: string) => books.byId.get(id)?.code,
      horizon: (days: number) => t('risk.horizonScope', { days }),
      vintage: (year: number) => t('risk.vintageScope', { year }),
    }),
    [counterparties.byId, books.byId, t],
  );
}

export function LimitStatusBadge({ status, testId }: { status: Limit['status']; testId?: string }) {
  return <StatusBadge status={status} data-testid={testId} />;
}

/** An open breach is a problem (red), a closed one is history (grey). */
function BreachBadge({ status }: { status: Breach['status'] }) {
  return <StatusBadge status={status === 'OPEN' ? 'BREACH' : 'CLOSED'} label={statusLabel(status)} />;
}

/**
 * Limits in breach and near their warning level (the latest measurements), the highest utilisation, and open breaches;
 * the counts come from the rows, the percentages from the server.
 */
function RiskKpis({ entityId }: { entityId: string }) {
  const { t } = useTranslation();
  const limits = $api.useQuery('get', '/api/v1/risk/limits', {
    params: { query: { legalEntityId: entityId, activeOnly: true } },
  });
  const breaches = $api.useQuery('get', '/api/v1/risk/breaches', {
    params: { query: { legalEntityId: entityId, openOnly: true } },
  });
  const active = limits.data ?? [];
  const utilisations = useUtilisations(active);
  const measured = active.flatMap((limit) => {
    const current = utilisations.get(limit.id);
    return current ? [{ limit, current }] : [];
  });
  const inBreach = measured.filter(
    (row) => row.current.status === 'BREACH' || row.current.status === 'NOT_MEASURED',
  ).length;
  const warning = measured.filter((row) => row.current.status === 'WARNING').length;
  const highest = measured.reduce<(typeof measured)[number] | undefined>(
    (top, row) => (!top || row.current.utilisation > top.current.utilisation ? row : top),
    undefined,
  );
  const open = breaches.data?.length ?? 0;
  return (
    <SimpleGrid cols={{ base: 1, xs: 2, lg: 4 }} spacing="md" data-testid="risk-kpis">
      <KpiCard
        label={t('risk.kpi.inBreach')}
        value={inBreach}
        loading={limits.isPending}
        trend={inBreach > 0 ? t('risk.kpi.inBreachHint') : t('risk.kpi.withinLimits')}
        trendTone={inBreach > 0 ? 'down' : 'up'}
      />
      <KpiCard
        label={t('risk.kpi.warning')}
        value={warning}
        loading={limits.isPending}
        trend={t('risk.kpi.warningHint')}
        trendTone={warning > 0 ? 'warning' : 'neutral'}
      />
      <KpiCard
        label={t('risk.kpi.highest')}
        value={highest ? formatDecimal(highest.current.utilisation, 0, 1) : '—'}
        unit={highest ? '%' : undefined}
        loading={limits.isPending}
        trend={
          highest
            ? `${t(`risk.types.${highest.limit.type}`)} · ${t('risk.kpi.measuredOf', { count: measured.length, total: active.length })}`
            : t('risk.notMeasured')
        }
        trendTone={
          highest
            ? highest.current.status === 'OK'
              ? 'up'
              : highest.current.status === 'WARNING'
                ? 'warning'
                : 'down'
            : 'neutral'
        }
        to={highest ? `/risk/limits/${highest.limit.id}` : undefined}
      />
      <KpiCard
        label={t('risk.kpi.openBreaches')}
        value={open}
        loading={breaches.isPending}
        trend={open > 0 ? t('risk.kpi.review') : t('risk.kpi.noBreaches')}
        trendTone={open > 0 ? 'down' : 'up'}
        to="/risk?tab=breaches"
      />
    </SimpleGrid>
  );
}

/** A limit that could not be measured counts as a breach; a measurement that left something out says what. */
export function MeasurementFlags({
  value,
}: {
  value: Pick<Utilisation, 'status' | 'incomplete' | 'leftOutCurrencies' | 'incompleteReasons'>;
}) {
  const { t } = useTranslation();
  return (
    <>
      {value.status === 'NOT_MEASURED' && (
        <StatusBadge
          status="NOT_MEASURED"
          label={t('risk.statusNotMeasured')}
          size="xs"
          data-testid="limit-not-measured"
        />
      )}
      {value.incomplete && (
        <Tooltip label={t('risk.incompleteHint', { reasons: value.incompleteReasons.join('; ') })} multiline maw={320}>
          <Badge color="orange" variant="light" size="xs" tt="none" data-testid="measurement-incomplete">
            {t('risk.incomplete', { currencies: value.leftOutCurrencies.join(', ') })}
          </Badge>
        </Tooltip>
      )}
    </>
  );
}

export function UtilisationCell({ value }: { value: Utilisation | undefined }) {
  const { t } = useTranslation();
  if (!value) {
    return (
      <Text size="xs" c="dimmed">
        {t('risk.notMeasured')}
      </Text>
    );
  }
  return (
    <Group gap="xs" wrap="nowrap">
      <Progress value={utilisationBar(value.utilisation)} color={utilisationColor(value.status)} w={90} />
      <Text size="xs" c={utilisationColor(value.status)}>
        {formatDecimal(value.utilisation, 0, 1)} %
      </Text>
      <MeasurementFlags value={value} />
    </Group>
  );
}

/** The latest measurement of each limit; a limit never measured answers 404 and shows as such. */
function useUtilisations(limits: Limit[]): Map<string, Utilisation> {
  const results = useQueries({
    queries: limits.map((limit) =>
      $api.queryOptions(
        'get',
        '/api/v1/risk/limits/{id}/utilisation',
        { params: { path: { id: limit.id } } },
        { retry: false },
      ),
    ),
  });
  return new Map(
    results.flatMap((result, index) => {
      const limit = limits[index];
      return result.data && limit ? [[limit.id, result.data] as const] : [];
    }),
  );
}

function LimitsTab({ entityId }: { entityId: string }) {
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

function money(field: keyof Exposure, header: string): ColDef<Exposure> {
  return {
    field,
    headerName: header,
    width: 140,
    type: 'rightAligned',
    valueFormatter: (p) => formatDecimal(p.value as number, 2, 2),
  };
}

function ExposuresTab({ entityId }: { entityId: string }) {
  const { t } = useTranslation();
  const counterparties = useCounterparties();
  const exposures = $api.useQuery('get', '/api/v1/risk/exposures', { params: { query: { legalEntityId: entityId } } });
  const columns = useMemo<ColDef<Exposure>[]>(
    () => [
      {
        field: 'counterpartyId',
        headerName: t('trades.columns.counterparty'),
        flex: 1,
        minWidth: 200,
        valueFormatter: (p) => counterparties.byId.get(String(p.value))?.name ?? String(p.value),
      },
      money('mtm', t('risk.mtm')),
      money('receivables', t('risk.receivables')),
      money('payablesNetted', t('risk.payablesNetted')),
      money('pfe', t('risk.pfe')),
      money('collateral', t('risk.collateral')),
      { ...money('exposure', t('risk.exposure')), cellStyle: { fontWeight: 600 } },
      { field: 'currency', headerName: t('trades.form.currency'), width: 100 },
      {
        colId: 'incomplete',
        headerName: t('risk.incompleteColumn'),
        width: 150,
        valueGetter: (p) =>
          p.data?.incomplete ? p.data.leftOutCurrencies.join(', ') || t('risk.incomplete', { currencies: '' }) : '',
        tooltipValueGetter: (p) => p.data?.incompleteReasons.join('; '),
      },
      {
        field: 'measuredAt',
        headerName: t('risk.measuredAt'),
        width: 170,
        valueFormatter: (p) => formatDateTime(p.value as string),
      },
    ],
    [t, counterparties.byId],
  );
  const chart = (exposures.data ?? []).map((exposure) => ({
    counterparty: counterparties.byId.get(exposure.counterpartyId)?.code ?? exposure.counterpartyId.slice(0, 8),
    exposure: exposure.exposure,
    collateral: exposure.collateral,
  }));
  return (
    <QueryState isPending={exposures.isPending} error={exposures.error}>
      {(exposures.data ?? []).length === 0 ? (
        <Alert color="gray">{t('risk.noExposures')}</Alert>
      ) : (
        <Stack>
          <Card withBorder>
            <BarChart
              h={220}
              data={chart}
              dataKey="counterparty"
              series={[
                { name: 'exposure', label: t('risk.exposure'), color: 'orange.6' },
                { name: 'collateral', label: t('risk.collateral'), color: 'teal.6' },
              ]}
              tickLine="y"
            />
          </Card>
          <DataGrid<Exposure>
            rowData={exposures.data ?? []}
            columnDefs={columns}
            rowId={(row) => row.counterpartyId}
            height={320}
          />
        </Stack>
      )}
    </QueryState>
  );
}

interface BreachRow extends Breach {
  limit?: Limit;
  scopeText: string;
}

function BreachesTab({ entityId }: { entityId: string }) {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const names = useScopeNames(entityId);
  const [openOnly, setOpenOnly] = useState(true);
  const breaches = $api.useQuery('get', '/api/v1/risk/breaches', {
    params: { query: { legalEntityId: entityId, openOnly } },
  });
  const limits = $api.useQuery('get', '/api/v1/risk/limits', {
    params: { query: { legalEntityId: entityId, activeOnly: false } },
  });
  const rows = useMemo<BreachRow[]>(() => {
    const byId = new Map((limits.data ?? []).map((limit) => [limit.id, limit]));
    return (breaches.data ?? []).map((breach) => {
      const limit = byId.get(breach.limitId);
      return { ...breach, limit, scopeText: limit ? scopeLabel(limit.scope, names) : '' };
    });
  }, [breaches.data, limits.data, names]);
  const columns = useMemo<ColDef<BreachRow>[]>(
    () => [
      {
        field: 'openedAt',
        headerName: t('risk.openedAt'),
        width: 170,
        sort: 'desc',
        valueFormatter: (p) => formatDateTime(p.value as string),
      },
      {
        colId: 'type',
        headerName: t('risk.limitType'),
        width: 190,
        valueGetter: (p) => (p.data?.limit ? t(`risk.types.${p.data.limit.type}`) : ''),
      },
      { field: 'scopeText', headerName: t('risk.scope'), flex: 1, minWidth: 180 },
      {
        colId: 'limitAmount',
        headerName: t('risk.amount'),
        width: 150,
        type: 'rightAligned',
        valueGetter: (p) =>
          p.data?.limit ? `${formatDecimal(p.data.limit.amount, 0, 2)} ${limitUnit(p.data.limit)}` : '',
      },
      {
        field: 'firstValue',
        headerName: t('risk.firstValue'),
        width: 130,
        type: 'rightAligned',
        valueFormatter: (p) => formatDecimal(p.value as number, 0, 2),
      },
      {
        field: 'peakValue',
        headerName: t('risk.peakValue'),
        width: 130,
        type: 'rightAligned',
        valueFormatter: (p) => formatDecimal(p.value as number, 0, 2),
      },
      {
        field: 'status',
        headerName: t('allocation.status'),
        width: 110,
        cellRenderer: (p: ICellRendererParams<BreachRow>) => p.data && <BreachBadge status={p.data.status} />,
      },
      {
        field: 'closedAt',
        headerName: t('risk.closedAt'),
        width: 170,
        valueFormatter: (p) => formatDateTime(p.value as string | null),
      },
    ],
    [t],
  );
  return (
    <Stack>
      <Switch label={t('risk.openOnly')} checked={openOnly} onChange={(e) => setOpenOnly(e.currentTarget.checked)} />
      <QueryState isPending={breaches.isPending} error={breaches.error}>
        <DataGrid<BreachRow>
          rowData={rows}
          columnDefs={columns}
          rowId={(row) => row.id}
          height={400}
          onRowClicked={(event) => {
            if (event.data) void navigate(`/risk/limits/${event.data.limitId}`);
          }}
          overlayNoRowsTemplate={t('risk.noBreaches')}
        />
      </QueryState>
    </Stack>
  );
}

function RequestCreditSupportModal({
  onClose,
  entityId,
  counterpartyId,
}: {
  onClose: () => void;
  entityId: string;
  counterpartyId: string;
}) {
  const { t } = useTranslation();
  const invalidate = useInvalidateRisk();
  const [kind, setKind] = useState<CreditSupportKind>('GUARANTEE_RECEIVED');
  const [amount, setAmount] = useState('');
  const [currency, setCurrency] = useState('EUR');
  const [validFrom, setValidFrom] = useState(dayjs().format('YYYY-MM-DD'));
  const [validTo, setValidTo] = useState('');
  const [reference, setReference] = useState('');
  const request = $api.useMutation('post', '/api/v1/risk/credit-support', {
    meta: { notify: false },
    onSuccess: () => {
      notifySuccess(t('risk.creditSupportRequested'));
      void invalidate();
      onClose();
    },
  });
  return (
    <Modal opened onClose={onClose} title={t('risk.requestCreditSupport')}>
      <Stack>
        <ProblemAlert error={request.error} />
        <Text size="sm" c="dimmed">
          {t('risk.fourEyesHint')}
        </Text>
        <Select
          label={t('risk.kind')}
          data={CREDIT_SUPPORT_KINDS.map((value) => ({ value, label: t(`risk.creditKinds.${value}`) }))}
          value={kind}
          allowDeselect={false}
          onChange={(value) => value && setKind(value)}
        />
        <Group grow>
          <TextInput
            label={t('risk.amount')}
            inputMode="decimal"
            value={amount}
            onChange={(e) => setAmount(e.currentTarget.value)}
            required
          />
          <TextInput
            label={t('trades.form.currency')}
            value={currency}
            maxLength={3}
            onChange={(e) => setCurrency(e.currentTarget.value.toUpperCase())}
          />
        </Group>
        <Group grow>
          <TextInput
            type="date"
            label={t('risk.validFrom')}
            value={validFrom}
            onChange={(e) => setValidFrom(e.currentTarget.value)}
          />
          <TextInput
            type="date"
            label={t('risk.validTo')}
            value={validTo}
            onChange={(e) => setValidTo(e.currentTarget.value)}
          />
        </Group>
        <TextInput
          label={t('risk.reference')}
          value={reference}
          onChange={(e) => setReference(e.currentTarget.value)}
        />
        <Button
          disabled={!amount.trim()}
          loading={request.isPending}
          onClick={() =>
            request.mutate({
              body: {
                legalEntityId: entityId,
                counterpartyId,
                kind,
                amount: decimal(amount),
                currency,
                validFrom,
                validTo: validTo || undefined,
                reference: reference || undefined,
              },
            })
          }
        >
          {t('risk.sendForApproval')}
        </Button>
      </Stack>
    </Modal>
  );
}

function CreditSupportTab({ entityId }: { entityId: string }) {
  const { t } = useTranslation();
  const counterparties = useCounterparties();
  const [picked, setPicked] = useState<string | null>(null);
  const [requesting, setRequesting] = useState(false);
  const counterpartyId = picked ?? counterparties.list[0]?.id ?? null;
  const support = $api.useQuery(
    'get',
    '/api/v1/risk/credit-support',
    { params: { query: { counterpartyId: counterpartyId ?? '' } } },
    { enabled: counterpartyId !== null },
  );
  const rows = (support.data ?? []).filter((row) => row.legalEntityId == null || row.legalEntityId === entityId);
  return (
    <Stack>
      <Group justify="space-between" align="flex-end">
        <Select
          label={t('trades.columns.counterparty')}
          data={counterparties.list.map((c) => ({ value: c.id, label: `${c.code} · ${c.name}` }))}
          value={counterpartyId}
          onChange={setPicked}
          searchable
          w={360}
          data-testid="credit-support-counterparty"
        />
        {counterpartyId && (
          <Can op="risk_requestCreditSupport">
            <Button leftSection={<IconPlus size={16} />} onClick={() => setRequesting(true)}>
              {t('risk.requestCreditSupport')}
            </Button>
          </Can>
        )}
      </Group>
      <QueryState isPending={counterpartyId !== null && support.isPending} error={support.error}>
        {rows.length === 0 ? (
          <Alert color="gray">{t('risk.noCreditSupport')}</Alert>
        ) : (
          <Table withTableBorder striped>
            <Table.Thead>
              <Table.Tr>
                <Table.Th>{t('risk.kind')}</Table.Th>
                <Table.Th ta="right">{t('risk.amount')}</Table.Th>
                <Table.Th>{t('risk.validFrom')}</Table.Th>
                <Table.Th>{t('risk.validTo')}</Table.Th>
                <Table.Th>{t('risk.reference')}</Table.Th>
                <Table.Th>{t('allocation.status')}</Table.Th>
                <Table.Th>{t('risk.requestedBy')}</Table.Th>
                <Table.Th>{t('curves.signedOffBy')}</Table.Th>
              </Table.Tr>
            </Table.Thead>
            <Table.Tbody>
              {rows.map((row) => (
                <Table.Tr key={row.id}>
                  <Table.Td>{t(`risk.creditKinds.${row.kind}`)}</Table.Td>
                  <Table.Td ta="right">{formatMoney(row.amount, row.currency)}</Table.Td>
                  <Table.Td>{formatDate(row.validFrom)}</Table.Td>
                  <Table.Td>{formatDate(row.validTo)}</Table.Td>
                  <Table.Td>{row.reference}</Table.Td>
                  <Table.Td>
                    <StatusBadge status={row.status} />
                  </Table.Td>
                  <Table.Td>{row.requestedBy}</Table.Td>
                  <Table.Td>{row.approvedBy}</Table.Td>
                </Table.Tr>
              ))}
            </Table.Tbody>
          </Table>
        )}
      </QueryState>
      {requesting && counterpartyId && (
        <RequestCreditSupportModal
          onClose={() => setRequesting(false)}
          entityId={entityId}
          counterpartyId={counterpartyId}
        />
      )}
    </Stack>
  );
}

function PfeTab() {
  const { t } = useTranslation();
  const addOns = $api.useQuery('get', '/api/v1/risk/pfe-add-ons');
  return (
    <QueryState isPending={addOns.isPending} error={addOns.error}>
      <Stack>
        <Text size="sm" c="dimmed">
          {t('risk.pfeHint')}
        </Text>
        <Table withTableBorder striped maw={640}>
          <Table.Thead>
            <Table.Tr>
              <Table.Th>{t('risk.productFamily')}</Table.Th>
              <Table.Th ta="right">{t('risk.maxTenor')}</Table.Th>
              <Table.Th ta="right">{t('risk.addOnPct')}</Table.Th>
              <Table.Th>{t('risk.calibrated')}</Table.Th>
            </Table.Tr>
          </Table.Thead>
          <Table.Tbody>
            {(addOns.data ?? []).map((row) => (
              <Table.Tr key={`${row.productFamily}-${row.maxTenorMonths}`}>
                <Table.Td>{t(`trades.families.${row.productFamily}`)}</Table.Td>
                <Table.Td ta="right">{row.maxTenorMonths >= 9999 ? t('risk.longer') : row.maxTenorMonths}</Table.Td>
                <Table.Td ta="right">{formatDecimal(row.pct, 0, 2)} %</Table.Td>
                <Table.Td>
                  {row.calibrated ? t('common.yes') : <StatusBadge status="WARNING" label={t('risk.notCalibrated')} />}
                </Table.Td>
              </Table.Tr>
            ))}
          </Table.Tbody>
        </Table>
      </Stack>
    </QueryState>
  );
}

const TABS = ['limits', 'exposures', 'breaches', 'credit-support', 'pfe'] as const;

/** Limits and their utilisation, counterparty exposure, breaches, credit support and PFE add-ons of an entity. */
export function RiskPage() {
  const { t } = useTranslation();
  const { entity } = useEntity();
  const [params, setParams] = useSearchParams();
  const requested = params.get('tab');
  const tab = TABS.find((value) => value === requested) ?? 'limits';
  return (
    <Stack>
      <PageHeader title={t('risk.title')} description={t('risk.description', { entity: entity?.code ?? '' })} />
      {!entity ? (
        <Alert color="gray">{t('header.noEntity')}</Alert>
      ) : (
        <>
          <RiskKpis entityId={entity.id} />
          <Tabs
            value={tab}
            onChange={(value) => setParams(value && value !== 'limits' ? { tab: value } : {}, { replace: true })}
            keepMounted={false}
          >
            <Tabs.List>
              <Tabs.Tab value="limits">{t('risk.tabs.limits')}</Tabs.Tab>
              <Tabs.Tab value="exposures">{t('risk.tabs.exposures')}</Tabs.Tab>
              <Tabs.Tab value="breaches">{t('risk.tabs.breaches')}</Tabs.Tab>
              <Tabs.Tab value="credit-support">{t('risk.tabs.creditSupport')}</Tabs.Tab>
              <Tabs.Tab value="pfe">{t('risk.tabs.pfe')}</Tabs.Tab>
            </Tabs.List>
            <Tabs.Panel value="limits" pt="md">
              <LimitsTab entityId={entity.id} />
            </Tabs.Panel>
            <Tabs.Panel value="exposures" pt="md">
              <ExposuresTab entityId={entity.id} />
            </Tabs.Panel>
            <Tabs.Panel value="breaches" pt="md">
              <BreachesTab entityId={entity.id} />
            </Tabs.Panel>
            <Tabs.Panel value="credit-support" pt="md">
              <CreditSupportTab entityId={entity.id} />
            </Tabs.Panel>
            <Tabs.Panel value="pfe" pt="md">
              <PfeTab />
            </Tabs.Panel>
          </Tabs>
        </>
      )}
    </Stack>
  );
}
