import {
  Anchor,
  Badge,
  Button,
  Card,
  Group,
  Modal,
  MultiSelect,
  SegmentedControl,
  Select,
  SimpleGrid,
  Stack,
  Text,
  TextInput,
  Title,
} from '@mantine/core';
import { useForm } from '@mantine/form';
import { IconSearch } from '@tabler/icons-react';
import dayjs from 'dayjs';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link } from 'react-router';
import { $api } from '@/shared/api/client';
import { asProblemError } from '@/shared/api/problem';
import type { Schema } from '@/shared/api/types';
import { EmptyState } from '@/shared/components/EmptyState';
import { FactGrid } from '@/shared/components/DetailLayout';
import { notifySuccess } from '@/shared/components/notify';
import { ProblemAlert } from '@/shared/components/ProblemAlert';
import { QueryState } from '@/shared/components/QueryState';
import { StatusBadge } from '@/shared/components/StatusBadge';
import { applyProblemToForm } from '@/shared/forms/applyProblemToForm';
import { formatDate, formatDateTime, formatDecimal } from '@/shared/format/format';
import { useAllowanceProducts } from '@/features/trades/hooks';
import {
  CURVE_KINDS,
  DAY_COUNTS,
  defaultDayCount,
  emptyCurve,
  lookupOptions,
  priceLookup,
  proxyCandidates,
  toCreateCurve,
  validateCurve,
  type Curve,
  type CurveForm,
  type PriceLookup,
  type PriceLookupForm,
} from './model/curves';
import { QUOTE_TYPES } from './model/marketData';
import { translated, useBucketCodes, useRefreshMarketData } from './InstrumentActions';

const CURVE_FIELDS = new Set(Object.keys(emptyCurve()));

/** A new forward, vintage or discount curve; its snapshots are then built and signed off as usual. */
export function CreateCurveModal({
  curves,
  onClose,
  onCreated,
}: {
  curves: readonly Curve[];
  onClose: () => void;
  onCreated: (curve: Curve) => void;
}) {
  const { t } = useTranslation();
  const refresh = useRefreshMarketData();
  const allowances = useAllowanceProducts();
  const buckets = useBucketCodes();
  const form = useForm<CurveForm>({
    initialValues: emptyCurve(),
    validate: (values) => translated(validateCurve(values), t),
  });
  const create = $api.useMutation('post', '/api/v1/market-data/curves', {
    meta: { notify: false },
    onSuccess: (curve) => {
      notifySuccess(t('curves.curveCreated', { code: curve.code }));
      void refresh();
      onCreated(curve);
    },
    onError: (error) => {
      const problem = asProblemError(error);
      if (problem.status === 422) applyProblemToForm(form, problem, CURVE_FIELDS);
    },
  });
  const kind = form.values.kind;
  const proxies = proxyCandidates(curves, kind, form.values.code);
  return (
    <Modal opened onClose={onClose} title={t('curves.newCurve')} size="lg">
      <form onSubmit={form.onSubmit((values) => create.mutate({ body: toCreateCurve(values) }))}>
        <Stack>
          <ProblemAlert error={create.error} />
          <SimpleGrid cols={{ base: 1, sm: 2 }}>
            <TextInput
              label={t('marketData.code')}
              description={t('marketData.codeHint')}
              required
              data-testid="curve-code"
              {...form.getInputProps('code')}
            />
            <Select
              label={t('curves.kind')}
              data={CURVE_KINDS.map((value) => ({ value, label: t(`curves.kinds.${value}`) }))}
              allowDeselect={false}
              data-testid="curve-kind"
              {...form.getInputProps('kind')}
              onChange={(value) => {
                if (!value) return;
                form.setFieldValue('kind', value as CurveForm['kind']);
                form.setFieldValue('proxyCurveCode', '');
              }}
            />
          </SimpleGrid>
          <TextInput
            label={t('marketData.descriptionColumn')}
            required
            data-testid="curve-description"
            {...form.getInputProps('description')}
          />
          <SimpleGrid cols={{ base: 1, sm: 2 }}>
            {kind === 'EUA_FORWARD' && (
              <Select
                label={t('curves.product')}
                data={allowances.map((product) => ({
                  value: product.code,
                  label: `${product.code} · ${product.name}`,
                }))}
                required
                data-testid="curve-allowance"
                {...form.getInputProps('allowanceProductCode')}
              />
            )}
            {kind === 'GOO_VINTAGE' && (
              <Select
                label={t('curves.bucket')}
                data={buckets}
                required
                searchable
                data-testid="curve-bucket"
                {...form.getInputProps('bucketCode')}
              />
            )}
            <TextInput
              label={t('marketData.currency')}
              maxLength={3}
              required
              data-testid="curve-currency"
              {...form.getInputProps('currency')}
            />
            {kind === 'DISCOUNT' && (
              <Select
                label={t('curves.dayCount')}
                description={t('curves.dayCountHint')}
                placeholder={t('curves.dayCountDefault', {
                  dayCount: t(`curves.dayCounts.${defaultDayCount(form.values.currency)}`),
                })}
                data={DAY_COUNTS.map((value) => ({ value, label: t(`curves.dayCounts.${value}`) }))}
                clearable
                data-testid="curve-day-count"
                value={form.values.dayCount || null}
                onChange={(value) => form.setFieldValue('dayCount', value ?? '')}
                error={form.errors.dayCount}
              />
            )}
          </SimpleGrid>
          <MultiSelect
            label={t('curves.quoteTypes')}
            data={QUOTE_TYPES}
            data-testid="curve-quote-types"
            {...form.getInputProps('quoteTypes')}
          />
          <SimpleGrid cols={{ base: 1, sm: 3 }}>
            <TextInput
              label={t('curves.stalenessDays')}
              inputMode="numeric"
              data-testid="curve-staleness"
              {...form.getInputProps('stalenessDays')}
            />
            <Select
              label={t('curves.proxyCurve')}
              description={t('curves.proxyHint')}
              data={proxies.map((curve) => ({ value: curve.code, label: curve.code }))}
              clearable
              data-testid="curve-proxy"
              value={form.values.proxyCurveCode || null}
              onChange={(value) => form.setFieldValue('proxyCurveCode', value ?? '')}
              error={form.errors.proxyCurveCode}
            />
            <TextInput
              label={t('curves.proxySpread')}
              inputMode="decimal"
              disabled={!form.values.proxyCurveCode}
              data-testid="curve-proxy-spread"
              {...form.getInputProps('proxySpread')}
            />
          </SimpleGrid>
          <Group justify="flex-end">
            <Button variant="default" onClick={onClose}>
              {t('common.cancel')}
            </Button>
            <Button type="submit" loading={create.isPending} data-testid="curve-submit">
              {t('curves.newCurve')}
            </Button>
          </Group>
        </Stack>
      </form>
    </Modal>
  );
}

function isNotFound(error: unknown): boolean {
  return Boolean(error) && asProblemError(error).status === 404;
}

/** The official snapshot of the selected curve on the selected date (exactly that date). */
export function OfficialCurve({ code, asOf }: { code: string; asOf: string }) {
  const { t } = useTranslation();
  const official = $api.useQuery(
    'get',
    '/api/v1/market-data/curves/{code}/official',
    { params: { path: { code }, query: { asOf } } },
    { enabled: Boolean(code && asOf), retry: false },
  );
  return (
    <Card withBorder data-testid="official-curve">
      <Title order={5} mb="sm">
        {t('curves.officialOn', { date: formatDate(asOf) })}
      </Title>
      {isNotFound(official.error) ? (
        <EmptyState
          title={t('curves.noOfficial')}
          description={t('curves.noOfficialHint')}
          testId="official-curve-none"
        />
      ) : (
        <QueryState isPending={official.isPending && Boolean(code)} error={official.error}>
          {official.data && (
            <Stack gap="sm">
              <Group gap="xs">
                <Text fw={600}>
                  {official.data.curveCode} v{official.data.version}
                </Text>
                <StatusBadge status={official.data.status} data-testid="official-curve-status" />
              </Group>
              <FactGrid
                cols={2}
                facts={[
                  { label: t('curves.points'), value: official.data.points.length },
                  { label: t('curves.gaps'), value: official.data.gaps.length },
                  {
                    label: t('curves.builtBy'),
                    value: `${official.data.builtBy} · ${formatDateTime(official.data.builtAt)}`,
                  },
                  {
                    label: t('curves.signedOffBy'),
                    value: official.data.signedOffBy
                      ? `${official.data.signedOffBy} · ${formatDateTime(official.data.signedOffAt)}`
                      : '—',
                  },
                ]}
              />
              <Anchor
                component={Link}
                to={`/market-data/snapshots/${official.data.id}`}
                size="sm"
                data-testid="official-curve-open"
              >
                {t('curves.openSnapshot')}
              </Anchor>
            </Stack>
          )}
        </QueryState>
      )}
    </Card>
  );
}

function PriceResult({ price }: { price: Schema<'CurvePrice'> }) {
  const { t } = useTranslation();
  return (
    <Stack gap={4} data-testid="price-result">
      <Group gap="xs">
        <Text size="xl" fw={700} className="etrm-figure" data-testid="price-value">
          {formatDecimal(price.value, 2, 6)} {price.currency}
        </Text>
        <Badge variant="outline" color="gray" data-testid="price-origin">
          {t(`curves.origins.${price.origin}`)}
        </Badge>
      </Group>
      <Text size="sm" c="dimmed">
        {t('curves.priceFrom', { curve: price.curveCode, date: formatDate(price.curveDate) })}
      </Text>
      <Anchor component={Link} to={`/market-data/snapshots/${price.snapshotId}`} size="sm" data-testid="price-snapshot">
        {t('curves.openSnapshot')}
      </Anchor>
    </Stack>
  );
}

/** The discount factor to a payment date; without an official discount curve the service answers 1 and says so. */
function DiscountResult({ factor, currency }: { factor: Schema<'DiscountFactor'>; currency: string }) {
  const { t } = useTranslation();
  if (!factor.discounted) {
    return (
      <EmptyState
        title={t('curves.noDiscountCurve', { currency })}
        description={t('curves.noDiscountCurveHint')}
        testId="discount-none"
      />
    );
  }
  return (
    <Stack gap={4} data-testid="discount-result">
      <Group gap="xs">
        <Text size="xl" fw={700} className="etrm-figure" data-testid="discount-value">
          {formatDecimal(factor.value, 6, 6)}
        </Text>
        {factor.origin && (
          <Badge variant="outline" color="gray" data-testid="discount-origin">
            {t(`curves.origins.${factor.origin}`)}
          </Badge>
        )}
      </Group>
      <Text size="sm" className="etrm-figure" data-testid="discount-details">
        {t('curves.discountDetails', {
          rate: factor.rate == null ? '—' : formatDecimal(factor.rate, 2, 6),
          days: formatDecimal(factor.days, 0, 0),
        })}
      </Text>
      {factor.curveCode && (
        <Text size="sm" c="dimmed">
          {t('curves.priceFrom', { curve: factor.curveCode, date: formatDate(factor.curveDate) })}
        </Text>
      )}
      {factor.snapshotId && (
        <Anchor
          component={Link}
          to={`/market-data/snapshots/${factor.snapshotId}`}
          size="sm"
          data-testid="discount-snapshot"
        >
          {t('curves.openSnapshot')}
        </Anchor>
      )}
    </Stack>
  );
}

function LookupOutcome({ lookup }: { lookup: PriceLookup }) {
  const { t } = useTranslation();
  const forward = $api.useQuery(
    'get',
    '/api/v1/market-data/curves/forward-price',
    { params: { query: lookup.kind === 'forward' ? lookup.query : { product: '', delivery: '', asOf: '' } } },
    { enabled: lookup.kind === 'forward', retry: false },
  );
  const vintage = $api.useQuery(
    'get',
    '/api/v1/market-data/curves/vintage-price',
    { params: { query: lookup.kind === 'vintage' ? lookup.query : { bucket: '', year: 0, asOf: '' } } },
    { enabled: lookup.kind === 'vintage', retry: false },
  );
  const discount = $api.useQuery(
    'get',
    '/api/v1/market-data/curves/discount-factor',
    { params: { query: lookup.kind === 'discount' ? lookup.query : { currency: '', payDate: '', asOf: '' } } },
    { enabled: lookup.kind === 'discount', retry: false },
  );
  if (lookup.kind === 'discount') {
    return (
      <QueryState isPending={discount.isPending} error={discount.error}>
        {discount.data && <DiscountResult factor={discount.data} currency={lookup.query.currency} />}
      </QueryState>
    );
  }
  const result = lookup.kind === 'forward' ? forward : vintage;
  if (isNotFound(result.error)) {
    return <EmptyState title={t('curves.noPrice')} description={t('curves.noPriceHint')} testId="price-none" />;
  }
  return (
    <QueryState isPending={result.isPending} error={result.error}>
      {result.data && <PriceResult price={result.data} />}
    </QueryState>
  );
}

/** The price valuation would read: an EUA forward for a delivery date, a GoO vintage, or a discount factor to a payment date, as of a date. */
export function PriceLookupPanel({ curves, asOf }: { curves: readonly Curve[]; asOf: string }) {
  const { t } = useTranslation();
  const options = lookupOptions(curves);
  const [form, setForm] = useState<PriceLookupForm>(() => ({
    kind: 'forward',
    product: options.products[0] ?? 'EUA',
    delivery: `${dayjs().year() + 1}-12-15`,
    bucket: options.buckets[0] ?? '',
    year: String(dayjs().year()),
    currency: options.currencies[0] ?? 'EUR',
    payDate: dayjs().add(3, 'month').format('YYYY-MM-DD'),
    asOf,
  }));
  const [lookup, setLookup] = useState<PriceLookup | null>(null);
  const request = priceLookup(form);
  const set = (patch: Partial<PriceLookupForm>) => setForm({ ...form, ...patch });
  return (
    <Card withBorder data-testid="price-lookup">
      <Title order={5}>{t('curves.priceLookup')}</Title>
      <Text size="sm" c="dimmed" mb="sm">
        {t('curves.priceLookupHint')}
      </Text>
      <Stack gap="sm">
        <SegmentedControl
          value={form.kind}
          onChange={(kind) => set({ kind: kind })}
          data={[
            { value: 'forward', label: t('curves.forward') },
            { value: 'vintage', label: t('curves.vintage') },
            { value: 'discount', label: t('curves.discountFactor') },
          ]}
          data-testid="price-kind"
        />
        <Group align="flex-end">
          {form.kind === 'forward' ? (
            <>
              <Select
                label={t('curves.product')}
                data={options.products}
                value={form.product || null}
                onChange={(product) => set({ product: product ?? '' })}
                w={140}
                data-testid="price-product"
              />
              <TextInput
                type="date"
                label={t('curves.delivery')}
                value={form.delivery}
                onChange={(e) => set({ delivery: e.currentTarget.value })}
                data-testid="price-delivery"
              />
            </>
          ) : form.kind === 'discount' ? (
            <Group align="flex-end" data-testid="discount-lookup">
              <TextInput
                label={t('marketData.currency')}
                maxLength={3}
                value={form.currency}
                onChange={(e) => set({ currency: e.currentTarget.value.toUpperCase() })}
                w={100}
                data-testid="discount-currency"
              />
              <TextInput
                type="date"
                label={t('curves.payDate')}
                value={form.payDate}
                onChange={(e) => set({ payDate: e.currentTarget.value })}
                data-testid="discount-pay-date"
              />
            </Group>
          ) : (
            <>
              <Select
                label={t('curves.bucket')}
                data={options.buckets}
                value={form.bucket || null}
                onChange={(bucket) => set({ bucket: bucket ?? '' })}
                searchable
                w={200}
                data-testid="price-bucket"
              />
              <TextInput
                label={t('curves.year')}
                inputMode="numeric"
                maxLength={4}
                value={form.year}
                onChange={(e) => set({ year: e.currentTarget.value })}
                w={120}
                data-testid="price-year"
              />
            </>
          )}
          <TextInput
            type="date"
            label={t('curves.asOf')}
            value={form.asOf}
            onChange={(e) => set({ asOf: e.currentTarget.value })}
            data-testid="price-as-of"
          />
          <Button
            variant="light"
            leftSection={<IconSearch size={16} />}
            disabled={!request}
            onClick={() => setLookup(request)}
            data-testid="price-look-up"
          >
            {t('curves.lookUp')}
          </Button>
        </Group>
        {lookup && <LookupOutcome key={JSON.stringify(lookup)} lookup={lookup} />}
      </Stack>
    </Card>
  );
}
