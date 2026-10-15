import { useUrlState } from '@/shared/useUrlState';
import { BarChart } from '@mantine/charts';
import {
  Alert,
  Anchor,
  Button,
  Grid,
  Group,
  Modal,
  NumberInput,
  Paper,
  Select,
  SimpleGrid,
  Stack,
  Table,
  Text,
  TextInput,
  Timeline,
  Title,
} from '@mantine/core';
import { IconBuildingFactory2, IconCalendar, IconCalendarDue, IconPlus } from '@tabler/icons-react';
import { useQueryClient } from '@tanstack/react-query';
import dayjs from 'dayjs';
import { useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link, useNavigate } from 'react-router';
import { token } from '@/app/tokens';
import { $api } from '@/shared/api/client';
import type { Schema } from '@/shared/api/types';
import { Can } from '@/shared/auth/Can';
import { EmptyState } from '@/shared/components/EmptyState';
import { ScrollTable } from '@/shared/components/ScrollTable';
import { KpiCard } from '@/shared/components/KpiCard';
import { notifySuccess } from '@/shared/components/notify';
import { PageHeader } from '@/shared/components/PageHeader';
import { ProblemAlert } from '@/shared/components/ProblemAlert';
import { QueryState } from '@/shared/components/QueryState';
import { TableSkeleton } from '@/shared/components/Skeletons';
import { StatusBadge, statusLabel } from '@/shared/components/StatusBadge';
import { useEntity } from '@/shared/context/EntityContext';
import { formatDate, formatDecimal } from '@/shared/format/format';
import { useEntityAccounts } from '@/features/trades/hooks';
import { yearOr } from '@/shared/urlParams';
import { InstallationsCard } from './InstallationsCard';
import { DEADLINE_TONES, deadlineCalendar, nextDeadline } from './model/deadlines';

type EntityCompliance = Schema<'EntityCompliance'>;

type ComplianceStatus = Schema<'InstallationCompliance'>['status'];
type InstallationKind = Schema<'InstallationView'>['kind'];

export function useInvalidateEts() {
  const queryClient = useQueryClient();
  return () =>
    queryClient.invalidateQueries({
      predicate: (q) =>
        typeof q.queryKey[1] === 'string' &&
        (q.queryKey[1].startsWith('/api/v1/ets') ||
          q.queryKey[1].startsWith('/api/v1/positions') ||
          q.queryKey[1].startsWith('/api/v1/workflow')),
    });
}

/** The tone of each compliance state: open in progress, verified on its way, compliant done, non-compliant a problem. */
const COMPLIANCE_TONE: Record<ComplianceStatus, string> = {
  OPEN: 'OPEN',
  VERIFIED: 'APPROVED',
  COMPLIANT: 'COMPLETED',
  NON_COMPLIANT: 'BREACH',
};

export function ComplianceStatusBadge({ status }: { status: ComplianceStatus }) {
  return <StatusBadge status={COMPLIANCE_TONE[status]} label={statusLabel(status)} data-testid="compliance-status" />;
}

/** A headline figure of a compliance year (a KPI card without a link). */
export function Figure({ label, value, trend }: { label: string; value: string; trend?: string }) {
  return <KpiCard label={label} value={value} trend={trend} />;
}

export const tonnes = (value: number | null | undefined) => (value == null ? '–' : `${formatDecimal(value)} t`);

function RegisterInstallationModal({
  onClose,
  entityId,
  country,
}: {
  onClose: () => void;
  entityId: string;
  country: string;
}) {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const invalidate = useInvalidateEts();
  const accounts = useEntityAccounts(entityId).filter((account) => account.registryCode === 'UNION_REGISTRY');
  const [values, setValues] = useState({
    kind: 'INSTALLATION' as InstallationKind,
    eutlId: '',
    name: '',
    country,
    activityCode: '',
    permitId: '',
    registryAccountId: '',
    validFrom: dayjs().startOf('year').format('YYYY-MM-DD'),
  });
  const register = $api.useMutation('post', '/api/v1/ets/installations', {
    meta: { notify: false },
    onSuccess: (installation) => {
      notifySuccess(t('ets.registered'));
      void invalidate();
      void navigate(`/ets/installations/${installation.id}`);
    },
  });
  const set = (patch: Partial<typeof values>) => setValues({ ...values, ...patch });
  return (
    <Modal opened onClose={onClose} title={t('ets.register')} size="lg">
      <Stack>
        <ProblemAlert error={register.error} />
        <Group grow>
          <Select
            label={t('ets.kind')}
            data={[
              { value: 'INSTALLATION', label: t('ets.kinds.INSTALLATION') },
              { value: 'AIRCRAFT_OPERATOR', label: t('ets.kinds.AIRCRAFT_OPERATOR') },
            ]}
            value={values.kind}
            allowDeselect={false}
            onChange={(kind) => kind && set({ kind: kind })}
          />
          <TextInput
            label={t('ets.eutlId')}
            value={values.eutlId}
            onChange={(e) => set({ eutlId: e.currentTarget.value })}
            required
          />
        </Group>
        <TextInput
          label={t('ets.name')}
          value={values.name}
          onChange={(e) => set({ name: e.currentTarget.value })}
          required
        />
        <Group grow>
          <TextInput
            label={t('ets.country')}
            value={values.country}
            maxLength={2}
            onChange={(e) => set({ country: e.currentTarget.value.toUpperCase() })}
          />
          <TextInput
            label={t('ets.activityCode')}
            value={values.activityCode}
            onChange={(e) => set({ activityCode: e.currentTarget.value })}
          />
          <TextInput
            label={t('ets.permitId')}
            value={values.permitId}
            onChange={(e) => set({ permitId: e.currentTarget.value })}
          />
        </Group>
        <Select
          label={t('ets.account')}
          description={t('ets.accountHint')}
          data={accounts.map((account) => ({ value: account.id, label: `${account.accountNumber} · ${account.name}` }))}
          value={values.registryAccountId || null}
          onChange={(id) => set({ registryAccountId: id ?? '' })}
          required
        />
        <TextInput
          type="date"
          label={t('risk.validFrom')}
          value={values.validFrom}
          onChange={(e) => set({ validFrom: e.currentTarget.value })}
          w={220}
        />
        <Button
          disabled={!values.eutlId.trim() || !values.name.trim() || !values.registryAccountId}
          loading={register.isPending}
          onClick={() =>
            register.mutate({
              body: {
                ...values,
                legalEntityId: entityId,
                activityCode: values.activityCode || undefined,
                permitId: values.permitId || undefined,
              },
            })
          }
        >
          {t('ets.register')}
        </Button>
      </Stack>
    </Modal>
  );
}

function useDeadlines(entityId: string, year: number) {
  return $api.useQuery('get', '/api/v1/ets/deadlines', {
    params: { query: { legalEntityId: entityId, from: `${year}-01-01`, to: `${year + 1}-12-31` } },
  });
}

function Deadlines({ entityId, year }: { entityId: string; year: number }) {
  const { t } = useTranslation();
  const today = dayjs().format('YYYY-MM-DD');
  const deadlines = useDeadlines(entityId, year);
  const months = useMemo(() => deadlineCalendar(deadlines.data ?? [], today), [deadlines.data, today]);
  return (
    <Paper withBorder radius="lg" p="lg" h="100%">
      <Title order={3} size="h5" mb="md">
        {t('ets.deadlines')}
      </Title>
      <QueryState isPending={false} error={deadlines.error}>
        {deadlines.isPending ? (
          <TableSkeleton rows={3} />
        ) : months.length === 0 ? (
          <Text size="sm" c="dimmed">
            {t('ets.noDeadlines')}
          </Text>
        ) : (
          <Timeline bulletSize={22} lineWidth={2} data-testid="deadline-calendar">
            {months.map((month) => (
              <Timeline.Item
                key={month.month}
                bullet={<IconCalendarDue size={12} />}
                title={
                  <Text size="sm" fw={600}>
                    {dayjs(`${month.month}-01`).format('MMMM YYYY')}
                  </Text>
                }
              >
                <Stack gap={6} mt={6}>
                  {month.deadlines.map((deadline) => (
                    <Group
                      key={`${deadline.installationId}-${deadline.kind}-${deadline.complianceYear}`}
                      gap="xs"
                      wrap="nowrap"
                      align="flex-start"
                    >
                      <StatusBadge
                        status={DEADLINE_TONES[deadline.state]}
                        label={t(`ets.deadlineStates.${deadline.state}`)}
                        miw={92}
                      />
                      <Stack gap={0} miw={0}>
                        <Text size="sm">
                          {t(`ets.deadlineKinds.${deadline.kind}`, { year: deadline.complianceYear })}
                        </Text>
                        <Text size="xs" c="dimmed">
                          {formatDate(deadline.dueOn)} ·{' '}
                          <Anchor component={Link} to={`/ets/installations/${deadline.installationId}`} size="xs">
                            {deadline.installationName}
                          </Anchor>
                        </Text>
                      </Stack>
                    </Group>
                  ))}
                </Stack>
              </Timeline.Item>
            ))}
          </Timeline>
        )}
      </QueryState>
    </Paper>
  );
}

/**
 * The compliance position for the year: net allowances against the obligation, emissions, free allocation and the
 * next deadline. Figures are the server's (positions and compliance), never summed here.
 */
function EtsKpis({ entityId, year, compliance }: { entityId: string; year: number; compliance?: EntityCompliance }) {
  const { t } = useTranslation();
  const ladder = $api.useQuery('get', '/api/v1/positions/ets', {
    params: { query: { legalEntityId: entityId, complianceYear: year } },
  });
  const deadlines = useDeadlines(entityId, year);
  const next = nextDeadline(deadlines.data ?? [], dayjs().format('YYYY-MM-DD'));
  const net = ladder.data?.netPosition;
  return (
    <SimpleGrid cols={{ base: 1, xs: 2, lg: 4 }} spacing="md" data-testid="ets-kpis">
      <KpiCard
        label={t('ets.kpi.net')}
        value={net === undefined ? '—' : formatDecimal(net)}
        unit="t"
        loading={ladder.isPending}
        trend={
          ladder.data &&
          t('ets.kpi.netHint', {
            held: formatDecimal(ladder.data.heldAllowances),
            owed: formatDecimal(ladder.data.obligationOutstanding),
          })
        }
        trendTone={net === undefined ? 'neutral' : net < 0 ? 'down' : 'up'}
        to="/positions?tab=ets"
      />
      <KpiCard
        label={t('ets.estimated')}
        value={compliance ? formatDecimal(compliance.estimatedEmissions) : '—'}
        unit="t"
        loading={!compliance}
        trend={
          compliance?.verifiedEmissions == null
            ? t('ets.kpi.notVerified')
            : t('ets.kpi.verified', { tonnes: formatDecimal(compliance.verifiedEmissions) })
        }
      />
      <KpiCard
        label={t('positions.freeAllocation')}
        value={compliance ? formatDecimal(compliance.freeAllocationReceived) : '—'}
        unit="t"
        loading={!compliance}
        trend={compliance && t('ets.kpi.ofExpected', { tonnes: formatDecimal(compliance.freeAllocationExpected) })}
      />
      <KpiCard
        label={t('ets.kpi.next')}
        value={next ? formatDate(next.dueOn) : compliance ? formatDate(compliance.surrenderDeadline) : '—'}
        loading={deadlines.isPending}
        trend={
          next
            ? `${t(`ets.deadlineKinds.${next.kind}`, { year: next.complianceYear })} · ${t(`ets.deadlineStates.${next.state}`)}`
            : t('positions.deadline')
        }
        trendTone={next?.state === 'overdue' ? 'down' : next?.state === 'dueSoon' ? 'warning' : 'neutral'}
      />
    </SimpleGrid>
  );
}

/** Per installation: emissions to cover against allowances held and already surrendered (the server's figures). */
function ComplianceChart({ compliance }: { compliance: EntityCompliance }) {
  const { t } = useTranslation();
  const data = compliance.installations.map((line) => ({
    installation: line.installation.name,
    emissions: line.emissions,
    held: line.held,
    surrendered: line.surrendered,
  }));
  return (
    <Paper withBorder radius="lg" p="lg" h="100%">
      <Title order={3} size="h5">
        {t('ets.chartTitle')}
      </Title>
      <Text size="sm" c="dimmed" mb="md">
        {t('ets.chartHint')}
      </Text>
      <BarChart
        h={240}
        data={data}
        dataKey="installation"
        series={[
          { name: 'emissions', label: t('positions.emissions'), color: 'orange.6' },
          { name: 'held', label: t('positions.held'), color: 'brand.6' },
          { name: 'surrendered', label: t('positions.surrendered'), color: 'gray.5' },
        ]}
        valueFormatter={(value) => formatDecimal(value)}
        withLegend
        legendProps={{ verticalAlign: 'bottom' }}
        tickLine="y"
        data-testid="compliance-chart"
      />
    </Paper>
  );
}

/** The entity's EU ETS position by installation for a compliance year, and the deadlines around it. */
export function EtsPage() {
  const { t } = useTranslation();
  const { entity } = useEntity();
  const { params, reset: resetUrl } = useUrlState();
  const year = yearOr(params.get('year'), new Date().getFullYear());
  const [registering, setRegistering] = useState(false);
  const compliance = $api.useQuery(
    'get',
    '/api/v1/ets/compliance',
    { params: { query: { legalEntityId: entity?.id ?? '', year } } },
    { enabled: Boolean(entity) },
  );
  return (
    <Stack>
      <PageHeader
        title={t('ets.title')}
        description={t('ets.description', { entity: entity?.code ?? '' })}
        actions={
          entity && (
            <>
              <NumberInput
                aria-label={t('positions.complianceYear')}
                leftSection={<IconCalendar size={16} />}
                value={year}
                onChange={(value) => resetUrl(Number(value) ? { year: String(value) } : {})}
                w={130}
                min={2005}
              />
              <Can op="etsCompliance_register">
                <Button leftSection={<IconPlus size={16} />} onClick={() => setRegistering(true)}>
                  {t('ets.register')}
                </Button>
              </Can>
            </>
          )
        }
      />
      {!entity ? (
        <Alert color="gray">{t('header.noEntity')}</Alert>
      ) : (
        <>
          <EtsKpis entityId={entity.id} year={year} compliance={compliance.data} />
          <QueryState isPending={false} error={compliance.error}>
            {!compliance.data ? (
              <TableSkeleton rows={2} />
            ) : compliance.data.installations.length === 0 ? (
              <EmptyState
                icon={<IconBuildingFactory2 size={22} />}
                title={t('positions.noInstallations')}
                description={t('ets.noInstallationsHint')}
              />
            ) : (
              <Paper withBorder radius="lg" p="lg">
                <Title order={3} size="h5" mb="sm">
                  {t('ets.byInstallation', { year })}
                </Title>
                <ScrollTable label={t('ets.byInstallation', { year })} minWidth={1000}>
                  <Table highlightOnHover verticalSpacing="sm" data-testid="installations">
                    <Table.Thead>
                      <Table.Tr>
                        <Table.Th>{t('positions.installation')}</Table.Th>
                        <Table.Th>{t('positions.basis')}</Table.Th>
                        <Table.Th ta="right">{t('positions.emissions')}</Table.Th>
                        <Table.Th ta="right">{t('positions.held')}</Table.Th>
                        <Table.Th ta="right">{t('ets.freeToCome')}</Table.Th>
                        <Table.Th ta="right">{t('positions.surrendered')}</Table.Th>
                        <Table.Th ta="right">{t('ets.outstanding')}</Table.Th>
                        <Table.Th ta="right">{t('ets.net')}</Table.Th>
                        <Table.Th w={150}>{t('allocation.status')}</Table.Th>
                      </Table.Tr>
                    </Table.Thead>
                    <Table.Tbody className="etrm-figure">
                      {compliance.data.installations.map((line) => (
                        <Table.Tr key={line.installation.id}>
                          <Table.Td>
                            <Anchor
                              component={Link}
                              to={`/ets/installations/${line.installation.id}?year=${year}`}
                              size="sm"
                            >
                              {line.installation.name}
                            </Anchor>
                            <Text size="xs" c="dimmed">
                              {line.installation.eutlId}
                            </Text>
                          </Table.Td>
                          <Table.Td>{t(`ets.basis.${line.emissionsBasis}`)}</Table.Td>
                          <Table.Td ta="right">{formatDecimal(line.emissions)}</Table.Td>
                          <Table.Td ta="right">{formatDecimal(line.held)}</Table.Td>
                          <Table.Td ta="right">{formatDecimal(line.freeAllocation?.outstanding ?? 0)}</Table.Td>
                          <Table.Td ta="right">{formatDecimal(line.surrendered)}</Table.Td>
                          <Table.Td ta="right">{formatDecimal(line.outstanding)}</Table.Td>
                          <Table.Td ta="right" fw={600} style={{ color: token(line.net < 0 ? 'loss' : 'gain') }}>
                            {formatDecimal(line.net)}
                          </Table.Td>
                          <Table.Td style={{ whiteSpace: 'nowrap' }}>
                            <ComplianceStatusBadge status={line.status} />
                          </Table.Td>
                        </Table.Tr>
                      ))}
                    </Table.Tbody>
                  </Table>
                </ScrollTable>
              </Paper>
            )}
          </QueryState>
          <Grid gap="md">
            {compliance.data && compliance.data.installations.length > 0 && (
              <Grid.Col span={{ base: 12, lg: 7 }}>
                <ComplianceChart compliance={compliance.data} />
              </Grid.Col>
            )}
            <Grid.Col span={{ base: 12, lg: compliance.data && compliance.data.installations.length > 0 ? 5 : 12 }}>
              <Deadlines entityId={entity.id} year={year} />
            </Grid.Col>
          </Grid>
          <InstallationsCard entityId={entity.id} year={year} />
          {registering && (
            <RegisterInstallationModal
              onClose={() => setRegistering(false)}
              entityId={entity.id}
              country={entity.country}
            />
          )}
        </>
      )}
    </Stack>
  );
}
