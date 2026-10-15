import { useUrlState } from '@/shared/useUrlState';
import { LineChart } from '@mantine/charts';
import {
  Alert,
  Button,
  Group,
  Modal,
  Paper,
  Select,
  SimpleGrid,
  Stack,
  Table,
  Text,
  TextInput,
  Title,
} from '@mantine/core';
import { IconChartLine, IconHammer, IconInbox, IconPlus, IconSend } from '@tabler/icons-react';
import { useQueryClient } from '@tanstack/react-query';
import dayjs from 'dayjs';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link, useNavigate, useParams } from 'react-router';
import { $api } from '@/shared/api/client';
import { decimal } from '@/shared/api/types';
import { Can, useCan } from '@/shared/auth/Can';
import { DetailLayout } from '@/shared/components/DetailLayout';
import { EmptyState } from '@/shared/components/EmptyState';
import { LifecycleTimeline } from '@/shared/components/LifecycleTimeline';
import { notifySuccess } from '@/shared/components/notify';
import { PageHeader } from '@/shared/components/PageHeader';
import { ProblemAlert } from '@/shared/components/ProblemAlert';
import { QueryState } from '@/shared/components/QueryState';
import { CardSkeleton, TableSkeleton } from '@/shared/components/Skeletons';
import { StatusBadge } from '@/shared/components/StatusBadge';
import { formatDate, formatDateTime, formatDecimal } from '@/shared/format/format';
import { CreateCurveModal, OfficialCurve, PriceLookupPanel } from './CurveTools';
import { signOffSteps } from './model/lifecycle';

/** The snapshot page's badge is `snapshot-status`; rows of the snapshot list use their own id, so the two never mix. */
export function SnapshotStatusBadge({ status, testId = 'snapshot-status' }: { status: string; testId?: string }) {
  return <StatusBadge status={status} data-testid={testId} />;
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

/** How later snapshots of a forward curve are read beyond the last future: flat, or at the last carry rate. */
function InterpolationSelect({ code, value }: { code: string; value: 'LINEAR' | 'CARRY' }) {
  const { t } = useTranslation();
  const allowed = useCan('curve_setInterpolation');
  const refresh = useRefreshCurves();
  const change = $api.useMutation('post', '/api/v1/market-data/curves/{code}/interpolation', {
    onSuccess: () => {
      notifySuccess(t('curves.interpolationChanged'));
      void refresh();
    },
  });
  return (
    <>
      <Select
        label={t('curves.interpolation')}
        description={t('curves.interpolationHelp')}
        data={(['LINEAR', 'CARRY'] as const).map((v) => ({ value: v, label: t(`curves.interpolations.${v}`) }))}
        value={value}
        disabled={!allowed || change.isPending}
        onChange={(next) => {
          if (next && next !== value) change.mutate({ params: { path: { code } }, body: { interpolation: next } });
        }}
        w={360}
        data-testid="curve-interpolation"
      />
      <ProblemAlert error={change.error} />
    </>
  );
}

/**
 * Curves and their snapshots for a date, the official one, and a price lookup; building a snapshot starts the sign-off
 * (four eyes).
 */
export function CurvesPage() {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const { params, set: patchUrl } = useUrlState();
  const curves = $api.useQuery('get', '/api/v1/market-data/curves', { params: { query: { activeOnly: true } } });
  const code = params.get('curve') ?? curves.data?.[0]?.code ?? '';
  const asOf = params.get('asOf') ?? dayjs().subtract(1, 'day').format('YYYY-MM-DD');
  const snapshots = $api.useQuery(
    'get',
    '/api/v1/market-data/curves/{code}/snapshots',
    { params: { path: { code }, query: { asOf } } },
    { enabled: Boolean(code) },
  );
  const selected = curves.data?.find((curve) => curve.code === code);
  const [creating, setCreating] = useState(false);
  const build = $api.useMutation('post', '/api/v1/market-data/curves/{code}/snapshots', {
    meta: { notify: false },
    onSuccess: (snapshot) => void navigate(`/market-data/snapshots/${snapshot.id}`),
  });
  const set = (key: string, value: string) => patchUrl({ [key]: value });
  const buildButton = (
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
  );
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
        {buildButton}
      </Group>
      {selected?.kind === 'EUA_FORWARD' && (
        <InterpolationSelect code={selected.code} value={selected.interpolation ?? 'LINEAR'} />
      )}
      {selected?.kind === 'DISCOUNT' && selected.dayCount && (
        <Text size="sm" c="dimmed" data-testid="curve-day-count-info">
          {t('curves.discountCurveInfo', {
            currency: selected.currency,
            dayCount: t(`curves.dayCounts.${selected.dayCount}`),
          })}
        </Text>
      )}
      <ProblemAlert error={build.error} />
      {curves.data && (
        <SimpleGrid cols={{ base: 1, lg: 2 }}>
          <OfficialCurve code={code} asOf={asOf} />
          <PriceLookupPanel curves={curves.data} asOf={asOf} />
        </SimpleGrid>
      )}
      <Paper withBorder radius="lg" p="lg">
        <Title order={3} size="h5" mb="sm">
          {t('curves.snapshotsOn', { code, date: formatDate(asOf) })}
        </Title>
        <QueryState isPending={false} error={snapshots.error}>
          {snapshots.isPending && Boolean(code) ? (
            <TableSkeleton rows={2} />
          ) : (snapshots.data ?? []).length === 0 ? (
            <EmptyState
              icon={<IconChartLine size={22} />}
              title={t('curves.noSnapshots')}
              description={t('curves.noSnapshotsHint')}
              testId="snapshots-none"
            />
          ) : (
            <Table highlightOnHover verticalSpacing="sm">
              <Table.Thead>
                <Table.Tr>
                  <Table.Th>{t('curves.version')}</Table.Th>
                  <Table.Th>{t('allocation.status')}</Table.Th>
                  <Table.Th ta="right">{t('curves.points')}</Table.Th>
                  <Table.Th ta="right">{t('curves.gaps')}</Table.Th>
                  <Table.Th>{t('curves.builtBy')}</Table.Th>
                  <Table.Th>{t('curves.signedOffBy')}</Table.Th>
                </Table.Tr>
              </Table.Thead>
              <Table.Tbody className="etrm-figure">
                {(snapshots.data ?? []).map((snapshot) => (
                  <Table.Tr
                    key={snapshot.id}
                    style={{ cursor: 'pointer' }}
                    onClick={() => void navigate(`/market-data/snapshots/${snapshot.id}`)}
                  >
                    <Table.Td>v{snapshot.version}</Table.Td>
                    <Table.Td>
                      <SnapshotStatusBadge status={snapshot.status} testId="snapshot-row-status" />
                    </Table.Td>
                    <Table.Td ta="right">{snapshot.points.length}</Table.Td>
                    <Table.Td ta="right" c={snapshot.gaps.length > 0 ? 'orange' : undefined}>
                      {snapshot.gaps.length}
                    </Table.Td>
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
      </Paper>
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

/** One snapshot: its shape and points, overrides with a note, submission for sign-off, and its lifecycle. */
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
  const data = snapshot.data;
  const breadcrumbs = [
    { label: t('marketData.title'), to: '/market-data' },
    {
      label: t('curves.title'),
      to: data ? `/market-data/curves?curve=${data.curveCode}&asOf=${data.asOfDate}` : '/market-data/curves',
    },
  ];
  if (!data) {
    return (
      <Stack gap={0}>
        <PageHeader breadcrumbs={breadcrumbs} title={t('curves.snapshot')} />
        <QueryState isPending={false} error={snapshot.error}>
          <CardSkeleton height={320} />
        </QueryState>
      </Stack>
    );
  }
  const signOffTask = task.data?.items[0];
  const isDiscount = data.kind === 'DISCOUNT';
  const isVolatility = data.kind === 'EUA_VOLATILITY';
  const valueLabel = isDiscount
    ? t('curves.ratePercent')
    : isVolatility
      ? t('curves.volatilityPercent')
      : t('trades.columns.price');
  return (
    <Stack gap={0}>
      <PageHeader
        breadcrumbs={breadcrumbs}
        title={`${data.curveCode} · ${formatDate(data.asOfDate)}`}
        badge={
          <Group gap={6} wrap="nowrap">
            <SnapshotStatusBadge status={data.status} />
            <StatusBadge status="VERSION" label={`v${data.version}`} />
          </Group>
        }
        description={[
          t(`curves.kinds.${data.kind}`),
          data.currency,
          isDiscount && data.dayCount ? t(`curves.dayCounts.${data.dayCount}`) : null,
        ]
          .filter(Boolean)
          .join(' · ')}
        actions={
          <>
            {signOffTask && (
              <Button
                component={Link}
                to={`/inbox/${signOffTask.id}`}
                variant={data.status === 'DRAFT' ? 'default' : 'filled'}
                leftSection={<IconInbox size={16} />}
                data-testid="sign-off-task"
              >
                {t('curves.signOffTask')}
              </Button>
            )}
            {data.status === 'DRAFT' && (
              <Can op="curve_submit">
                <Button
                  leftSection={<IconSend size={16} />}
                  loading={submit.isPending}
                  onClick={() => submit.mutate(path)}
                  data-testid="submit-snapshot"
                >
                  {t('curves.submit')}
                </Button>
              </Can>
            )}
          </>
        }
      />
      <DetailLayout
        summary={[
          { label: t('curves.points'), value: data.points.length },
          { label: t('curves.gaps'), value: data.gaps.length },
          { label: t('marketData.currency'), value: data.currency },
          ...(isDiscount && data.dayCount
            ? [{ label: t('curves.dayCount'), value: t(`curves.dayCounts.${data.dayCount}`) }]
            : []),
          {
            label: t('curves.inputsHash'),
            value: (
              <Text component="span" ff="monospace" size="sm" title={data.inputsHash}>
                {data.inputsHash.slice(0, 16)}
              </Text>
            ),
          },
        ]}
        aside={
          <LifecycleTimeline
            title={t('curves.lifecycle.title')}
            steps={signOffSteps(data, t)}
            testId="snapshot-lifecycle"
          />
        }
      >
        {data.gaps.length > 0 && (
          <Alert color="orange" title={t('curves.gaps')}>
            {data.gaps.join('; ')}
          </Alert>
        )}
        {data.note && (
          <Text size="sm" c="dimmed">
            {data.note}
          </Text>
        )}
        {data.points.length > 1 && (
          <Paper withBorder radius="lg" p="lg">
            <Title order={3} size="h5" mb="sm">
              {t('curves.shape')}
            </Title>
            <LineChart
              h={220}
              data={data.points.map((point) => ({ pillar: point.pillar, price: point.price }))}
              dataKey="pillar"
              series={[
                {
                  name: 'price',
                  label: isDiscount
                    ? t('curves.ratePercentShort')
                    : isVolatility
                      ? t('curves.volatilityPercent')
                      : data.currency,
                  color: 'brand.6',
                },
              ]}
              curveType="monotone"
              yAxisProps={{ domain: ['auto', 'auto'] }}
              valueFormatter={(value) => formatDecimal(value, 2, 4)}
            />
          </Paper>
        )}
        <Paper withBorder radius="lg" p="lg">
          <Title order={3} size="h5" mb="sm">
            {t('curves.pillars')}
          </Title>
          <Table verticalSpacing="sm">
            <Table.Thead>
              <Table.Tr>
                <Table.Th>{isDiscount ? t('curves.tenor') : t('curves.pillar')}</Table.Th>
                {isDiscount && <Table.Th>{t('curves.pillarDate')}</Table.Th>}
                <Table.Th ta="right">{valueLabel}</Table.Th>
                <Table.Th>{t('curves.origin')}</Table.Th>
                <Table.Th>{t('marketData.note')}</Table.Th>
                <Table.Th />
              </Table.Tr>
            </Table.Thead>
            <Table.Tbody className="etrm-figure">
              {data.points.map((point) => (
                <Table.Tr key={point.pillar}>
                  <Table.Td>{point.pillar}</Table.Td>
                  {isDiscount && <Table.Td>{formatDate(point.pillarDate)}</Table.Td>}
                  <Table.Td ta="right">{formatDecimal(point.price, 2, 6)}</Table.Td>
                  <Table.Td c="dimmed">{point.origin}</Table.Td>
                  <Table.Td>{point.note}</Table.Td>
                  <Table.Td ta="right">
                    {canOverride && data.status === 'DRAFT' && (
                      <Button
                        size="compact-sm"
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
        </Paper>
      </DetailLayout>
      <Modal
        opened={override !== null}
        onClose={() => setOverride(null)}
        title={t('curves.overrideTitle', { pillar: override?.pillar ?? '' })}
      >
        {override && (
          <Stack>
            <ProblemAlert error={overrideMutation.error} />
            <TextInput
              label={valueLabel}
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
