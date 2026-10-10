import {
  Alert,
  Anchor,
  Badge,
  Button,
  Card,
  Group,
  Modal,
  NumberInput,
  Select,
  SimpleGrid,
  Stack,
  Table,
  Text,
  TextInput,
  Timeline,
  Title,
} from '@mantine/core';
import { IconCalendarDue, IconPlus } from '@tabler/icons-react';
import { useQueryClient } from '@tanstack/react-query';
import dayjs from 'dayjs';
import { useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link, useNavigate, useSearchParams } from 'react-router';
import { $api } from '@/shared/api/client';
import type { Schema } from '@/shared/api/types';
import { Can } from '@/shared/auth/Can';
import { notifySuccess } from '@/shared/components/notify';
import { PageHeader } from '@/shared/components/PageHeader';
import { ProblemAlert } from '@/shared/components/ProblemAlert';
import { QueryState } from '@/shared/components/QueryState';
import { useEntity } from '@/shared/context/EntityContext';
import { formatDate, formatDecimal } from '@/shared/format/format';
import { useEntityAccounts } from '@/features/trades/hooks';
import { DEADLINE_COLORS, deadlineCalendar } from './model/deadlines';

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

export function ComplianceStatusBadge({ status }: { status: ComplianceStatus }) {
  const colors: Record<ComplianceStatus, string> = {
    OPEN: 'blue',
    VERIFIED: 'cyan',
    COMPLIANT: 'teal',
    NON_COMPLIANT: 'red',
  };
  return (
    <Badge color={colors[status]} variant="light" data-testid="compliance-status">
      {status.replace(/_/g, ' ')}
    </Badge>
  );
}

export function Figure({ label, value, color }: { label: string; value: string; color?: string }) {
  return (
    <Card withBorder>
      <Text size="xs" c="dimmed">
        {label}
      </Text>
      <Text size="xl" fw={700} c={color}>
        {value}
      </Text>
    </Card>
  );
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

function Deadlines({ entityId, year }: { entityId: string; year: number }) {
  const { t } = useTranslation();
  const today = dayjs().format('YYYY-MM-DD');
  const deadlines = $api.useQuery('get', '/api/v1/ets/deadlines', {
    params: { query: { legalEntityId: entityId, from: `${year}-01-01`, to: `${year + 1}-12-31` } },
  });
  const months = useMemo(() => deadlineCalendar(deadlines.data ?? [], today), [deadlines.data, today]);
  return (
    <Card withBorder>
      <Title order={5} mb="sm">
        {t('ets.deadlines')}
      </Title>
      <QueryState isPending={deadlines.isPending} error={deadlines.error}>
        {months.length === 0 ? (
          <Text size="sm" c="dimmed">
            {t('ets.noDeadlines')}
          </Text>
        ) : (
          <Timeline bulletSize={22} lineWidth={2} data-testid="deadline-calendar">
            {months.map((month) => (
              <Timeline.Item
                key={month.month}
                bullet={<IconCalendarDue size={12} />}
                title={dayjs(`${month.month}-01`).format('MMMM YYYY')}
              >
                <Stack gap={4} mt={4}>
                  {month.deadlines.map((deadline) => (
                    <Group key={`${deadline.installationId}-${deadline.kind}-${deadline.complianceYear}`} gap="xs">
                      <Text size="sm" w={100}>
                        {formatDate(deadline.dueOn)}
                      </Text>
                      <Badge color={DEADLINE_COLORS[deadline.state]} variant="light" w={100}>
                        {t(`ets.deadlineStates.${deadline.state}`)}
                      </Badge>
                      <Text size="sm">
                        {t(`ets.deadlineKinds.${deadline.kind}`, { year: deadline.complianceYear })} ·{' '}
                        <Anchor component={Link} to={`/ets/installations/${deadline.installationId}`} size="sm">
                          {deadline.installationName}
                        </Anchor>
                      </Text>
                    </Group>
                  ))}
                </Stack>
              </Timeline.Item>
            ))}
          </Timeline>
        )}
      </QueryState>
    </Card>
  );
}

/** The entity's EU ETS position by installation for a compliance year, and the deadlines around it. */
export function EtsPage() {
  const { t } = useTranslation();
  const { entity } = useEntity();
  const [params, setParams] = useSearchParams();
  const year = Number(params.get('year') ?? new Date().getFullYear());
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
            <Can op="etsCompliance_register">
              <Button leftSection={<IconPlus size={16} />} onClick={() => setRegistering(true)}>
                {t('ets.register')}
              </Button>
            </Can>
          )
        }
      />
      {!entity ? (
        <Alert color="gray">{t('header.noEntity')}</Alert>
      ) : (
        <>
          <NumberInput
            label={t('positions.complianceYear')}
            value={year}
            onChange={(value) => setParams(Number(value) ? { year: String(value) } : {}, { replace: true })}
            w={180}
            min={2005}
          />
          <QueryState isPending={compliance.isPending} error={compliance.error}>
            {compliance.data && (
              <Stack>
                <SimpleGrid cols={{ base: 2, md: 5 }}>
                  <Figure label={t('ets.estimated')} value={tonnes(compliance.data.estimatedEmissions)} />
                  <Figure label={t('ets.verified')} value={tonnes(compliance.data.verifiedEmissions)} />
                  <Figure
                    label={t('positions.freeAllocation')}
                    value={`${formatDecimal(compliance.data.freeAllocationReceived)} / ${tonnes(compliance.data.freeAllocationExpected)}`}
                  />
                  <Figure label={t('positions.surrendered')} value={tonnes(compliance.data.surrendered)} />
                  <Figure label={t('positions.deadline')} value={formatDate(compliance.data.surrenderDeadline)} />
                </SimpleGrid>
                {compliance.data.installations.length === 0 ? (
                  <Alert color="gray">{t('positions.noInstallations')}</Alert>
                ) : (
                  <Table.ScrollContainer minWidth={1100}>
                    <Table withTableBorder striped highlightOnHover data-testid="installations">
                      <Table.Thead>
                        <Table.Tr>
                          <Table.Th>{t('positions.installation')}</Table.Th>
                          <Table.Th>{t('ets.eutlId')}</Table.Th>
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
                      <Table.Tbody>
                        {compliance.data.installations.map((line) => (
                          <Table.Tr key={line.installation.id}>
                            <Table.Td>
                              <Anchor component={Link} to={`/ets/installations/${line.installation.id}?year=${year}`}>
                                {line.installation.name}
                              </Anchor>
                            </Table.Td>
                            <Table.Td style={{ whiteSpace: 'nowrap' }}>{line.installation.eutlId}</Table.Td>
                            <Table.Td>{t(`ets.basis.${line.emissionsBasis}`)}</Table.Td>
                            <Table.Td ta="right">{formatDecimal(line.emissions)}</Table.Td>
                            <Table.Td ta="right">{formatDecimal(line.held)}</Table.Td>
                            <Table.Td ta="right">{formatDecimal(line.freeAllocation?.outstanding ?? 0)}</Table.Td>
                            <Table.Td ta="right">{formatDecimal(line.surrendered)}</Table.Td>
                            <Table.Td ta="right">{formatDecimal(line.outstanding)}</Table.Td>
                            <Table.Td ta="right" c={line.net < 0 ? 'red' : 'teal'} fw={600}>
                              {formatDecimal(line.net)}
                            </Table.Td>
                            <Table.Td style={{ whiteSpace: 'nowrap' }}>
                              <ComplianceStatusBadge status={line.status} />
                            </Table.Td>
                          </Table.Tr>
                        ))}
                      </Table.Tbody>
                    </Table>
                  </Table.ScrollContainer>
                )}
              </Stack>
            )}
          </QueryState>
          <Deadlines entityId={entity.id} year={year} />
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
