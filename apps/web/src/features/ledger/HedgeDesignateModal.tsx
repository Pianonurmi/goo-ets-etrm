import {
  Alert,
  Anchor,
  Button,
  Group,
  Modal,
  MultiSelect,
  Select,
  SegmentedControl,
  SimpleGrid,
  Stack,
  Text,
  TextInput,
  Textarea,
  Title,
} from '@mantine/core';
import { useForm } from '@mantine/form';
import { useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useNavigate } from 'react-router';
import { $api } from '@/shared/api/client';
import { asProblemError } from '@/shared/api/problem';
import { notifyProblem, notifySuccess } from '@/shared/components/notify';
import { applyProblemToForm } from '@/shared/forms/applyProblemToForm';
import { formatDecimal, formatQuantity } from '@/shared/format/format';
import { useAllowanceProducts } from '@/features/trades/hooks';
import { useInvalidateLedger } from './hooks';
import {
  HEDGE_CURRENCIES,
  HEDGE_FORM_FIELDS,
  INSTRUMENT_STATUSES,
  eligibleTrades,
  emptyHedge,
  flattenViolations,
  instrumentSide,
  toDesignation,
  validateHedge,
  withDirection,
  withFamily,
  withInstruments,
  type HedgeFamily,
  type HedgeFormValues,
  type TradeSummary,
} from './model/hedge';

/** Open trades of the entity and family, found with the blotter's search (`trade_search`), page by page. */
function useOpenTrades(entityId: string, family: HedgeFamily) {
  const search = $api.useInfiniteQuery(
    'get',
    '/api/v1/trades/search',
    {
      params: {
        query: { legalEntityId: entityId, status: INSTRUMENT_STATUSES, productFamily: family, limit: 200 },
      },
    },
    {
      initialPageParam: null as string | null,
      getNextPageParam: (page) => page.nextCursor ?? undefined,
      pageParamName: 'cursor',
    },
  );
  const items = useMemo(() => search.data?.pages.flatMap((page) => page.items) ?? [], [search.data]);
  return { search, items };
}

function tradeLabel(trade: TradeSummary): string {
  return [
    trade.tradeNumber,
    trade.product,
    formatQuantity(trade.quantity, trade.unit),
    `${formatDecimal(trade.price, 2, 6)} ${trade.priceCurrency}`,
  ].join(' · ');
}

/**
 * Proposes a cash flow hedge (ADR 0045): the forecast transaction, the forwards that hedge it with a proportion of
 * each, and the objective for the documentation. Nothing applies until a second accountant approves in the inbox.
 */
export function HedgeDesignateModal({ entityId, onClose }: { entityId: string; onClose: () => void }) {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const invalidate = useInvalidateLedger();
  const [unplaced, setUnplaced] = useState<string[]>([]);
  const allowances = useAllowanceProducts();
  const buckets = $api.useQuery(
    'get',
    '/api/v1/products/buckets',
    { params: { query: { activeOnly: true } } },
    { staleTime: 5 * 60_000 },
  );
  const form = useForm<HedgeFormValues>({
    initialValues: emptyHedge(),
    validate: (values) =>
      Object.fromEntries(Object.entries(validateHedge(values)).map(([field, key]) => [field, t(key)])),
  });
  const { direction, productFamily: family } = form.values;
  const { search: trades, items: openTrades } = useOpenTrades(entityId, family);
  const candidates = eligibleTrades(openTrades, direction, family, form.values.allowanceProductCode);
  const side = instrumentSide(direction);
  const sideText = t(`hedges.sides.${side}`);
  const designate = $api.useMutation('post', '/api/v1/ledger/hedges', { meta: { notify: false } });

  const submit = form.onSubmit((values) => {
    setUnplaced([]);
    designate.mutate(
      { body: toDesignation(values, entityId) },
      {
        onSuccess: (hedge) => {
          notifySuccess(t('hedges.designated', { number: hedge.number }));
          void invalidate();
          onClose();
          void navigate(`/ledger/hedges/${hedge.id}`);
        },
        onError: (error) => {
          const problem = asProblemError(error);
          if (problem.status === 422) {
            const rest = applyProblemToForm(form, flattenViolations(problem), HEDGE_FORM_FIELDS);
            setUnplaced([problem.message, ...rest.map((violation) => `${violation.field}: ${violation.message}`)]);
          } else {
            notifyProblem(problem);
          }
        },
      },
    );
  });

  const productOptions = allowances
    .filter((product) => product.active)
    .map((product) => ({ value: product.code, label: `${product.code} · ${product.name}` }));
  const bucketOptions = (buckets.data ?? []).map((bucket) => ({
    value: bucket.code,
    label: `${bucket.code} · ${bucket.name}`,
  }));
  const tradeOptions = candidates.map((trade) => ({ value: trade.id, label: tradeLabel(trade) }));
  const chosen = new Map(candidates.map((trade) => [trade.id, trade]));

  return (
    <Modal opened onClose={onClose} size="xl" title={t('hedges.designateTitle')} data-testid="hedge-designate-modal">
      <form onSubmit={submit} noValidate>
        <Stack>
          {unplaced.length > 0 && (
            <Alert color="red" title={t('errors.validation')} data-testid="hedge-form-problem">
              {unplaced.map((line) => (
                <Text key={line} size="sm">
                  {line}
                </Text>
              ))}
            </Alert>
          )}
          <Text size="sm" c="dimmed">
            {t('hedges.designateIntro')}
          </Text>
          <div>
            <Title order={4} size="h5">
              {t('hedges.form.itemTitle')}
            </Title>
            <Text size="xs" c="dimmed">
              {t('hedges.form.itemHint')}
            </Text>
          </div>
          <SimpleGrid cols={{ base: 1, sm: 2 }}>
            <Stack gap={4}>
              <Text size="sm" fw={500}>
                {t('hedges.form.direction')}
              </Text>
              <SegmentedControl
                data={(['PURCHASE', 'SALE'] as const).map((value) => ({
                  value,
                  label: t(`hedges.directions.${value}`),
                }))}
                value={direction}
                onChange={(value) => form.setValues(withDirection(form.values, value))}
                fullWidth
                data-testid="hedge-direction"
              />
            </Stack>
            <Stack gap={4}>
              <Text size="sm" fw={500}>
                {t('hedges.form.family')}
              </Text>
              <SegmentedControl
                data={(['EMISSION_ALLOWANCE', 'GUARANTEE_OF_ORIGIN'] as const).map((value) => ({
                  value,
                  label: t(`hedges.families.${value}`),
                }))}
                value={family}
                onChange={(value) => form.setValues(withFamily(form.values, value))}
                fullWidth
                data-testid="hedge-family"
              />
            </Stack>
          </SimpleGrid>
          <SimpleGrid cols={{ base: 1, sm: 2 }}>
            {family === 'EMISSION_ALLOWANCE' ? (
              <Select
                label={t('hedges.form.allowanceProduct')}
                data={productOptions}
                allowDeselect={false}
                searchable
                data-testid="hedge-product"
                {...form.getInputProps('allowanceProductCode')}
              />
            ) : (
              <Select
                label={t('hedges.form.bucket')}
                data={bucketOptions}
                searchable
                data-testid="hedge-bucket"
                {...form.getInputProps('bucketCode')}
              />
            )}
            {family === 'GUARANTEE_OF_ORIGIN' && (
              <TextInput
                label={t('hedges.form.productionYear')}
                inputMode="numeric"
                maxLength={4}
                data-testid="hedge-production-year"
                {...form.getInputProps('productionYear')}
              />
            )}
          </SimpleGrid>
          <SimpleGrid cols={{ base: 1, sm: 3 }}>
            <TextInput
              label={t('hedges.form.quantityUnit', { unit: t(`hedges.units.${family}`) })}
              inputMode="decimal"
              data-testid="hedge-quantity"
              {...form.getInputProps('quantity')}
            />
            <TextInput
              type="date"
              label={t('hedges.form.expectedDate')}
              data-testid="hedge-expected-date"
              {...form.getInputProps('expectedDate')}
            />
            <Select
              label={t('hedges.form.currency')}
              data={HEDGE_CURRENCIES}
              allowDeselect={false}
              data-testid="hedge-currency"
              {...form.getInputProps('currency')}
            />
          </SimpleGrid>
          <Textarea
            label={t('hedges.form.objective')}
            description={t('hedges.form.objectiveHint')}
            autosize
            minRows={3}
            data-testid="hedge-objective"
            {...form.getInputProps('objective')}
          />
          <div>
            <Title order={4} size="h5">
              {t('hedges.form.instrumentsTitle')}
            </Title>
            <Text size="xs" c="dimmed">
              {t('hedges.form.instrumentsHint', { side: sideText })}
            </Text>
          </div>
          <MultiSelect
            label={t('hedges.form.trades')}
            data={tradeOptions}
            value={form.values.instruments.map((instrument) => instrument.tradeId)}
            onChange={(ids) => form.setValues(withInstruments(form.values, ids))}
            error={form.errors.instruments}
            placeholder={t('hedges.form.tradesPlaceholder')}
            nothingFoundMessage={
              trades.isPending ? t('hedges.form.loadingTrades') : t('hedges.form.noTrades', { side: sideText })
            }
            searchable
            clearable
            data-testid="hedge-instruments"
          />
          {trades.hasNextPage && (
            <Anchor
              component="button"
              type="button"
              size="sm"
              ta="left"
              onClick={() => void trades.fetchNextPage()}
              data-testid="hedge-more-trades"
            >
              {trades.isFetchingNextPage ? t('app.loading') : t('hedges.form.moreTrades')}
            </Anchor>
          )}
          {form.values.instruments.map((instrument, index) => {
            const trade = chosen.get(instrument.tradeId);
            return (
              <Group key={instrument.tradeId} align="flex-start" wrap="nowrap" data-testid="hedge-instrument-row">
                <Text size="sm" fw={500} pt={34} style={{ flex: 1 }} truncate>
                  {trade ? tradeLabel(trade) : instrument.tradeId}
                </Text>
                <TextInput
                  label={t('hedges.form.proportion')}
                  description={t('hedges.form.proportionHint')}
                  inputMode="decimal"
                  w={160}
                  data-testid="hedge-proportion"
                  {...form.getInputProps(`instruments.${index}.proportion`)}
                />
              </Group>
            );
          })}
          <Text size="sm" c="dimmed">
            {t('hedges.form.fourEyes')}
          </Text>
          <Group justify="flex-end">
            <Button variant="default" onClick={onClose}>
              {t('common.cancel')}
            </Button>
            <Button type="submit" loading={designate.isPending} data-testid="hedge-designate-submit">
              {t('hedges.form.submit')}
            </Button>
          </Group>
        </Stack>
      </form>
    </Modal>
  );
}
