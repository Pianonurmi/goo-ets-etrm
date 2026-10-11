import { LineChart } from '@mantine/charts';
import {
  Alert,
  Anchor,
  Badge,
  Button,
  Card,
  Group,
  Modal,
  Select,
  SimpleGrid,
  Stack,
  Table,
  Text,
  TextInput,
  Title,
} from '@mantine/core';
import { IconArrowLeft, IconHammer, IconInbox, IconPlus } from '@tabler/icons-react';
import { useQueryClient } from '@tanstack/react-query';
import dayjs from 'dayjs';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link, useNavigate, useParams, useSearchParams } from 'react-router';
import { $api } from '@/shared/api/client';
import { decimal } from '@/shared/api/types';
import { Can, useCan } from '@/shared/auth/Can';
import { notifySuccess } from '@/shared/components/notify';
import { PageHeader } from '@/shared/components/PageHeader';
import { ProblemAlert } from '@/shared/components/ProblemAlert';
import { QueryState } from '@/shared/components/QueryState';
import { formatDateTime, formatDecimal } from '@/shared/format/format';
import { CreateCurveModal, OfficialCurve, PriceLookupPanel } from './CurveTools';

export function SnapshotStatusBadge({ status }: { status: string }) {
  const colors: Record<string, string> = {
    DRAFT: 'gray',
    PENDING_SIGN_OFF: 'yellow',
    OFFICIAL: 'teal',
    SUPERSEDED: 'gray',
    REJECTED: 'red',
  };
  return (
    <Badge color={colors[status] ?? 'gray'} variant="light" data-testid="snapshot-status">
      {status.replace(/_/g, ' ')}
    </Badge>
  );
}

function useRefreshCurves() {
  const queryClient = useQueryClient();
  return () =>
    queryClient.invalidateQueries({
      predicate: (q) =>
        typeof q.queryKey[1] === 'string' &&
        (q.queryKey[1].startsWith('/api/v1/market-data/curves') || q.queryKey[1].startsWith('/api/v1/workflow')),
    });
}

/**
 * Curves and their snapshots for a date, the official one, and a price lookup; building a snapshot starts the sign-off
 * (four eyes).
 */
export function CurvesPage() {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const [params, setParams] = useSearchParams();
  const curves = $api.useQuery('get', '/api/v1/market-data/curves', { params: { query: { activeOnly: true } } });
  const code = params.get('curve') ?? curves.data?.[0]?.code ?? '';
  const asOf = params.get('asOf') ?? dayjs().subtract(1, 'day').format('YYYY-MM-DD');
  const snapshots = $api.useQuery(
    'get',
    '/api/v1/market-data/curves/{code}/snapshots',
    { params: { path: { code }, query: { asOf } } },
    { enabled: Boolean(code) },
  );
  const [creating, setCreating] = useState(false);
  const build = $api.useMutation('post', '/api/v1/market-data/curves/{code}/snapshots', {
    meta: { notify: false },
    onSuccess: (snapshot) => void navigate(`/market-data/snapshots/${snapshot.id}`),
  });
  const set = (key: string, value: string) => {
    const next = new URLSearchParams(params);
    next.set(key, value);
    setParams(next, { replace: true });
  };
  return (
    <Stack>
      <PageHeader
        title={t('curves.title')}
        description={t('curves.description')}
        actions={
          <Can op="curve_create">
            <Button
              variant="default"
              leftSection={<IconPlus size={16} />}
              onClick={() => setCreating(true)}
              data-testid="new-curve"
            >
              {t('curves.newCurve')}
            </Button>
          </Can>
        }
      />
      <Group align="flex-end">
        <Select
          label={t('curves.curve')}
          data={(curves.data ?? []).map((curve) => ({
            value: curve.code,
            label: `${curve.code} · ${curve.description}`,
          }))}
          value={code || null}
          onChange={(value) => set('curve', value ?? '')}
          w={360}
          data-testid="curve-select"
        />
        <TextInput
          type="date"
          label={t('curves.asOf')}
          value={asOf}
          onChange={(e) => set('asOf', e.currentTarget.value)}
        />
        <Can op="curve_build">
          <Button
            leftSection={<IconHammer size={16} />}
            disabled={!code}
            loading={build.isPending}
            onClick={() => build.mutate({ params: { path: { code } }, body: { asOf } })}
            data-testid="build-snapshot"
          >
            {t('curves.build')}
          </Button>
        </Can>
      </Group>
      <ProblemAlert error={build.error} />
      <QueryState isPending={snapshots.isPending && Boolean(code)} error={snapshots.error}>
        {(snapshots.data ?? []).length === 0 ? (
          <Text c="dimmed" size="sm">
            {t('curves.noSnapshots')}
          </Text>
        ) : (
          <Table withTableBorder striped highlightOnHover>
            <Table.Thead>
              <Table.Tr>
                <Table.Th>{t('curves.version')}</Table.Th>
                <Table.Th>{t('allocation.status')}</Table.Th>
                <Table.Th>{t('curves.points')}</Table.Th>
                <Table.Th>{t('curves.gaps')}</Table.Th>
                <Table.Th>{t('curves.builtBy')}</Table.Th>
                <Table.Th>{t('curves.signedOffBy')}</Table.Th>
              </Table.Tr>
            </Table.Thead>
            <Table.Tbody>
              {(snapshots.data ?? []).map((snapshot) => (
                <Table.Tr
                  key={snapshot.id}
                  style={{ cursor: 'pointer' }}
                  onClick={() => void navigate(`/market-data/snapshots/${snapshot.id}`)}
                >
                  <Table.Td>v{snapshot.version}</Table.Td>
                  <Table.Td>
                    <SnapshotStatusBadge status={snapshot.status} />
                  </Table.Td>
                  <Table.Td>{snapshot.points.length}</Table.Td>
                  <Table.Td>{snapshot.gaps.length}</Table.Td>
                  <Table.Td>
                    {snapshot.builtBy} · {formatDateTime(snapshot.builtAt)}
                  </Table.Td>
                  <Table.Td>
                    {snapshot.signedOffBy ? `${snapshot.signedOffBy} · ${formatDateTime(snapshot.signedOffAt)}` : '—'}
                  </Table.Td>
                </Table.Tr>
              ))}
            </Table.Tbody>
          </Table>
        )}
      </QueryState>
      {curves.data && (
        <SimpleGrid cols={{ base: 1, lg: 2 }}>
          <OfficialCurve code={code} asOf={asOf} />
          <PriceLookupPanel curves={curves.data} asOf={asOf} />
        </SimpleGrid>
      )}
      {creating && (
        <CreateCurveModal
          curves={curves.data ?? []}
          onClose={() => setCreating(false)}
          onCreated={(curve) => {
            setCreating(false);
            set('curve', curve.code);
          }}
        />
      )}
    </Stack>
  );
}

/** One snapshot: its points as a chart and table, overrides with a note, submission for sign-off. */
export function SnapshotPage() {
  const { t } = useTranslation();
  const { snapshotId = '' } = useParams();
  const refresh = useRefreshCurves();
  const snapshot = $api.useQuery('get', '/api/v1/market-data/curves/snapshots/{id}', {
    params: { path: { id: snapshotId } },
  });
  const task = $api.useQuery(
    'get',
    '/api/v1/workflow/tasks',
    { params: { query: { subjectRef: snapshotId, type: ['CURVE_SIGN_OFF'] } } },
    { enabled: snapshot.data?.status === 'PENDING_SIGN_OFF' },
  );
  const canOverride = useCan('curve_override');
  const [override, setOverride] = useState<{ pillar: string; price: string; note: string } | null>(null);
  const submit = $api.useMutation('post', '/api/v1/market-data/curves/snapshots/{id}/submit', {
    onSuccess: () => {
      notifySuccess(t('curves.submitted'));
      void refresh();
    },
  });
  const overrideMutation = $api.useMutation('post', '/api/v1/market-data/curves/snapshots/{id}/override', {
    meta: { notify: false },
    onSuccess: () => {
      setOverride(null);
      void refresh();
    },
  });
  const path = { params: { path: { id: snapshotId } } };
  return (
    <Stack>
      <Anchor component={Link} to="/market-data/curves" size="sm">
        <Group gap={4}>
          <IconArrowLeft size={14} />
          {t('curves.back')}
        </Group>
      </Anchor>
      <QueryState isPending={snapshot.isPending} error={snapshot.error}>
        {snapshot.data && (
          <Stack>
            <Card withBorder>
              <Group justify="space-between" align="flex-start">
                <Stack gap={2}>
                  <Group gap="xs">
                    <Title order={3}>
                      {snapshot.data.curveCode} {snapshot.data.asOfDate}
                    </Title>
                    <SnapshotStatusBadge status={snapshot.data.status} />
                    <Badge variant="outline" color="gray">
                      v{snapshot.data.version}
                    </Badge>
                  </Group>
                  <Text size="sm" c="dimmed">
                    {snapshot.data.kind} · {snapshot.data.currency}
                  </Text>
                </Stack>
                <Group>
                  {task.data?.items[0] && (
                    <Button
                      component={Link}
                      to={`/inbox/${task.data.items[0].id}`}
                      variant="light"
                      leftSection={<IconInbox size={16} />}
                      data-testid="sign-off-task"
                    >
                      {t('curves.signOffTask')}
                    </Button>
                  )}
                  {snapshot.data.status === 'DRAFT' && (
                    <Can op="curve_submit">
                      <Button
                        loading={submit.isPending}
                        onClick={() => submit.mutate(path)}
                        data-testid="submit-snapshot"
                      >
                        {t('curves.submit')}
                      </Button>
                    </Can>
                  )}
                </Group>
              </Group>
              <SimpleGrid cols={{ base: 2, md: 4 }} mt="md">
                <Text size="sm">
                  {t('curves.builtByWho', { who: snapshot.data.builtBy, at: formatDateTime(snapshot.data.builtAt) })}
                </Text>
                <Text size="sm">
                  {snapshot.data.signedOffBy
                    ? t('curves.signedOffByWho', {
                        who: snapshot.data.signedOffBy,
                        at: formatDateTime(snapshot.data.signedOffAt),
                      })
                    : t('curves.notSignedOff')}
                </Text>
                <Text size="sm" ff="monospace" truncate>
                  {snapshot.data.inputsHash.slice(0, 16)}
                </Text>
              </SimpleGrid>
              {snapshot.data.gaps.length > 0 && (
                <Alert color="orange" mt="md" title={t('curves.gaps')}>
                  {snapshot.data.gaps.join('; ')}
                </Alert>
              )}
            </Card>
            {snapshot.data.points.length > 1 && (
              <Card withBorder>
                <LineChart
                  h={240}
                  data={snapshot.data.points.map((point) => ({ pillar: point.pillar, price: point.price }))}
                  dataKey="pillar"
                  series={[{ name: 'price', label: snapshot.data.currency, color: 'teal.6' }]}
                  curveType="linear"
                />
              </Card>
            )}
            <Table withTableBorder striped>
              <Table.Thead>
                <Table.Tr>
                  <Table.Th>{t('curves.pillar')}</Table.Th>
                  <Table.Th ta="right">{t('trades.columns.price')}</Table.Th>
                  <Table.Th>{t('curves.origin')}</Table.Th>
                  <Table.Th>{t('marketData.note')}</Table.Th>
                  <Table.Th />
                </Table.Tr>
              </Table.Thead>
              <Table.Tbody>
                {snapshot.data.points.map((point) => (
                  <Table.Tr key={point.pillar}>
                    <Table.Td>{point.pillar}</Table.Td>
                    <Table.Td ta="right">{formatDecimal(point.price, 2, 6)}</Table.Td>
                    <Table.Td>{point.origin}</Table.Td>
                    <Table.Td>{point.note}</Table.Td>
                    <Table.Td>
                      {canOverride && snapshot.data.status === 'DRAFT' && (
                        <Button
                          size="xs"
                          variant="subtle"
                          onClick={() => setOverride({ pillar: point.pillar, price: String(point.price), note: '' })}
                        >
                          {t('curves.override')}
                        </Button>
                      )}
                    </Table.Td>
                  </Table.Tr>
                ))}
              </Table.Tbody>
            </Table>
          </Stack>
        )}
      </QueryState>
      <Modal
        opened={override !== null}
        onClose={() => setOverride(null)}
        title={t('curves.overrideTitle', { pillar: override?.pillar ?? '' })}
      >
        {override && (
          <Stack>
            <ProblemAlert error={overrideMutation.error} />
            <TextInput
              label={t('trades.columns.price')}
              inputMode="decimal"
              value={override.price}
              onChange={(e) => setOverride({ ...override, price: e.currentTarget.value })}
            />
            <TextInput
              label={t('marketData.note')}
              required
              value={override.note}
              onChange={(e) => setOverride({ ...override, note: e.currentTarget.value })}
            />
            <Button
              disabled={!override.note.trim() || !override.price.trim()}
              loading={overrideMutation.isPending}
              onClick={() =>
                overrideMutation.mutate({
                  ...path,
                  body: { pillar: override.pillar, price: decimal(override.price), note: override.note.trim() },
                })
              }
            >
              {t('curves.override')}
            </Button>
          </Stack>
        )}
      </Modal>
    </Stack>
  );
}
