import {
  Anchor,
  Badge,
  Button,
  Card,
  Code,
  Grid,
  Group,
  Modal,
  NumberInput,
  Select,
  SimpleGrid,
  Stack,
  Text,
  TextInput,
  Textarea,
  Timeline,
  Title,
} from '@mantine/core';
import { IconArrowLeft } from '@tabler/icons-react';
import { useQueryClient } from '@tanstack/react-query';
import { useState, type ReactNode } from 'react';
import { useTranslation } from 'react-i18next';
import { Link, useParams } from 'react-router';
import { $api } from '@/shared/api/client';
import type { Schema } from '@/shared/api/types';
import { useCan } from '@/shared/auth/Can';
import { notifySuccess } from '@/shared/components/notify';
import { QueryState } from '@/shared/components/QueryState';
import { formatDate, formatDateTime, formatDecimal } from '@/shared/format/format';
import { daysToExpiry, LotStatusBadge, type Lot } from './lotColumns';

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

type Action = 'withdraw' | 'split' | 'cancel' | null;
type Purpose = Schema<'CancellationDetails'>['purpose'];
const PURPOSES: Purpose[] = ['DISCLOSURE', 'OWN_CONSUMPTION', 'VOLUNTARY', 'COMPLIANCE', 'ON_BEHALF_OF_THIRD_PARTY'];

function useRefreshInventory() {
  const queryClient = useQueryClient();
  return () =>
    queryClient.invalidateQueries({
      predicate: (query) => typeof query.queryKey[1] === 'string' && query.queryKey[1].startsWith('/api/v1/inventory'),
    });
}

function LotActions({ lot }: { lot: Lot }) {
  const { t } = useTranslation();
  const refresh = useRefreshInventory();
  const canWithdraw = useCan('inventory_withdraw');
  const canSplit = useCan('inventory_split');
  const canCancel = useCan('inventory_cancel');
  const [action, setAction] = useState<Action>(null);
  const [reason, setReason] = useState('');
  const [quantity, setQuantity] = useState<number | string>('');
  const [cancellation, setCancellation] = useState({
    purpose: 'DISCLOSURE' as Purpose,
    country: lot.deviceCountry,
    from: '',
    to: '',
    beneficiary: '',
  });
  const done = (message: string) => () => {
    notifySuccess(message);
    setAction(null);
    void refresh();
  };
  const withdraw = $api.useMutation('post', '/api/v1/inventory/lots/{id}/withdraw', {
    onSuccess: done(t('inventory.withdrawn')),
  });
  const split = $api.useMutation('post', '/api/v1/inventory/lots/{id}/split', {
    onSuccess: done(t('inventory.splitDone')),
  });
  const cancel = $api.useMutation('post', '/api/v1/inventory/lots/{id}/cancel', {
    onSuccess: done(t('inventory.cancelled')),
  });
  const live = lot.status === 'HELD';
  const path = { params: { path: { id: lot.id } } };
  if (!live || !(canWithdraw || canSplit || canCancel)) return null;
  return (
    <Card withBorder>
      <Title order={5} mb="sm">
        {t('inventory.actions')}
      </Title>
      <Group>
        {canSplit && (
          <Button variant="light" size="xs" onClick={() => setAction('split')}>
            {t('inventory.split')}
          </Button>
        )}
        {canCancel && (
          <Button variant="light" size="xs" color="violet" onClick={() => setAction('cancel')}>
            {t('inventory.cancel')}
          </Button>
        )}
        {canWithdraw && (
          <Button variant="light" size="xs" color="orange" onClick={() => setAction('withdraw')} data-testid="withdraw">
            {t('inventory.withdraw')}
          </Button>
        )}
      </Group>
      <Modal opened={action === 'withdraw'} onClose={() => setAction(null)} title={t('inventory.withdraw')}>
        <Stack>
          <Text size="sm">{t('inventory.withdrawHint')}</Text>
          <Textarea
            label={t('inbox.reason')}
            required
            value={reason}
            onChange={(event) => setReason(event.currentTarget.value)}
          />
          <Button
            color="orange"
            disabled={!reason.trim()}
            loading={withdraw.isPending}
            onClick={() => withdraw.mutate({ ...path, body: { reason: reason.trim() } })}
          >
            {t('inventory.withdraw')}
          </Button>
        </Stack>
      </Modal>
      <Modal opened={action === 'split'} onClose={() => setAction(null)} title={t('inventory.split')}>
        <Stack>
          <NumberInput
            label={t('inventory.splitQuantity')}
            description={t('inventory.splitHint', { quantity: lot.quantityMwh })}
            min={1}
            max={lot.quantityMwh - 1}
            value={quantity}
            onChange={setQuantity}
          />
          <Button
            disabled={!quantity}
            loading={split.isPending}
            onClick={() => split.mutate({ ...path, body: { quantity: Number(quantity) } })}
          >
            {t('inventory.split')}
          </Button>
        </Stack>
      </Modal>
      <Modal opened={action === 'cancel'} onClose={() => setAction(null)} title={t('inventory.cancel')} size="lg">
        <Stack>
          <Text size="sm">{t('inventory.cancelHint')}</Text>
          <Select
            label={t('inventory.purpose')}
            data={PURPOSES.map((purpose) => ({ value: purpose, label: t(`inventory.purposes.${purpose}`) }))}
            value={cancellation.purpose}
            onChange={(value) => setCancellation({ ...cancellation, purpose: value ?? 'DISCLOSURE' })}
            allowDeselect={false}
          />
          <TextInput
            label={t('inventory.beneficiary')}
            value={cancellation.beneficiary}
            onChange={(e) => setCancellation({ ...cancellation, beneficiary: e.currentTarget.value })}
          />
          <Group grow>
            <TextInput
              label={t('inventory.consumptionCountry')}
              value={cancellation.country}
              onChange={(e) => setCancellation({ ...cancellation, country: e.currentTarget.value.toUpperCase() })}
              maxLength={2}
            />
            <TextInput
              type="date"
              label={t('inventory.consumptionFrom')}
              value={cancellation.from}
              onChange={(e) => setCancellation({ ...cancellation, from: e.currentTarget.value })}
            />
            <TextInput
              type="date"
              label={t('inventory.consumptionTo')}
              value={cancellation.to}
              onChange={(e) => setCancellation({ ...cancellation, to: e.currentTarget.value })}
            />
          </Group>
          <Button
            color="violet"
            disabled={!cancellation.purpose || !cancellation.country || !cancellation.from || !cancellation.to}
            loading={cancel.isPending}
            onClick={() =>
              cancel.mutate({
                ...path,
                body: {
                  quantity: null,
                  details: {
                    purpose: cancellation.purpose,
                    consumptionCountry: cancellation.country,
                    consumptionPeriod: { start: cancellation.from, endExclusive: cancellation.to },
                    beneficiaryName: cancellation.beneficiary || null,
                  },
                },
              })
            }
          >
            {t('inventory.cancel')}
          </Button>
        </Stack>
      </Modal>
    </Card>
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
                          <LotStatusBadge status={data.status} />
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
                    <Card withBorder>
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
