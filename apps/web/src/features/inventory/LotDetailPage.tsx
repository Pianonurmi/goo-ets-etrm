import {
  Anchor,
  Badge,
  Button,
  Card,
  Code,
  Grid,
  Group,
  SimpleGrid,
  Stack,
  Text,
  TextInput,
  Timeline,
  Title,
} from '@mantine/core';
import { IconArrowLeft } from '@tabler/icons-react';
import { useState, type ReactNode } from 'react';
import { useTranslation } from 'react-i18next';
import { Link, useParams } from 'react-router';
import { $api } from '@/shared/api/client';
import { QueryState } from '@/shared/components/QueryState';
import { formatDate, formatDateTime, formatDecimal } from '@/shared/format/format';
import { daysToExpiry, LotStatusBadge } from './lotColumns';
import { LotActions } from './LotOperations';

function Field({ label, children }: { label: string; children: ReactNode }) {
  return (
    <Stack gap={0}>
      <Text size="xs" c="dimmed">
        {label}
      </Text>
      <Text size="sm" component="div">
        {children ?? '—'}
      </Text>
    </Stack>
  );
}

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
    <Card withBorder>
      <Title order={5} mb="xs">
        {t('inventory.asOf')}
      </Title>
      <Text size="xs" c="dimmed" mb="sm">
        {t('inventory.asOfHint')}
      </Text>
      <Group align="flex-end">
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
        <Button variant="light" onClick={() => setAsked({ effectiveAt, knownAt })}>
          {t('inventory.show')}
        </Button>
      </Group>
      {asked && (
        <QueryState isPending={result.isPending} error={result.error}>
          <Code block mt="sm">
            {JSON.stringify(result.data ?? null, null, 2)}
          </Code>
        </QueryState>
      )}
    </Card>
  );
}

export function LotDetailPage() {
  const { t } = useTranslation();
  const { lotId = '' } = useParams();
  const lot = $api.useQuery('get', '/api/v1/inventory/lots/{id}', { params: { path: { id: lotId } } });
  const history = $api.useQuery('get', '/api/v1/inventory/lots/{id}/history', { params: { path: { id: lotId } } });
  return (
    <Stack>
      <Anchor component={Link} to="/inventory" size="sm">
        <Group gap={4}>
          <IconArrowLeft size={14} />
          {t('inventory.back')}
        </Group>
      </Anchor>
      <QueryState isPending={lot.isPending} error={lot.error}>
        {lot.data &&
          (() => {
            const data = lot.data;
            const days = daysToExpiry(data);
            return (
              <Grid>
                <Grid.Col span={{ base: 12, lg: 8 }}>
                  <Stack>
                    <Card withBorder>
                      <Group justify="space-between" mb="sm">
                        <Stack gap={0}>
                          <Text size="sm" c="dimmed">
                            {data.issuanceRef}
                          </Text>
                          <Title order={3} data-testid="lot-title">
                            {t('inventory.lotTitle', {
                              quantity: formatDecimal(data.quantityMwh),
                              source: data.energySourceCode,
                            })}
                          </Title>
                        </Stack>
                        <Group gap={6}>
                          <LotStatusBadge status={data.status} testId="lot-status" />
                          {days !== null && days <= 60 && (
                            <Badge color="red">{t('inventory.expiresIn', { count: days })}</Badge>
                          )}
                        </Group>
                      </Group>
                      <SimpleGrid cols={{ base: 2, md: 4 }}>
                        <Field
                          label={t('inventory.columns.device')}
                        >{`${data.deviceName ?? ''} (${data.deviceGsrn})`}</Field>
                        <Field label={t('inventory.columns.country')}>{data.deviceCountry}</Field>
                        <Field label={t('inventory.columns.technology')}>{data.technologyCode}</Field>
                        <Field label={t('inventory.capacity')}>
                          {data.deviceCapacityKw != null ? `${formatDecimal(data.deviceCapacityKw)} kW` : null}
                        </Field>
                        <Field
                          label={t('inventory.columns.production')}
                        >{`${formatDate(data.productionPeriod.start)} – ${formatDate(data.productionPeriod.endExclusive)}`}</Field>
                        <Field label={t('inventory.issued')}>{formatDate(data.issueDate)}</Field>
                        <Field label={t('inventory.columns.expiry')}>{formatDate(data.expiryDate)}</Field>
                        <Field label={t('inventory.range')}>{`${data.range.start} – ${data.range.end}`}</Field>
                        <Field label={t('inventory.columns.registry')}>{data.registryCode}</Field>
                        <Field label={t('inventory.scheme')}>{data.certificateScheme}</Field>
                        <Field label={t('inventory.support')}>
                          {[
                            data.supportProduction ? t('inventory.production') : null,
                            data.supportInvestment ? t('inventory.investment') : null,
                          ]
                            .filter(Boolean)
                            .join(', ') || t('common.no')}
                        </Field>
                        <Field label={t('inventory.columns.cost')}>
                          {data.costBasisPerMwh != null
                            ? `${formatDecimal(data.costBasisPerMwh, 2, 4)} ${data.costCurrency ?? ''}`
                            : null}
                        </Field>
                        <Field label={t('inventory.labels')}>{data.labels.join(', ') || null}</Field>
                        <Field label={t('inventory.reservation')}>{data.reservationRef}</Field>
                        <Field label={t('inventory.parent')}>
                          {data.parentLotId ? (
                            <Anchor component={Link} to={`/inventory/lots/${data.parentLotId}`} size="sm">
                              {data.parentLotId.slice(-8)}
                            </Anchor>
                          ) : null}
                        </Field>
                        <Field
                          label={t('inventory.acquired')}
                        >{`${data.acquisitionSource} ${formatDate(data.acquisitionDate)}`}</Field>
                      </SimpleGrid>
                    </Card>
                    <AsOf lotId={data.id} />
                  </Stack>
                </Grid.Col>
                <Grid.Col span={{ base: 12, lg: 4 }}>
                  <Stack>
                    <LotActions lot={data} />
                    <Card withBorder data-testid="lot-history">
                      <Title order={5} mb="sm">
                        {t('inventory.history')}
                      </Title>
                      <QueryState isPending={history.isPending} error={history.error}>
                        <Timeline bulletSize={14} lineWidth={2}>
                          {(history.data ?? []).map((event) => (
                            <Timeline.Item key={event.seq} title={event.eventType}>
                              <Text size="xs" c="dimmed">
                                {event.actor} · {formatDateTime(event.recordedAt)}
                              </Text>
                            </Timeline.Item>
                          ))}
                        </Timeline>
                      </QueryState>
                    </Card>
                  </Stack>
                </Grid.Col>
              </Grid>
            );
          })()}
      </QueryState>
    </Stack>
  );
}
