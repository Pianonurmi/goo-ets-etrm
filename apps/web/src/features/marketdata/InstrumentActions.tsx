import { Button, Group, Modal, Select, SimpleGrid, Stack, Text, TextInput } from '@mantine/core';
import { useForm } from '@mantine/form';
import { useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { $api } from '@/shared/api/client';
import { asProblemError } from '@/shared/api/problem';
import { notifySuccess } from '@/shared/components/notify';
import { ProblemAlert } from '@/shared/components/ProblemAlert';
import { applyProblemToForm } from '@/shared/forms/applyProblemToForm';
import { useAllowanceProducts } from '@/features/trades/hooks';
import {
  emptyInstrument,
  INSTRUMENT_KINDS,
  instrumentFields,
  toCreateInstrument,
  validateInstrument,
  type InstrumentForm,
} from './model/marketData';

const INSTRUMENT_FIELDS = new Set(Object.keys(emptyInstrument()));

export function useRefreshMarketData() {
  const queryClient = useQueryClient();
  return () =>
    queryClient.invalidateQueries({
      predicate: (q) => typeof q.queryKey[1] === 'string' && q.queryKey[1].startsWith('/api/v1/market-data'),
    });
}

export function translated(errors: Record<string, string>, t: (key: string) => string): Record<string, string> {
  return Object.fromEntries(Object.entries(errors).map(([field, key]) => [field, t(key)]));
}

/** Product buckets, for GoO vintages and vintage curves. */
export function useBucketCodes(): { value: string; label: string }[] {
  const buckets = $api.useQuery(
    'get',
    '/api/v1/products/buckets',
    { params: { query: { activeOnly: true } } },
    { staleTime: 5 * 60_000 },
  );
  return (buckets.data ?? []).map((bucket) => ({ value: bucket.code, label: `${bucket.code} · ${bucket.name}` }));
}

/** A new instrument; the kind decides which product, contract or vintage it names. */
export function CreateInstrumentModal({ onClose }: { onClose: () => void }) {
  const { t } = useTranslation();
  const refresh = useRefreshMarketData();
  const allowances = useAllowanceProducts();
  const buckets = useBucketCodes();
  const futures = $api.useQuery('get', '/api/v1/products/futures-contracts', {}, { staleTime: 5 * 60_000 });
  const form = useForm<InstrumentForm>({
    initialValues: emptyInstrument(),
    validate: (values) => translated(validateInstrument(values), t),
  });
  const create = $api.useMutation('post', '/api/v1/market-data/instruments', {
    meta: { notify: false },
    onSuccess: (instrument) => {
      notifySuccess(t('marketData.instrumentCreated', { code: instrument.code }));
      void refresh();
      onClose();
    },
    onError: (error) => {
      const problem = asProblemError(error);
      if (problem.status === 422) applyProblemToForm(form, problem, INSTRUMENT_FIELDS);
    },
  });
  const fields = instrumentFields(form.values.kind);
  return (
    <Modal opened onClose={onClose} title={t('marketData.newInstrument')} size="lg">
      <form onSubmit={form.onSubmit((values) => create.mutate({ body: toCreateInstrument(values) }))}>
        <Stack>
          <ProblemAlert error={create.error} />
          <SimpleGrid cols={{ base: 1, sm: 2 }}>
            <TextInput
              label={t('marketData.code')}
              description={t('marketData.codeHint')}
              required
              data-testid="instrument-code"
              {...form.getInputProps('code')}
            />
            <Select
              label={t('marketData.kind')}
              data={INSTRUMENT_KINDS.map((value) => ({ value, label: t(`marketData.kinds.${value}`) }))}
              allowDeselect={false}
              data-testid="instrument-kind"
              {...form.getInputProps('kind')}
            />
          </SimpleGrid>
          <TextInput
            label={t('marketData.descriptionColumn')}
            required
            data-testid="instrument-description"
            {...form.getInputProps('description')}
          />
          <SimpleGrid cols={{ base: 1, sm: 2 }}>
            {fields.allowanceProduct && (
              <Select
                label={t('marketData.allowanceProduct')}
                data={allowances.map((product) => ({
                  value: product.code,
                  label: `${product.code} · ${product.name}`,
                }))}
                required
                data-testid="instrument-allowance"
                {...form.getInputProps('allowanceProductCode')}
              />
            )}
            {fields.futuresContract && (
              <Select
                label={t('marketData.futuresContract')}
                data={(futures.data ?? [])
                  .filter((contract) => contract.active)
                  .map((contract) => ({ value: contract.code, label: `${contract.code} · ${contract.deliveryMonth}` }))}
                required
                searchable
                data-testid="instrument-contract"
                {...form.getInputProps('futuresContractCode')}
              />
            )}
            {fields.bucket && (
              <Select
                label={t('marketData.bucket')}
                data={buckets}
                required
                searchable
                data-testid="instrument-bucket"
                {...form.getInputProps('bucketCode')}
              />
            )}
            {fields.productionYear && (
              <TextInput
                label={t('marketData.productionYear')}
                inputMode="numeric"
                maxLength={4}
                required
                data-testid="instrument-year"
                {...form.getInputProps('productionYear')}
              />
            )}
            {fields.unit && (
              <TextInput
                label={t('marketData.unit')}
                required
                data-testid="instrument-unit"
                {...form.getInputProps('unit')}
              />
            )}
            {fields.currency && (
              <TextInput
                label={t('marketData.currency')}
                maxLength={3}
                required
                data-testid="instrument-currency"
                {...form.getInputProps('currency')}
              />
            )}
          </SimpleGrid>
          <Group justify="flex-end">
            <Button variant="default" onClick={onClose}>
              {t('common.cancel')}
            </Button>
            <Button type="submit" loading={create.isPending} data-testid="instrument-submit">
              {t('marketData.newInstrument')}
            </Button>
          </Group>
        </Stack>
      </form>
    </Modal>
  );
}

/** Maps a source's code (a broker's product name, say) to an instrument, for file imports. */
export function AliasModal({
  instruments,
  initialInstrument,
  onClose,
}: {
  instruments: string[];
  initialInstrument?: string;
  onClose: () => void;
}) {
  const { t } = useTranslation();
  const [values, setValues] = useState({ source: '', externalCode: '', instrumentCode: initialInstrument ?? '' });
  const add = $api.useMutation('put', '/api/v1/market-data/aliases', {
    meta: { notify: false },
    onSuccess: (alias) => {
      notifySuccess(
        t('marketData.aliasAdded', {
          source: alias.source.toUpperCase(),
          external: alias.externalCode,
          instrument: alias.instrumentCode.toUpperCase(),
        }),
      );
      onClose();
    },
  });
  const ready = values.source.trim() && values.externalCode.trim() && values.instrumentCode;
  return (
    <Modal opened onClose={onClose} title={t('marketData.addAlias')}>
      <Stack>
        <Text size="sm" c="dimmed">
          {t('marketData.aliasHint')}
        </Text>
        <ProblemAlert error={add.error} />
        <Select
          label={t('marketData.instrument')}
          data={instruments}
          searchable
          required
          value={values.instrumentCode || null}
          onChange={(code) => setValues({ ...values, instrumentCode: code ?? '' })}
          data-testid="alias-instrument"
        />
        <Group grow>
          <TextInput
            label={t('marketData.aliasSource')}
            required
            value={values.source}
            onChange={(e) => setValues({ ...values, source: e.currentTarget.value.toUpperCase() })}
            data-testid="alias-source"
          />
          <TextInput
            label={t('marketData.externalCode')}
            required
            value={values.externalCode}
            onChange={(e) => setValues({ ...values, externalCode: e.currentTarget.value })}
            data-testid="alias-external"
          />
        </Group>
        <Group justify="flex-end">
          <Button variant="default" onClick={onClose}>
            {t('common.cancel')}
          </Button>
          <Button
            disabled={!ready}
            loading={add.isPending}
            onClick={() =>
              add.mutate({
                body: {
                  source: values.source.trim(),
                  externalCode: values.externalCode.trim(),
                  instrumentCode: values.instrumentCode,
                },
              })
            }
            data-testid="alias-submit"
          >
            {t('common.save')}
          </Button>
        </Group>
      </Stack>
    </Modal>
  );
}
