import {
  ActionIcon,
  Alert,
  Anchor,
  Button,
  Card,
  Divider,
  Group,
  SegmentedControl,
  Select,
  SimpleGrid,
  Stack,
  Text,
  TextInput,
  Textarea,
  Title,
} from '@mantine/core';
import { useForm } from '@mantine/form';
import { IconArrowLeft, IconPlus, IconTrash } from '@tabler/icons-react';
import { useQueryClient } from '@tanstack/react-query';
import dayjs from 'dayjs';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link, useNavigate, useParams } from 'react-router';
import { $api } from '@/shared/api/client';
import { asProblemError } from '@/shared/api/problem';
import { notifyProblem, notifySuccess } from '@/shared/components/notify';
import { PageHeader } from '@/shared/components/PageHeader';
import { QueryState } from '@/shared/components/QueryState';
import { useEntity } from '@/shared/context/EntityContext';
import { applyProblemToForm } from '@/shared/forms/applyProblemToForm';
import { CriteriaBuilder } from '@/features/criteria/CriteriaBuilder';
import { useAllowanceProducts, useBooks, useCounterparties, useEntityAccounts } from './hooks';
import {
  emptyDelivery,
  emptyLeg,
  emptyTrade,
  FORM_FIELDS,
  fromTrade,
  toAmendTrade,
  toCaptureTrade,
  unitOf,
  validateTrade,
  type ProductFamily,
  type TradeFormValues,
  type TradeView,
} from './model/tradeForm';

const INSTRUMENTS = ['SPOT', 'FORWARD', 'FUTURE', 'OPTION', 'SWAP', 'AUCTION', 'INTERNAL_TRANSFER'] as const;
const CURRENCIES = ['EUR', 'SEK', 'NOK', 'DKK', 'PLN', 'CHF', 'GBP'];

function TradeForm({ existing }: { existing?: TradeView }) {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const { entity } = useEntity();
  const entityId = existing?.legalEntityId ?? entity?.id;
  const today = dayjs().format('YYYY-MM-DD');
  const counterparties = useCounterparties();
  const books = useBooks(entityId);
  const accounts = useEntityAccounts(entityId);
  const allowances = useAllowanceProducts();
  const [unplaced, setUnplaced] = useState<string[]>([]);
  const amending = existing !== undefined;

  const form = useForm<TradeFormValues>({
    initialValues: existing ? fromTrade(existing) : emptyTrade(today),
    validate: (values) => validateTrade(values, amending),
  });
  const capture = $api.useMutation('post', '/api/v1/trades', { meta: { notify: false } });
  const amend = $api.useMutation('put', '/api/v1/trades/{id}', { meta: { notify: false } });
  const family = form.values.productFamily;

  const failed = (error: unknown) => {
    const problem = asProblemError(error);
    if (problem.status === 422) {
      const rest = applyProblemToForm(form, problem, FORM_FIELDS);
      setUnplaced([problem.message, ...rest.map((v) => `${v.field}: ${v.message}`)]);
    } else {
      notifyProblem(problem);
    }
  };
  const saved = (trade: TradeView) => {
    void queryClient.invalidateQueries({
      predicate: (q) => typeof q.queryKey[1] === 'string' && q.queryKey[1].startsWith('/api/v1/trades'),
    });
    notifySuccess(
      amending
        ? t('trades.amended', { number: trade.tradeNumber })
        : t('trades.captured', { number: trade.tradeNumber }),
    );
    void navigate(`/trades/${trade.id}`);
  };
  const submit = form.onSubmit((values) => {
    setUnplaced([]);
    if (existing) {
      amend.mutate(
        { params: { path: { id: existing.id } }, body: toAmendTrade(values, existing.version) },
        { onSuccess: saved, onError: failed },
      );
    } else if (entityId) {
      capture.mutate({ body: toCaptureTrade(values, entityId) }, { onSuccess: saved, onError: failed });
    }
  });

  const setFamily = (next: ProductFamily) => {
    form.setFieldValue('productFamily', next);
    form.setFieldValue(
      'legs',
      form.values.legs.map((leg) => ({
        ...leg,
        allowanceProductCode: next === 'EMISSION_ALLOWANCE' ? leg.allowanceProductCode || 'EUA' : '',
      })),
    );
  };

  return (
    <form onSubmit={submit} noValidate>
      <Stack>
        {unplaced.length > 0 && (
          <Alert color="red" title={t('errors.validation')} data-testid="form-problem">
            {unplaced.map((line) => (
              <Text key={line} size="sm">
                {line}
              </Text>
            ))}
          </Alert>
        )}
        <Card withBorder>
          <Title order={5} mb="sm">
            {t('trades.form.header')}
          </Title>
          <SimpleGrid cols={{ base: 1, sm: 2, lg: 4 }}>
            <Stack gap={4}>
              <Text size="sm" fw={500}>
                {t('trades.family')}
              </Text>
              <SegmentedControl
                data={[
                  { value: 'GUARANTEE_OF_ORIGIN', label: t('trades.families.GUARANTEE_OF_ORIGIN') },
                  { value: 'EMISSION_ALLOWANCE', label: t('trades.families.EMISSION_ALLOWANCE') },
                ]}
                value={family}
                onChange={(value) => setFamily(value)}
                disabled={amending}
              />
            </Stack>
            <Select
              label={t('trades.columns.instrument')}
              data={[...INSTRUMENTS]}
              allowDeselect={false}
              disabled={amending}
              {...form.getInputProps('instrumentType')}
            />
            <Select
              label={t('trades.columns.book')}
              data={books.list.map((book) => ({ value: book.id, label: `${book.code} · ${book.name}` }))}
              required
              disabled={amending}
              searchable
              data-testid="field-book"
              {...form.getInputProps('bookId')}
            />
            <Select
              label={t('trades.columns.counterparty')}
              data={counterparties.list.map((cp) => ({ value: cp.id, label: `${cp.name} (${cp.code})` }))}
              required
              disabled={amending}
              searchable
              data-testid="field-counterparty"
              {...form.getInputProps('counterpartyId')}
            />
            <TextInput type="date" label={t('trades.columns.date')} required {...form.getInputProps('tradeDate')} />
            <TextInput
              label={t('trades.columns.trader')}
              placeholder={t('trades.form.traderHint')}
              disabled={amending}
              {...form.getInputProps('trader')}
            />
            <TextInput label={t('trades.columns.externalRef')} {...form.getInputProps('externalRef')} />
          </SimpleGrid>
          {family === 'EMISSION_ALLOWANCE' && (
            <SimpleGrid cols={{ base: 1, sm: 2, lg: 4 }} mt="sm">
              <TextInput
                type="datetime-local"
                label={t('trades.form.executionTimestamp')}
                {...form.getInputProps('executionTimestamp')}
              />
              <TextInput label={t('trades.form.venueMic')} placeholder="XOFF" {...form.getInputProps('venueMic')} />
            </SimpleGrid>
          )}
        </Card>

        {form.values.legs.map((leg, index) => (
          <Card withBorder key={index} data-testid={`leg-${index}`}>
            <Group justify="space-between" mb="sm">
              <Title order={5}>{t('trades.form.leg', { number: index + 1 })}</Title>
              {form.values.legs.length > 1 && (
                <ActionIcon
                  variant="subtle"
                  color="red"
                  aria-label={t('criteria.remove')}
                  onClick={() => form.removeListItem('legs', index)}
                >
                  <IconTrash size={16} />
                </ActionIcon>
              )}
            </Group>
            <Stack>
              <SimpleGrid cols={{ base: 1, sm: 2, lg: 5 }}>
                <Select
                  label={t('trades.columns.side')}
                  data={['BUY', 'SELL']}
                  allowDeselect={false}
                  {...form.getInputProps(`legs.${index}.side`)}
                />
                {family === 'EMISSION_ALLOWANCE' && (
                  <Select
                    label={t('trades.form.allowanceProduct')}
                    data={allowances.map((product) => ({
                      value: product.code,
                      label: `${product.code} · ${product.name}`,
                    }))}
                    {...form.getInputProps(`legs.${index}.allowanceProductCode`)}
                  />
                )}
                <TextInput
                  label={t('trades.form.quantity', { unit: unitOf(family) })}
                  inputMode="decimal"
                  required
                  data-testid={`leg-${index}-quantity`}
                  {...form.getInputProps(`legs.${index}.quantity`)}
                />
                <TextInput
                  label={t('trades.columns.price')}
                  inputMode="decimal"
                  required
                  data-testid={`leg-${index}-price`}
                  {...form.getInputProps(`legs.${index}.price`)}
                />
                <Select
                  label={t('trades.form.currency')}
                  data={CURRENCIES}
                  allowDeselect={false}
                  {...form.getInputProps(`legs.${index}.priceCurrency`)}
                />
                {family === 'GUARANTEE_OF_ORIGIN' && (
                  <>
                    <Select
                      label={t('trades.form.deliveryAccount')}
                      data={accounts.map((account) => ({
                        value: account.id,
                        label: `${account.registryCode} ${account.accountNumber}`,
                      }))}
                      clearable
                      data-testid={`leg-${index}-account`}
                      {...form.getInputProps(`legs.${index}.deliveryAccountId`)}
                    />
                    <TextInput
                      label={t('trades.form.counterpartyAccount')}
                      description={leg.side === 'SELL' ? t('trades.form.counterpartyAccountHint') : undefined}
                      data-testid={`leg-${index}-cp-account`}
                      placeholder="REGISTRY:ACCOUNT"
                      {...form.getInputProps(`legs.${index}.counterpartyAccountRef`)}
                    />
                  </>
                )}
              </SimpleGrid>
              {family === 'GUARANTEE_OF_ORIGIN' && (
                <Stack gap={4}>
                  <Text size="sm" fw={500}>
                    {t('trades.form.criteria')}
                  </Text>
                  <CriteriaBuilder
                    value={leg.criterion}
                    onChange={(criterion) => form.setFieldValue(`legs.${index}.criterion`, criterion)}
                  />
                </Stack>
              )}
              <Divider label={t('trades.form.deliveries')} labelPosition="left" />
              {leg.deliveries.map((_, d) => (
                <Group key={d} align="flex-end" wrap="wrap">
                  <TextInput
                    type="date"
                    label={t('trades.form.windowStart')}
                    {...form.getInputProps(`legs.${index}.deliveries.${d}.start`)}
                  />
                  <TextInput
                    type="date"
                    label={t('trades.form.windowEnd')}
                    {...form.getInputProps(`legs.${index}.deliveries.${d}.endExclusive`)}
                  />
                  <TextInput
                    type="date"
                    label={t('trades.form.deliveryDate')}
                    {...form.getInputProps(`legs.${index}.deliveries.${d}.deliveryDate`)}
                  />
                  <TextInput
                    label={t('trades.form.quantity', { unit: unitOf(family) })}
                    inputMode="decimal"
                    data-testid={`leg-${index}-delivery-${d}-quantity`}
                    {...form.getInputProps(`legs.${index}.deliveries.${d}.quantity`)}
                  />
                  {leg.deliveries.length > 1 && (
                    <ActionIcon
                      variant="subtle"
                      color="red"
                      mb={4}
                      aria-label={t('criteria.remove')}
                      onClick={() => form.removeListItem(`legs.${index}.deliveries`, d)}
                    >
                      <IconTrash size={16} />
                    </ActionIcon>
                  )}
                </Group>
              ))}
              <Group>
                <Button
                  size="xs"
                  variant="subtle"
                  leftSection={<IconPlus size={14} />}
                  onClick={() => form.insertListItem(`legs.${index}.deliveries`, emptyDelivery(today))}
                >
                  {t('trades.form.addDelivery')}
                </Button>
              </Group>
            </Stack>
          </Card>
        ))}
        <Group>
          <Button
            variant="light"
            leftSection={<IconPlus size={16} />}
            onClick={() => form.insertListItem('legs', emptyLeg(family, today))}
          >
            {t('trades.form.addLeg')}
          </Button>
        </Group>
        {amending && (
          <Textarea
            label={t('trades.form.reason')}
            description={t('trades.form.reasonHint')}
            required
            {...form.getInputProps('reason')}
          />
        )}
        <Group justify="flex-end">
          <Button variant="default" component={Link} to={existing ? `/trades/${existing.id}` : '/trades'}>
            {t('common.cancel')}
          </Button>
          <Button type="submit" loading={capture.isPending || amend.isPending} data-testid="submit-trade">
            {amending ? t('trades.form.saveAmendment') : t('trades.form.capture')}
          </Button>
        </Group>
      </Stack>
    </form>
  );
}

/** Capture (`/trades/new`) or amend (`/trades/:tradeId/amend`). */
export function TradeFormPage() {
  const { t } = useTranslation();
  const { tradeId } = useParams();
  const trade = $api.useQuery(
    'get',
    '/api/v1/trades/{id}',
    { params: { path: { id: tradeId ?? '' } } },
    { enabled: Boolean(tradeId) },
  );
  return (
    <Stack>
      <Anchor component={Link} to={tradeId ? `/trades/${tradeId}` : '/trades'} size="sm">
        <Group gap={4}>
          <IconArrowLeft size={14} />
          {t('trades.back')}
        </Group>
      </Anchor>
      <PageHeader
        title={
          tradeId ? t('trades.form.amendTitle', { number: trade.data?.tradeNumber ?? '' }) : t('trades.form.newTitle')
        }
        description={tradeId ? t('trades.form.amendHint') : t('trades.form.newHint')}
      />
      {tradeId ? (
        <QueryState isPending={trade.isPending} error={trade.error}>
          {trade.data && <TradeForm existing={trade.data} />}
        </QueryState>
      ) : (
        <TradeForm />
      )}
    </Stack>
  );
}
