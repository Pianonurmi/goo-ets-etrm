import { useUrlState } from '@/shared/useUrlState';
import { LineChart } from '@mantine/charts';
import { Anchor, Button, List, Paper, Stack, Table, Tabs, Text, Title } from '@mantine/core';
import { IconCheck, IconFileTypePdf, IconInbox, IconPlayerStop, IconX } from '@tabler/icons-react';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link, useParams } from 'react-router';
import { $api } from '@/shared/api/client';
import { download } from '@/shared/api/download';
import { Can } from '@/shared/auth/Can';
import { DetailLayout } from '@/shared/components/DetailLayout';
import { EmptyState } from '@/shared/components/EmptyState';
import { Money, Quantity } from '@/shared/components/Figures';
import { LifecycleTimeline } from '@/shared/components/LifecycleTimeline';
import { PageHeader } from '@/shared/components/PageHeader';
import { QueryState } from '@/shared/components/QueryState';
import { ScrollTable } from '@/shared/components/ScrollTable';
import { CardSkeleton } from '@/shared/components/Skeletons';
import { StatusBadge } from '@/shared/components/StatusBadge';
import { useEntity } from '@/shared/context/EntityContext';
import { formatDate, formatDateTime, formatDecimal, formatPercent } from '@/shared/format/format';
import { HedgeDiscontinueModal } from './HedgeDiscontinueModal';
import {
  canDiscontinue,
  hedgeProduct,
  hedgeSteps,
  measurementSeries,
  openTaskType,
  type Hedge,
  type HedgeMeasurement,
} from './model/hedge';

const TABS = ['designation', 'effectiveness'] as const;

/** What the hedge is for, the instruments that hedge it and the prospective checks made at designation. */
function DesignationTab({ hedge }: { hedge: Hedge }) {
  const { t } = useTranslation();
  return (
    <Stack gap="lg">
      <Paper withBorder radius="lg" p="lg">
        <Title order={3} size="h5" mb="xs">
          {t('hedges.detail.objective')}
        </Title>
        <Text size="sm" style={{ whiteSpace: 'pre-wrap' }} data-testid="hedge-objective-text">
          {hedge.objective}
        </Text>
      </Paper>
      <Paper withBorder radius="lg" p="lg">
        <Title order={3} size="h5" mb="sm">
          {t('hedges.detail.instruments')}
        </Title>
        {hedge.instruments.length === 0 ? (
          <Text size="sm" c="dimmed">
            {t('hedges.detail.noInstruments')}
          </Text>
        ) : (
          <ScrollTable label={t('hedges.detail.instruments')} minWidth={420}>
            <Table verticalSpacing="sm" data-testid="hedge-instruments-table">
              <Table.Thead>
                <Table.Tr>
                  <Table.Th>{t('hedges.detail.instrumentTrade')}</Table.Th>
                  <Table.Th ta="right">{t('hedges.detail.instrumentProportion')}</Table.Th>
                  <Table.Th ta="right">{t('hedges.detail.instrumentQuantity')}</Table.Th>
                </Table.Tr>
              </Table.Thead>
              <Table.Tbody className="etrm-figure">
                {hedge.instruments.map((instrument) => (
                  <Table.Tr key={instrument.tradeId}>
                    <Table.Td>
                      <Anchor component={Link} to={`/trades/${instrument.tradeId}`} size="sm">
                        {instrument.tradeNumber}
                      </Anchor>
                    </Table.Td>
                    <Table.Td ta="right">{formatPercent(instrument.proportion, 2)}</Table.Td>
                    <Table.Td ta="right">
                      <Quantity value={instrument.quantity} unit={t(`hedges.units.${hedge.item.productFamily}`)} />
                    </Table.Td>
                  </Table.Tr>
                ))}
              </Table.Tbody>
            </Table>
          </ScrollTable>
        )}
      </Paper>
      <Paper withBorder radius="lg" p="lg">
        <Title order={3} size="h5">
          {t('hedges.detail.checks')}
        </Title>
        <Text size="sm" c="dimmed" mb="sm">
          {t('hedges.detail.checksHint')}
        </Text>
        {hedge.checks.length === 0 ? (
          <Text size="sm" c="dimmed">
            {t('hedges.detail.noChecks')}
          </Text>
        ) : (
          <List spacing="xs" size="sm" center data-testid="hedge-checks">
            {hedge.checks.map((check) => (
              <List.Item
                key={check.code}
                icon={
                  check.passed ? (
                    <IconCheck size={16} color="var(--etrm-gain)" aria-hidden="true" />
                  ) : (
                    <IconX size={16} color="var(--etrm-loss)" aria-hidden="true" />
                  )
                }
                data-testid={`hedge-check-${check.code}`}
              >
                <Text component="span" fw={600} size="sm">
                  {t(`hedges.checks.${check.code}`, { defaultValue: check.code })}
                </Text>{' '}
                <Text component="span" size="sm" c="dimmed">
                  {check.passed ? t('hedges.detail.met') : t('hedges.detail.notMet')} · {check.detail}
                </Text>
              </List.Item>
            ))}
          </List>
        )}
      </Paper>
    </Stack>
  );
}

/** Measurements on official valuation runs: instruments against the hypothetical derivative, and the reserve. */
function EffectivenessTab({ measurements, currency }: { measurements: readonly HedgeMeasurement[]; currency: string }) {
  const { t } = useTranslation();
  if (measurements.length === 0) {
    return (
      <Paper withBorder radius="lg">
        <EmptyState
          title={t('hedges.measurements.empty')}
          description={t('hedges.measurements.emptyHint')}
          testId="hedge-measurements-empty"
        />
      </Paper>
    );
  }
  const series = measurementSeries(measurements);
  const rows = [...measurements].sort((a, b) => b.valuationDate.localeCompare(a.valuationDate));
  return (
    <Stack gap="lg">
      <Paper withBorder radius="lg" p="lg">
        <Title order={3} size="h5">
          {t('hedges.measurements.chartTitle')}
        </Title>
        <Text size="sm" c="dimmed" mb="md">
          {t('hedges.measurements.chartHint', { currency })}
        </Text>
        <LineChart
          h={260}
          data={series}
          dataKey="date"
          series={[
            { name: 'instrumentChange', label: t('hedges.measurements.series.instrumentChange'), color: 'brand.6' },
            {
              name: 'hypotheticalValue',
              label: t('hedges.measurements.series.hypotheticalValue'),
              color: 'orange.7',
            },
            { name: 'effective', label: t('hedges.measurements.series.effective'), color: 'teal.7' },
          ]}
          curveType="linear"
          withLegend
          yAxisProps={{ domain: ['auto', 'auto'], width: 80 }}
          valueFormatter={(value) => formatDecimal(value, 2, 2)}
          data-testid="hedge-chart"
        />
        <Text size="xs" c="dimmed" mt="sm">
          {t('hedges.measurements.method')}
        </Text>
      </Paper>
      <Paper withBorder radius="lg" p="lg">
        <Title order={3} size="h5" mb="sm">
          {t('hedges.measurements.title')}
        </Title>
        <ScrollTable label={t('hedges.measurements.title')} minWidth={900}>
          <Table verticalSpacing="sm" data-testid="hedge-measurements">
            <Table.Thead>
              <Table.Tr>
                <Table.Th>{t('hedges.measurements.valuationDate')}</Table.Th>
                <Table.Th ta="right">{t('hedges.measurements.instrumentValue')}</Table.Th>
                <Table.Th ta="right">{t('hedges.measurements.instrumentChange')}</Table.Th>
                <Table.Th ta="right">{t('hedges.measurements.hypotheticalValue')}</Table.Th>
                <Table.Th ta="right">{t('hedges.measurements.effective')}</Table.Th>
                <Table.Th ta="right">{t('hedges.measurements.ineffective')}</Table.Th>
                <Table.Th ta="right">{t('hedges.measurements.offset')}</Table.Th>
                <Table.Th>{t('hedges.measurements.measuredAt')}</Table.Th>
              </Table.Tr>
            </Table.Thead>
            <Table.Tbody className="etrm-figure">
              {rows.map((m) => (
                <Table.Tr key={m.valuationRunId}>
                  <Table.Td>
                    <Anchor component={Link} to={`/valuation/runs/${m.valuationRunId}`} size="sm">
                      {formatDate(m.valuationDate)}
                    </Anchor>
                  </Table.Td>
                  <Table.Td ta="right">
                    <Money value={m.instrumentValue} currency={currency} />
                  </Table.Td>
                  <Table.Td ta="right">
                    <Money value={m.instrumentChange} currency={currency} signed />
                  </Table.Td>
                  <Table.Td ta="right">
                    <Money value={m.hypotheticalValue} currency={currency} />
                  </Table.Td>
                  <Table.Td ta="right">
                    <Money value={m.effective} currency={currency} />
                  </Table.Td>
                  <Table.Td ta="right">
                    <Money value={m.ineffective} currency={currency} signed />
                  </Table.Td>
                  <Table.Td ta="right">
                    {m.offsetRatio == null ? '—' : `${formatDecimal(m.offsetRatio, 1, 1)} %`}
                  </Table.Td>
                  <Table.Td>{formatDateTime(m.measuredAt)}</Table.Td>
                </Table.Tr>
              ))}
            </Table.Tbody>
          </Table>
        </ScrollTable>
      </Paper>
    </Stack>
  );
}

/** The discontinuation requested for or decided on a hedge, and the reserve it froze. */
function DiscontinuationCard({ hedge, currency }: { hedge: Hedge; currency: string }) {
  const { t } = useTranslation();
  const d = hedge.discontinuation;
  if (!d) return null;
  const decided = Boolean(d.decidedAt);
  return (
    <Paper withBorder p="md" data-testid="hedge-discontinuation">
      <Title order={6} mb="xs">
        {t('hedges.discontinuation.title')}
      </Title>
      <Stack gap="xs">
        <div>
          <Text size="xs" c="dimmed">
            {t('hedges.discontinuation.reason')}
          </Text>
          <Text size="sm">{d.reason}</Text>
        </div>
        <div>
          <Text size="xs" c="dimmed">
            {t('hedges.discontinuation.forecast')}
          </Text>
          <Text size="sm">
            {d.stillExpected ? t('hedges.discontinuation.stillExpected') : t('hedges.discontinuation.notExpected')}
          </Text>
        </div>
        {d.frozenReserve != null && (
          <div>
            <Text size="xs" c="dimmed">
              {t('hedges.discontinuation.frozenReserve')}
            </Text>
            <Money value={d.frozenReserve} currency={currency} size="sm" data-testid="hedge-frozen-reserve" />
          </div>
        )}
        {!decided && (
          <Text size="xs" c="dimmed">
            {t('hedges.discontinuation.waiting')}
          </Text>
        )}
      </Stack>
    </Paper>
  );
}

/** One cash flow hedge: item, instruments, checks, documentation, measurements and lifecycle (ADR 0045). */
export function HedgeDetailPage() {
  const { t } = useTranslation();
  const { hedgeId = '' } = useParams();
  const { entities } = useEntity();
  const { params, set: patchUrl } = useUrlState();
  const [discontinuing, setDiscontinuing] = useState(false);
  const detail = $api.useQuery('get', '/api/v1/ledger/hedges/{id}', { params: { path: { id: hedgeId } } });
  const hedge = detail.data?.relationship;
  const taskType = hedge ? openTaskType(hedge) : null;
  const task = $api.useQuery(
    'get',
    '/api/v1/workflow/tasks',
    { params: { query: { subjectRef: hedgeId, type: taskType ? [taskType] : undefined, status: 'OPEN' } } },
    { enabled: taskType !== null },
  );
  const breadcrumbs = [
    { label: t('ledger.title'), to: '/ledger' },
    { label: t('hedges.breadcrumb'), to: '/ledger/hedges' },
  ];
  if (!hedge) {
    return (
      <Stack gap={0}>
        <PageHeader breadcrumbs={breadcrumbs} title={t('hedges.detail.notFound')} />
        <QueryState isPending={false} error={detail.error}>
          <CardSkeleton height={320} />
        </QueryState>
      </Stack>
    );
  }
  const requestedTab = params.get('tab');
  const tab = TABS.find((value) => value === requestedTab) ?? 'designation';
  const baseCurrency = entities.find((entity) => entity.id === hedge.legalEntityId)?.baseCurrency ?? '';
  const item = hedge.item;
  const unit = t(`hedges.units.${item.productFamily}`);
  const openTask = taskType ? task.data?.items[0] : undefined;
  return (
    <Stack gap={0}>
      <PageHeader
        breadcrumbs={breadcrumbs}
        title={<span data-testid="hedge-number">{hedge.number}</span>}
        badge={
          <StatusBadge status={hedge.status} label={t(`hedges.statuses.${hedge.status}`)} data-testid="hedge-status" />
        }
        description={`${t(`hedges.directions.${item.direction}`)} · ${hedgeProduct(item)} · ${formatDate(item.expectedDate)}`}
        actions={
          <>
            {hedge.documentId && (
              <Button
                variant="default"
                leftSection={<IconFileTypePdf size={16} />}
                onClick={() =>
                  void download(`/api/v1/documents/${hedge.documentId}/content`, `${hedge.number}-documentation.pdf`)
                }
                data-testid="hedge-document"
              >
                {t('hedges.detail.document')}
              </Button>
            )}
            {openTask && (
              <Button
                component={Link}
                to={`/inbox/${openTask.id}`}
                variant="default"
                leftSection={<IconInbox size={16} />}
                data-testid="hedge-task"
              >
                {t('hedges.detail.inbox')}
              </Button>
            )}
            {canDiscontinue(hedge) && (
              <Can op="hedge_requestDiscontinuation">
                <Button
                  color="red"
                  variant="light"
                  leftSection={<IconPlayerStop size={16} />}
                  onClick={() => setDiscontinuing(true)}
                  data-testid="hedge-discontinue"
                >
                  {t('hedges.discontinue.action')}
                </Button>
              </Can>
            )}
          </>
        }
      />
      <div data-testid="hedge-detail">
        <DetailLayout
          summary={[
            { label: t('hedges.detail.direction'), value: t(`hedges.directions.${item.direction}`) },
            { label: t('hedges.detail.product'), value: hedgeProduct(item) },
            {
              label: t('hedges.detail.quantity'),
              value: <Quantity value={item.quantity} unit={unit} fw={600} />,
            },
            { label: t('hedges.detail.expectedDate'), value: formatDate(item.expectedDate) },
            { label: t('hedges.detail.ratio'), value: formatPercent(hedge.hedgeRatio), testId: 'hedge-ratio' },
            { label: t('hedges.detail.currency'), value: item.currency },
            {
              label: t('hedges.detail.designationDate'),
              value: hedge.designationDate ? formatDate(hedge.designationDate) : t('hedges.detail.notDesignated'),
            },
            {
              label: t('hedges.detail.designationPrice'),
              value:
                hedge.designationPrice == null ? (
                  '—'
                ) : (
                  <Text component="span" className="etrm-figure" data-testid="hedge-designation-price">
                    {formatDecimal(hedge.designationPrice, 2, 6)} {item.currency}
                  </Text>
                ),
            },
            {
              label: t('hedges.detail.baseline'),
              value:
                hedge.instrumentBaseline == null ? (
                  '—'
                ) : (
                  <Money value={hedge.instrumentBaseline} currency={baseCurrency} data-testid="hedge-baseline" />
                ),
            },
          ]}
          aside={
            <>
              <LifecycleTimeline
                title={t('hedges.lifecycle.title')}
                steps={hedgeSteps(hedge, t)}
                testId="hedge-lifecycle"
              />
              <DiscontinuationCard hedge={hedge} currency={baseCurrency} />
            </>
          }
        >
          <Tabs
            value={tab}
            onChange={(value) => patchUrl({ tab: value && value !== 'designation' ? value : null })}
            keepMounted={false}
          >
            <Tabs.List>
              <Tabs.Tab value="designation" data-testid="hedge-tab-designation">
                {t('hedges.detail.designation')}
              </Tabs.Tab>
              <Tabs.Tab value="effectiveness" data-testid="hedge-tab-effectiveness">
                {t('hedges.detail.effectiveness')}
              </Tabs.Tab>
            </Tabs.List>
            <Tabs.Panel value="designation" pt="md">
              <DesignationTab hedge={hedge} />
            </Tabs.Panel>
            <Tabs.Panel value="effectiveness" pt="md">
              <EffectivenessTab measurements={detail.data?.measurements ?? []} currency={baseCurrency} />
            </Tabs.Panel>
          </Tabs>
        </DetailLayout>
      </div>
      {discontinuing && (
        <HedgeDiscontinueModal hedgeId={hedge.id} number={hedge.number} onClose={() => setDiscontinuing(false)} />
      )}
    </Stack>
  );
}
