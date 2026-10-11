import { Anchor, Badge, Button, Code, Group, Paper, Stack, Text, TextInput, Title } from '@mantine/core';
import { IconHistory } from '@tabler/icons-react';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link, useParams } from 'react-router';
import { $api } from '@/shared/api/client';
import { DetailLayout } from '@/shared/components/DetailLayout';
import { Money, Quantity } from '@/shared/components/Figures';
import { LifecycleTimeline } from '@/shared/components/LifecycleTimeline';
import { PageHeader } from '@/shared/components/PageHeader';
import { QueryState } from '@/shared/components/QueryState';
import { CardSkeleton } from '@/shared/components/Skeletons';
import { statusLabel } from '@/shared/components/StatusBadge';
import { formatDate, formatDecimal } from '@/shared/format/format';
import { daysToExpiry, LotStatusBadge, type Lot } from './lotColumns';
import { LotActions } from './LotOperations';
import { historySteps } from './model/history';

function AsOf({ lotId }: { lotId: string }) {
  const { t } = useTranslation();
  const now = new Date().toISOString().slice(0, 16);
  const [effectiveAt, setEffectiveAt] = useState(now);
  const [knownAt, setKnownAt] = useState(now);
  const [asked, setAsked] = useState<{ effectiveAt: string; knownAt: string } | null>(null);
  const result = $api.useQuery(
    'get',
    '/api/v1/inventory/lots/{id}/as-of',
    {
      params: {
        path: { id: lotId },
        query: {
          effectiveAt: asked ? new Date(asked.effectiveAt).toISOString() : '',
          knownAt: asked ? new Date(asked.knownAt).toISOString() : '',
        },
      },
    },
    { enabled: asked !== null },
  );
  return (
    <Paper withBorder radius="lg" p="lg">
      <Title order={3} size="h5">
        {t('inventory.asOf')}
      </Title>
      <Text size="sm" c="dimmed" mb="md">
        {t('inventory.asOfHint')}
      </Text>
      <Group align="flex-end" gap="sm">
        <TextInput
          type="datetime-local"
          label={t('inventory.effectiveAt')}
          value={effectiveAt}
          onChange={(e) => setEffectiveAt(e.currentTarget.value)}
        />
        <TextInput
          type="datetime-local"
          label={t('inventory.knownAt')}
          value={knownAt}
          onChange={(e) => setKnownAt(e.currentTarget.value)}
        />
        <Button
          variant="default"
          leftSection={<IconHistory size={16} />}
          onClick={() => setAsked({ effectiveAt, knownAt })}
        >
          {t('inventory.show')}
        </Button>
      </Group>
      {asked && (
        <QueryState isPending={result.isPending} error={result.error}>
          <Code block mt="md" mah={360} style={{ overflow: 'auto' }}>
            {JSON.stringify(result.data ?? null, null, 2)}
          </Code>
        </QueryState>
      )}
    </Paper>
  );
}

function LotBody({ lot }: { lot: Lot }) {
  const { t } = useTranslation();
  const history = $api.useQuery('get', '/api/v1/inventory/lots/{id}/history', { params: { path: { id: lot.id } } });
  const days = daysToExpiry(lot);
  const source = statusLabel(lot.energySourceCode ?? '');
  const support =
    [lot.supportProduction ? t('inventory.production') : null, lot.supportInvestment ? t('inventory.investment') : null]
      .filter(Boolean)
      .join(', ') || t('common.no');
  return (
    <Stack gap="lg">
      <PageHeader
        breadcrumbs={[{ label: t('inventory.title'), to: '/inventory' }, { label: lot.issuanceRef }]}
        title={
          <span data-testid="lot-title">
            {t('inventory.lotTitle', { quantity: formatDecimal(lot.quantityMwh), source })}
          </span>
        }
        badge={
          <Group gap={6} wrap="nowrap">
            <LotStatusBadge status={lot.status} testId="lot-status" />
            {days !== null && days <= 60 && (
              <Badge color={days <= 30 ? 'red' : 'orange'} variant="light" radius="xl" tt="none">
                {t('inventory.expiresInLong', { count: days })}
              </Badge>
            )}
          </Group>
        }
        description={`${lot.deviceName ?? lot.deviceGsrn} · ${lot.deviceCountry} · ${t('inventory.producedIn', {
          from: formatDate(lot.productionPeriod.start),
          to: formatDate(lot.productionPeriod.endExclusive),
        })}`}
      />
      <DetailLayout
        summary={[
          { label: t('inventory.quantity'), value: <Quantity value={lot.quantityMwh} unit="MWh" fw={600} /> },
          { label: t('inventory.columns.expiry'), value: formatDate(lot.expiryDate) },
          {
            label: t('inventory.columns.cost'),
            value:
              lot.costBasisPerMwh != null ? <Money value={lot.costBasisPerMwh} currency={lot.costCurrency} /> : '—',
          },
          { label: t('inventory.gsrn'), value: lot.deviceGsrn },
          { label: t('inventory.columns.technology'), value: statusLabel(lot.technologyCode ?? '') || '—' },
          {
            label: t('inventory.capacity'),
            value: lot.deviceCapacityKw != null ? `${formatDecimal(lot.deviceCapacityKw)} kW` : '—',
          },
          { label: t('inventory.range'), value: `${lot.range.start} – ${lot.range.end}` },
          { label: t('inventory.columns.registry'), value: lot.registryCode },
          { label: t('inventory.scheme'), value: lot.certificateScheme },
          { label: t('inventory.issued'), value: formatDate(lot.issueDate) },
          { label: t('inventory.support'), value: support },
          { label: t('inventory.labels'), value: lot.labels.join(', ') || '—' },
          { label: t('inventory.reservation'), value: lot.reservationRef ?? '—' },
          {
            label: t('inventory.parent'),
            value: lot.parentLotId ? (
              <Anchor component={Link} to={`/inventory/lots/${lot.parentLotId}`} size="sm">
                {lot.parentLotId.slice(-8)}
              </Anchor>
            ) : (
              '—'
            ),
          },
          { label: t('inventory.acquiredThrough'), value: statusLabel(lot.acquisitionSource) },
          { label: t('inventory.acquired'), value: formatDate(lot.acquisitionDate) },
        ]}
        aside={
          <>
            <LotActions lot={lot} />
            <QueryState isPending={false} error={history.error}>
              {history.isPending ? (
                <CardSkeleton height={160} />
              ) : (
                <LifecycleTimeline
                  title={t('inventory.history')}
                  steps={historySteps(history.data ?? [])}
                  testId="lot-history"
                />
              )}
            </QueryState>
          </>
        }
      >
        <AsOf lotId={lot.id} />
      </DetailLayout>
    </Stack>
  );
}

export function LotDetailPage() {
  const { lotId = '' } = useParams();
  const lot = $api.useQuery('get', '/api/v1/inventory/lots/{id}', { params: { path: { id: lotId } } });
  return (
    <QueryState isPending={false} error={lot.error}>
      {lot.data ? (
        <LotBody lot={lot.data} />
      ) : (
        <Stack gap="lg">
          <CardSkeleton height={90} />
          <CardSkeleton height={200} />
        </Stack>
      )}
    </QueryState>
  );
}
