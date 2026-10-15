import { useUrlState, useUrlTab } from '@/shared/useUrlState';
import {
  ActionIcon,
  Alert,
  Badge,
  Button,
  Card,
  Grid,
  Group,
  NavLink,
  NumberInput,
  Stack,
  Table,
  Tabs,
  Text,
  TextInput,
  Title,
} from '@mantine/core';
import { IconAlertTriangle, IconDownload, IconPlayerPlay, IconReportAnalytics } from '@tabler/icons-react';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import type { ColDef, ICellRendererParams } from 'ag-grid-community';
import dayjs from 'dayjs';
import { useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { $api, authorisedFetch } from '@/shared/api/client';
import { download } from '@/shared/api/download';
import type { Schema } from '@/shared/api/types';
import { Can, useCan } from '@/shared/auth/Can';
import { useSession } from '@/shared/auth/SessionContext';
import { EmptyState } from '@/shared/components/EmptyState';
import { notifySuccess } from '@/shared/components/notify';
import { PageHeader } from '@/shared/components/PageHeader';
import { ProblemAlert } from '@/shared/components/ProblemAlert';
import { QueryState } from '@/shared/components/QueryState';
import { ReasonModal } from '@/shared/components/ReasonModal';
import { useEntity } from '@/shared/context/EntityContext';
import { formatDateTime } from '@/shared/format/format';
import { DataGrid } from '@/shared/grid/DataGrid';
import { previousPeriod } from '@/features/monthend/model/glBalances';
import {
  missingParameters,
  parameterInput,
  reportQuery,
  runnableReports,
  type ReportDefinition,
} from './model/parameters';
import { canDownloadExport, errorReportableEvents, exportFileName, type EmirEvent } from './model/exports';

type ReportExport = Schema<'ReportExportView'>;
type Row = Record<string, unknown>;

interface ReportRun {
  columns: string[];
  rows: Row[];
  seq: string | null;
  hash: string | null;
}

function cellText(value: unknown): string {
  if (value == null) return '';
  if (typeof value === 'string') return value;
  if (typeof value === 'number' || typeof value === 'boolean') return value.toString();
  return JSON.stringify(value);
}

function ReportRunner({ definition, entityId }: { definition: ReportDefinition; entityId: string | undefined }) {
  const { t } = useTranslation();
  const [values, setValues] = useState<Record<string, string>>(() =>
    Object.fromEntries(
      definition.parameters
        .filter((parameter) => parameterInput(parameter) === 'month')
        .map((parameter) => [parameter.name, previousPeriod(new Date())]),
    ),
  );
  const missing = missingParameters(definition, values, entityId);
  const run = useMutation({
    meta: { notify: false },
    mutationFn: async (): Promise<ReportRun> => {
      const response = await authorisedFetch(reportQuery(definition, values, entityId, 'json'));
      const body = (await response.json()) as { columns: string[]; rows: Row[] };
      return {
        ...body,
        seq: response.headers.get('X-Report-Seq'),
        hash: response.headers.get('X-Content-Sha256'),
      };
    },
  });
  const csv = useMutation({
    meta: { notify: false },
    mutationFn: () =>
      download(reportQuery(definition, values, entityId, 'csv'), `${definition.code.toLowerCase()}.csv`),
    onSuccess: (response) =>
      notifySuccess(t('reports.downloaded', { seq: response.headers.get('X-Report-Seq') ?? '' })),
  });
  const columns = useMemo<ColDef<Row>[]>(
    () =>
      (run.data?.columns ?? []).map((column) => ({
        colId: column,
        headerName: column,
        valueGetter: (p) => cellText(p.data?.[column]),
        minWidth: 120,
        flex: 1,
      })),
    [run.data],
  );
  return (
    <Stack>
      <Card withBorder radius="lg" p="lg">
        <Title order={3} size="h4">
          {definition.title}
        </Title>
        <Text size="sm" c="dimmed" mb="sm">
          {definition.description}
        </Text>
        <Group align="flex-end">
          {definition.parameters
            .filter((parameter) => parameterInput(parameter) !== 'entity')
            .map((parameter) => {
              const input = parameterInput(parameter);
              const common = {
                label: parameter.description,
                required: parameter.required,
                w: 200,
              };
              const value = values[parameter.name] ?? '';
              const set = (next: string) => setValues({ ...values, [parameter.name]: next });
              return input === 'int' ? (
                <NumberInput
                  key={parameter.name}
                  {...common}
                  value={value}
                  onChange={(v) => set(v === '' ? '' : String(v))}
                  allowDecimal={false}
                />
              ) : (
                <TextInput
                  key={parameter.name}
                  {...common}
                  type={input === 'date' ? 'date' : input === 'month' ? 'month' : 'text'}
                  value={value}
                  onChange={(e) => set(e.currentTarget.value)}
                  data-testid={`param-${parameter.name}`}
                />
              );
            })}
          <Button
            leftSection={<IconPlayerPlay size={16} />}
            disabled={missing.length > 0}
            loading={run.isPending}
            onClick={() => run.mutate()}
            data-testid="run-report"
          >
            {t('reports.run')}
          </Button>
          <Button
            variant="default"
            leftSection={<IconDownload size={16} />}
            disabled={missing.length > 0}
            loading={csv.isPending}
            onClick={() => csv.mutate()}
          >
            {t('reports.csv')}
          </Button>
        </Group>
      </Card>
      <ProblemAlert error={run.error ?? csv.error} />
      {!run.data && !run.isPending && (
        <EmptyState
          icon={<IconReportAnalytics size={22} />}
          title={t('reports.notRun')}
          description={t('reports.notRunHint')}
          testId="report-not-run"
        />
      )}
      {run.data && (
        <Stack gap="xs">
          <Text size="sm" c="dimmed" data-testid="report-run">
            {t('reports.runInfo', { seq: run.data.seq ?? '–', count: run.data.rows.length })}{' '}
            <Text span ff="monospace" size="xs">
              {run.data.hash?.slice(0, 16)}
            </Text>
          </Text>
          <DataGrid<Row>
            rowData={run.data.rows}
            columnDefs={columns}
            height={440}
            overlayNoRowsTemplate={t('common.empty')}
          />
        </Stack>
      )}
    </Stack>
  );
}

function Catalogue({ entityId }: { entityId: string | undefined }) {
  const { t } = useTranslation();
  const { me } = useSession();
  const { params, reset: resetUrl } = useUrlState();
  const catalogue = $api.useQuery('get', '/api/v1/reports', {}, { staleTime: 5 * 60_000 });
  const reports = runnableReports(catalogue.data ?? [], me.roles);
  const selected = reports.find((report) => report.code === params.get('report')) ?? reports[0];
  return (
    <QueryState isPending={catalogue.isPending} error={catalogue.error}>
      {reports.length === 0 ? (
        <Alert color="gray">{t('reports.none')}</Alert>
      ) : (
        <Grid>
          <Grid.Col span={{ base: 12, md: 3 }}>
            <Card withBorder radius="lg" p={6}>
              <Stack gap={2}>
                {reports.map((report) => (
                  <NavLink
                    key={report.code}
                    label={report.title}
                    active={report.code === selected?.code}
                    onClick={() => resetUrl({ report: report.code })}
                    style={{ borderRadius: 8 }}
                  />
                ))}
              </Stack>
            </Card>
          </Grid.Col>
          <Grid.Col span={{ base: 12, md: 9 }}>
            {selected && <ReportRunner key={selected.code} definition={selected} entityId={entityId} />}
          </Grid.Col>
        </Grid>
      )}
    </QueryState>
  );
}

function DownloadExport({ reportExport }: { reportExport: ReportExport }) {
  const { t } = useTranslation();
  const again = useMutation({
    mutationFn: () => download(`/api/v1/reports/exports/${reportExport.id}`, exportFileName(reportExport)),
    onSuccess: () => notifySuccess(t('reports.downloadedAgain', { seq: reportExport.seq })),
  });
  return (
    <ActionIcon
      variant="subtle"
      aria-label={t('reports.downloadAgain')}
      title={t('reports.downloadAgain')}
      loading={again.isPending}
      onClick={() => again.mutate()}
      data-testid={`export-download-${reportExport.id}`}
    >
      <IconDownload size={16} />
    </ActionIcon>
  );
}

function ExportLog({ entityId }: { entityId: string | undefined }) {
  const { t } = useTranslation();
  const { me } = useSession();
  const exports = $api.useQuery('get', '/api/v1/reports/exports', { params: { query: { legalEntityId: entityId } } });
  const catalogue = $api.useQuery('get', '/api/v1/reports', {}, { staleTime: 5 * 60_000 });
  const runnable = useMemo(() => runnableReports(catalogue.data ?? [], me.roles), [catalogue.data, me.roles]);
  const columns = useMemo<ColDef<ReportExport>[]>(
    () => [
      {
        colId: 'download',
        headerName: '',
        width: 64,
        minWidth: 64,
        sortable: false,
        filter: false,
        cellRenderer: (p: ICellRendererParams<ReportExport>) =>
          p.data && canDownloadExport(p.data, runnable) ? <DownloadExport reportExport={p.data} /> : null,
      },
      {
        field: 'exportedAt',
        headerName: t('reports.exportedAt'),
        width: 170,
        sort: 'desc',
        valueFormatter: (p) => formatDateTime(p.value as string),
      },
      { field: 'reportCode', headerName: t('reports.report'), width: 220 },
      { field: 'seq', headerName: '#', width: 80 },
      { field: 'format', headerName: t('marketData.format'), width: 90 },
      { field: 'rowCount', headerName: t('reports.rows'), width: 90, type: 'rightAligned' },
      { field: 'exportedBy', headerName: t('reports.exportedBy'), width: 130 },
      {
        colId: 'parameters',
        headerName: t('reports.parameters'),
        flex: 1,
        minWidth: 200,
        valueGetter: (p) =>
          Object.entries(p.data?.parameters ?? {})
            .filter(([key]) => key !== 'legalEntityId')
            .map(([key, value]) => `${key}=${value}`)
            .join(', '),
      },
      {
        field: 'contentHash',
        headerName: t('reports.hash'),
        width: 160,
        valueFormatter: (p) => String(p.value).slice(0, 16),
      },
    ],
    [t, runnable],
  );
  return (
    <QueryState isPending={exports.isPending} error={exports.error}>
      <DataGrid<ReportExport>
        rowData={exports.data ?? []}
        columnDefs={columns}
        rowId={(row) => row.id}
        height={520}
        overlayNoRowsTemplate={t('common.empty')}
      />
    </QueryState>
  );
}

function Emir({ entityId }: { entityId: string }) {
  const { t } = useTranslation();
  const [from, setFrom] = useState(dayjs().subtract(3, 'month').format('YYYY-MM-DD'));
  const [to, setTo] = useState(dayjs().format('YYYY-MM-DD'));
  const events = $api.useQuery('get', '/api/v1/reports/emir/events', {
    params: { query: { legalEntityId: entityId, from, to } },
  });
  const completeness = $api.useQuery('get', '/api/v1/reports/emir/completeness', {
    params: { query: { legalEntityId: entityId } },
  });
  const rules = $api.useQuery('get', '/api/v1/reports/emir/field-rules');
  const [reporting, setReporting] = useState<EmirEvent | null>(null);
  const queryClient = useQueryClient();
  const reportError = $api.useMutation('post', '/api/v1/reports/emir/trades/{tradeId}/error', {
    meta: { notify: false },
    onSuccess: (recorded) => {
      notifySuccess(t('reports.errorRecorded', { trade: recorded.tradeNumber }));
      setReporting(null);
      void queryClient.invalidateQueries({
        predicate: (q) => typeof q.queryKey[1] === 'string' && q.queryKey[1].startsWith('/api/v1/reports'),
      });
    },
  });
  const reportable = errorReportableEvents(events.data ?? []);
  const incomplete = (completeness.data ?? []).filter((row) => !row.complete);
  const unverified = (rules.data ?? []).filter((rule) => !rule.verified).length;
  return (
    <Stack>
      {unverified > 0 && <Alert color="orange">{t('reports.emirUnverified', { count: unverified })}</Alert>}
      <Card withBorder>
        <Title order={5} mb="sm">
          {t('reports.completeness')}
        </Title>
        <QueryState isPending={completeness.isPending} error={completeness.error}>
          {incomplete.length === 0 ? (
            <Text size="sm">{t('reports.allComplete', { count: (completeness.data ?? []).length })}</Text>
          ) : (
            <Table striped>
              <Table.Tbody>
                {incomplete.map((row) => (
                  <Table.Tr key={row.tradeId}>
                    <Table.Td>{row.tradeNumber}</Table.Td>
                    <Table.Td>{row.latestAction}</Table.Td>
                    <Table.Td>{row.missingFields.join(', ')}</Table.Td>
                  </Table.Tr>
                ))}
              </Table.Tbody>
            </Table>
          )}
        </QueryState>
      </Card>
      <Group align="flex-end">
        <TextInput type="date" label={t('trades.from')} value={from} onChange={(e) => setFrom(e.currentTarget.value)} />
        <TextInput type="date" label={t('trades.to')} value={to} onChange={(e) => setTo(e.currentTarget.value)} />
      </Group>
      <QueryState isPending={events.isPending} error={events.error}>
        <Table striped withTableBorder>
          <Table.Thead>
            <Table.Tr>
              <Table.Th>{t('reports.eventDate')}</Table.Th>
              <Table.Th>{t('reports.action')}</Table.Th>
              <Table.Th>{t('trades.columns.number')}</Table.Th>
              <Table.Th>{t('trades.version')}</Table.Th>
              <Table.Th>UTI</Table.Th>
              <Table.Th>{t('reports.exportedBy')}</Table.Th>
              <Table.Th />
            </Table.Tr>
          </Table.Thead>
          <Table.Tbody>
            {(events.data ?? []).map((event) => (
              <Table.Tr key={event.id} data-testid={`emir-event-${event.tradeNumber}-${event.action}`}>
                <Table.Td>{event.eventDate}</Table.Td>
                <Table.Td>
                  <Badge variant="light">{event.action}</Badge>
                </Table.Td>
                <Table.Td>{event.tradeNumber}</Table.Td>
                <Table.Td>{event.tradeVersion}</Table.Td>
                <Table.Td>{event.uti ?? '–'}</Table.Td>
                <Table.Td>{event.recordedBy}</Table.Td>
                <Table.Td>
                  {reportable.has(event.id) && (
                    <Can op="reporting_reportError">
                      <Button
                        size="compact-xs"
                        variant="subtle"
                        color="red"
                        leftSection={<IconAlertTriangle size={12} />}
                        onClick={() => setReporting(event)}
                        data-testid={`report-error-${event.tradeNumber}`}
                      >
                        {t('reports.reportError')}
                      </Button>
                    </Can>
                  )}
                </Table.Td>
              </Table.Tr>
            ))}
          </Table.Tbody>
        </Table>
      </QueryState>
      {reporting && (
        <ReasonModal
          title={t('reports.reportErrorTitle', { trade: reporting.tradeNumber })}
          hint={t('reports.reportErrorHint')}
          action={t('reports.reportError')}
          pending={reportError.isPending}
          error={reportError.error}
          onSubmit={(reason) =>
            reportError.mutate({ params: { path: { tradeId: reporting.tradeId } }, body: { reason } })
          }
          onClose={() => {
            reportError.reset();
            setReporting(null);
          }}
        />
      )}
    </Stack>
  );
}

/** The report catalogue for the user's roles, every run logged with its number and hash, and EMIR status. */
export function ReportsPage() {
  const { t } = useTranslation();
  const { entity } = useEntity();
  const canSeeLog = useCan('reporting_exports');
  const canSeeEmir = useCan('reporting_emirFieldRules');
  const [tab, setTab] = useUrlTab(['catalogue', 'log', 'emir'] as const, 'catalogue');
  return (
    <Stack>
      <PageHeader title={t('reports.title')} description={t('reports.description', { entity: entity?.code ?? '' })} />
      <Tabs value={tab} onChange={(value) => setTab(value)} keepMounted={false}>
        <Tabs.List>
          <Tabs.Tab value="catalogue">{t('reports.tabs.catalogue')}</Tabs.Tab>
          {canSeeLog && <Tabs.Tab value="log">{t('reports.tabs.log')}</Tabs.Tab>}
          {canSeeEmir && entity && <Tabs.Tab value="emir">{t('reports.tabs.emir')}</Tabs.Tab>}
        </Tabs.List>
        <Tabs.Panel value="catalogue" pt="md">
          <Catalogue entityId={entity?.id} />
        </Tabs.Panel>
        {canSeeLog && (
          <Tabs.Panel value="log" pt="md">
            <ExportLog entityId={entity?.id} />
          </Tabs.Panel>
        )}
        {canSeeEmir && entity && (
          <Tabs.Panel value="emir" pt="md">
            <Emir entityId={entity.id} />
          </Tabs.Panel>
        )}
      </Tabs>
    </Stack>
  );
}
