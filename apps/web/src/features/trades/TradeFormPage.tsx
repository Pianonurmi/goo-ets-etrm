import {
  ActionIcon,
  Alert,
  Autocomplete,
  Button,
  Divider,
  Group,
  Paper,
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
import { IconPlus, IconTrash } from '@tabler/icons-react';
import { useQueryClient } from '@tanstack/react-query';
import dayjs from 'dayjs';
import { useState, type ReactNode } from 'react';
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
import {
  useAllowanceProducts,
  useBooks,
  useCounterparties,
  useEntityAccounts,
  useFuturesContracts,
  usePriceIndices,
} from './hooks';
import {
  brokersOf,
  counterpartiesFor,
  emptyDelivery,
  emptyLeg,
  emptyTrade,
  FORM_FIELDS,
  fromTrade,
  isIntercompany,
  toAmendTrade,
  toCaptureTrade,
  unitOf,
  validateTrade,
  withFuture,
  type InstrumentType,
  type ProductFamily,
  type TradeFormValues,
  type TradeView,
} from './model/tradeForm';

const INSTRUMENTS = ['SPOT', 'FORWARD', 'FUTURE', 'OPTION', 'SWAP', 'AUCTION', 'INTERNAL_TRANSFER'] as const;
const CURRENCIES = ['EUR', 'SEK', 'NOK', 'DKK', 'PLN', 'CHF', 'GBP'];

/** Keeps the actions in reach at the bottom of the viewport while the form scrolls. */
const FOOTER = { position: 'sticky', bottom: 12, zIndex: 3, boxShadow: 'var(--mantine-shadow-sm)' } as const;

/** A titled part of the form with a line of help: what, with whom, then each leg. */
function FormSection({ title, description, children }: { title: string; description?: string; children: ReactNode }) {
  return (
    <Paper withBorder radius="lg" p="lg">
      <Stack gap="md">
        <div>
          <Title order={3} size="h5">
            {title}
          </Title>
          {description && (
            <Text size="sm" c="dimmed">
              {description}
            </Text>
          )}
        </div>
        {children}
      </Stack>
    </Paper>
  );
}

/** A part of a leg: terms, certificates, accounts, deliveries. */
function SubSection({ title, description, children }: { title: string; description?: string; children: ReactNode }) {
  return (
    <Stack gap="xs">
      <Divider
        label={
          <Text size="sm" fw={600}>
            {title}
          </Text>
        }
        labelPosition="left"
      />
      {description && (
        <Text size="xs" c="dimmed" mt={-4}>
          {description}
        </Text>
      )}
      {children}
    </Stack>
  );
}

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
    validate: (values) =>
      validateTrade(values, {
        amend: amending,
        intercompany: isIntercompany(counterparties.byId.get(values.counterpartyId)),
      }),
  });
  const instrument = form.values.instrumentType;
  const future = instrument === 'FUTURE';
  const counterparty = counterparties.byId.get(form.values.counterpartyId);
  const intercompany = isIntercompany(counterparty);
  const mirrorBooks = useBooks(
    intercompany && !amending ? (counterparty?.linkedLegalEntityId ?? undefined) : undefined,
  );
  const contracts = useFuturesContracts(future);
  const indices = usePriceIndices(!future);
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

  const tradesFuturesWith = (id: string) =>
    counterpartiesFor(counterparties.list, 'FUTURE').some((candidate) => candidate.id === id);
  const setInstrument = (next: InstrumentType) => {
    form.setFieldValue('instrumentType', next);
    if (next === 'FUTURE') {
      setFamily('EMISSION_ALLOWANCE');
      if (!tradesFuturesWith(form.values.counterpartyId)) form.setFieldValue('counterpartyId', '');
    }
  };
  const chooseContract = (index: number, code: string | null) => {
    const contract = contracts.find((candidate) => candidate.code === code);
    const leg = form.values.legs[index];
    if (!contract || !leg) return;
    form.setFieldValue(`legs.${index}`, withFuture(leg, contract));
    form.setFieldValue('venueMic', contract.venueMic);
  };

  const units = family === 'EMISSION_ALLOWANCE' ? t('trades.form.unitsEua') : t('trades.form.unitsGoo');
  return (
    <form onSubmit={submit} noValidate>
      <Stack gap="lg">
        {unplaced.length > 0 && (
          <Alert color="red" title={t('errors.validation')} data-testid="form-problem">
            {unplaced.map((line) => (
              <Text key={line} size="sm">
                {line}
              </Text>
            ))}
          </Alert>
        )}
        <FormSection title={t('trades.form.whatTitle')} description={t('trades.form.whatHint')}>
          <SimpleGrid cols={{ base: 1, sm: 2, lg: 4 }}>
            <Stack gap={4}>
              <Text size="sm" fw={500}>
                {t('trades.family')}
              </Text>
              <SegmentedControl
                data={[
                  { value: 'GUARANTEE_OF_ORIGIN', label: t('trades.familiesShort.GUARANTEE_OF_ORIGIN') },
                  { value: 'EMISSION_ALLOWANCE', label: t('trades.familiesShort.EMISSION_ALLOWANCE') },
                ]}
                value={family}
                onChange={(value) => setFamily(value)}
                disabled={amending || future}
                fullWidth
              />
            </Stack>
            <Select
              label={t('trades.columns.instrument')}
              data={INSTRUMENTS.map((value) => ({ value, label: t(`trades.instruments.${value}`) }))}
              allowDeselect={false}
              disabled={amending}
              data-testid="field-instrument"
              {...form.getInputProps('instrumentType')}
              onChange={(value: string | null) => {
                if (value) setInstrument(value as InstrumentType);
              }}
            />
            <TextInput type="date" label={t('trades.columns.date')} required {...form.getInputProps('tradeDate')} />
            {family === 'EMISSION_ALLOWANCE' && (
              <TextInput
                type="datetime-local"
                label={t('trades.form.executionTimestamp')}
                {...form.getInputProps('executionTimestamp')}
              />
            )}
            {family === 'EMISSION_ALLOWANCE' && (
              <TextInput label={t('trades.form.venueMic')} placeholder="XOFF" {...form.getInputProps('venueMic')} />
            )}
          </SimpleGrid>
        </FormSection>

        <FormSection title={t('trades.form.whoTitle')} description={t('trades.form.whoHint')}>
          <SimpleGrid cols={{ base: 1, sm: 2, lg: 4 }}>
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
              data={counterpartiesFor(counterparties.list, instrument).map((cp) => ({
                value: cp.id,
                label: `${cp.name} (${cp.code})`,
              }))}
              description={future ? t('trades.form.futureCounterparty') : undefined}
              required
              disabled={amending}
              searchable
              data-testid="field-counterparty"
              {...form.getInputProps('counterpartyId')}
            />
            <Select
              label={t('trades.form.broker')}
              description={t('trades.form.brokerHint')}
              data={brokersOf(counterparties.list).map((cp) => ({ value: cp.id, label: `${cp.name} (${cp.code})` }))}
              clearable
              data-testid="field-broker"
              {...form.getInputProps('brokerId')}
              onChange={(value: string | null) => form.setFieldValue('brokerId', value ?? '')}
            />
            <Autocomplete
              label={t('trades.form.masterAgreement')}
              description={t('trades.form.masterAgreementHint')}
              data={(counterparty?.masterAgreements ?? []).map((agreement) => agreement.reference)}
              data-testid="field-master-agreement"
              {...form.getInputProps('masterAgreementRef')}
            />
            {intercompany && !amending && (
              <Select
                label={t('trades.form.mirrorBook')}
                description={t('trades.form.mirrorBookHint')}
                data={mirrorBooks.list.map((book) => ({ value: book.id, label: `${book.code} · ${book.name}` }))}
                required
                data-testid="field-mirror-book"
                {...form.getInputProps('mirrorBookId')}
              />
            )}
            <TextInput
              label={t('trades.columns.trader')}
              placeholder={t('trades.form.traderHint')}
              disabled={amending}
              {...form.getInputProps('trader')}
            />
            <TextInput label={t('trades.columns.externalRef')} {...form.getInputProps('externalRef')} />
          </SimpleGrid>
        </FormSection>

        {form.values.legs.map((leg, index) => (
          <Paper withBorder radius="lg" p="lg" key={index} data-testid={`leg-${index}`}>
            <Group justify="space-between" align="flex-start" mb="md">
              <div>
                <Title order={3} size="h5">
                  {t('trades.form.leg', { number: index + 1 })}
                </Title>
                <Text size="sm" c="dimmed">
                  {t('trades.form.legHint', { units })}
                </Text>
              </div>
              {form.values.legs.length > 1 && (
                <Button
                  variant="subtle"
                  color="red"
                  size="xs"
                  leftSection={<IconTrash size={14} />}
                  onClick={() => form.removeListItem('legs', index)}
                >
                  {t('trades.form.removeLeg')}
                </Button>
              )}
            </Group>
            <Stack gap="lg">
              <SubSection title={t('trades.form.terms')}>
                <SimpleGrid cols={{ base: 1, sm: 2, lg: 4 }}>
                  <Select
                    label={t('trades.columns.side')}
                    data={[
                      { value: 'BUY', label: t('trades.form.buy') },
                      { value: 'SELL', label: t('trades.form.sell') },
                    ]}
                    allowDeselect={false}
                    {...form.getInputProps(`legs.${index}.side`)}
                  />
                  {future && (
                    <Select
                      label={t('trades.form.futuresContract')}
                      data={contracts.map((contract) => ({
                        value: contract.code,
                        label: `${contract.code} · ${contract.deliveryMonth} · ${contract.venueMic}`,
                      }))}
                      required
                      searchable
                      data-testid={`leg-${index}-contract`}
                      {...form.getInputProps(`legs.${index}.futuresContractCode`)}
                      onChange={(code: string | null) => chooseContract(index, code)}
                    />
                  )}
                  {family === 'EMISSION_ALLOWANCE' && (
                    <Select
                      label={t('trades.form.allowanceProduct')}
                      disabled={future}
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
                  {!future && (
                    <Stack gap={4}>
                      <Text size="sm" fw={500}>
                        {t('trades.form.priceType')}
                      </Text>
                      <SegmentedControl
                        data={[
                          { value: 'FIXED', label: t('trades.form.fixed') },
                          { value: 'INDEX', label: t('trades.form.index') },
                        ]}
                        data-testid={`leg-${index}-price-type`}
                        value={leg.priceType}
                        onChange={(value) => form.setFieldValue(`legs.${index}.priceType`, value)}
                        fullWidth
                      />
                    </Stack>
                  )}
                  {!future && leg.priceType === 'INDEX' && (
                    <Select
                      label={t('trades.form.priceIndex')}
                      data={indices.map((priceIndex) => ({
                        value: priceIndex.code,
                        label: `${priceIndex.code} · ${priceIndex.description}`,
                      }))}
                      required
                      data-testid={`leg-${index}-index`}
                      {...form.getInputProps(`legs.${index}.priceIndex`)}
                    />
                  )}
                  <TextInput
                    label={!future && leg.priceType === 'INDEX' ? t('trades.form.spread') : t('trades.columns.price')}
                    inputMode="decimal"
                    required
                    data-testid={`leg-${index}-price`}
                    {...form.getInputProps(`legs.${index}.price`)}
                  />
                  <Select
                    label={t('trades.form.currency')}
                    data={CURRENCIES}
                    allowDeselect={false}
                    disabled={future}
                    {...form.getInputProps(`legs.${index}.priceCurrency`)}
                  />
                </SimpleGrid>
              </SubSection>
              {family === 'GUARANTEE_OF_ORIGIN' && (
                <SubSection title={t('trades.form.criteria')} description={t('trades.form.criteriaHint')}>
                  <CriteriaBuilder
                    value={leg.criterion}
                    onChange={(criterion) => form.setFieldValue(`legs.${index}.criterion`, criterion)}
                  />
                </SubSection>
              )}
              {family === 'GUARANTEE_OF_ORIGIN' && (
                <SubSection title={t('trades.form.accounts')} description={t('trades.form.accountsHint')}>
                  <SimpleGrid cols={{ base: 1, sm: 2, lg: 4 }}>
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
                  </SimpleGrid>
                </SubSection>
              )}
              <SubSection
                title={t('trades.form.deliveries')}
                description={future ? undefined : t('trades.form.deliveriesHint')}
              >
                {future && (
                  <Text size="sm" c="dimmed" data-testid={`leg-${index}-future-delivery`}>
                    {leg.futuresContractCode
                      ? t('trades.form.futureDelivery', {
                          date: leg.deliveries[0]?.deliveryDate ?? '',
                          size:
                            contracts.find((contract) => contract.code === leg.futuresContractCode)?.contractSize ?? '',
                        })
                      : t('trades.form.futureChooseContract')}
                  </Text>
                )}
                {!future && (
                  <Stack gap="xs">
                    {leg.deliveries.map((_, d) => (
                      <Group key={d} align="flex-end" wrap="wrap" gap="sm">
                        <TextInput
                          type="date"
                          label={t('trades.form.windowStart')}
                          w={170}
                          {...form.getInputProps(`legs.${index}.deliveries.${d}.start`)}
                        />
                        <TextInput
                          type="date"
                          label={t('trades.form.windowEnd')}
                          w={190}
                          {...form.getInputProps(`legs.${index}.deliveries.${d}.endExclusive`)}
                        />
                        <TextInput
                          type="date"
                          label={t('trades.form.deliveryDate')}
                          w={170}
                          {...form.getInputProps(`legs.${index}.deliveries.${d}.deliveryDate`)}
                        />
                        <TextInput
                          label={t('trades.form.quantity', { unit: unitOf(family) })}
                          inputMode="decimal"
                          w={170}
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
                )}
              </SubSection>
            </Stack>
          </Paper>
        ))}
        <Group>
          <Button
            variant="default"
            leftSection={<IconPlus size={16} />}
            onClick={() => form.insertListItem('legs', emptyLeg(family, today))}
          >
            {t('trades.form.addLeg')}
          </Button>
        </Group>
        {amending && (
          <FormSection title={t('trades.form.reason')} description={t('trades.form.reasonHint')}>
            <Textarea
              aria-label={t('trades.form.reason')}
              required
              autosize
              minRows={2}
              {...form.getInputProps('reason')}
            />
          </FormSection>
        )}
        <Paper withBorder radius="lg" p="sm" style={FOOTER}>
          <Group justify="space-between" wrap="nowrap">
            <Text size="sm" c="dimmed" visibleFrom="sm">
              {amending ? t('trades.form.amendHint') : t('trades.form.newHint')}
            </Text>
            <Group gap="xs" wrap="nowrap" ml="auto">
              <Button variant="default" component={Link} to={existing ? `/trades/${existing.id}` : '/trades'}>
                {t('common.cancel')}
              </Button>
              <Button type="submit" loading={capture.isPending || amend.isPending} data-testid="submit-trade">
                {amending ? t('trades.form.saveAmendment') : t('trades.form.capture')}
              </Button>
            </Group>
          </Group>
        </Paper>
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
  const number = trade.data?.tradeNumber ?? '';
  return (
    <Stack gap="lg">
      <PageHeader
        breadcrumbs={
          tradeId
            ? [
                { label: t('trades.title'), to: '/trades' },
                { label: number, to: `/trades/${tradeId}` },
                { label: t('trades.amend') },
              ]
            : [{ label: t('trades.title'), to: '/trades' }, { label: t('trades.form.newTitle') }]
        }
        title={tradeId ? t('trades.form.amendTitle', { number }) : t('trades.form.newTitle')}
        description={tradeId ? t('trades.form.amendLead') : t('trades.form.newLead')}
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
