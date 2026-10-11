import {
  Alert,
  Autocomplete,
  Button,
  Checkbox,
  Group,
  Modal,
  NumberInput,
  Select,
  Stack,
  Text,
  TextInput,
} from '@mantine/core';
import { useForm } from '@mantine/form';
import { useMemo } from 'react';
import { useTranslation } from 'react-i18next';
import { useNavigate } from 'react-router';
import { $api } from '@/shared/api/client';
import { asProblemError } from '@/shared/api/problem';
import { notifySuccess } from '@/shared/components/notify';
import { ProblemAlert } from '@/shared/components/ProblemAlert';
import { formatDecimal } from '@/shared/format/format';
import { applyProblemToForm } from '@/shared/forms/applyProblemToForm';
import { useRegistries } from '@/features/registry/TransferModal';
import { useRefreshInventory } from './LotOperations';
import {
  ACQUISITION_SOURCES,
  RECEIVE_FIELDS,
  deviceFor,
  emptyReceive,
  gooAccounts,
  missingFields,
  rangeQuantity,
  toReceiveLots,
  type ReceiveValues,
} from './model/receive';

/**
 * Records certificates received into one of the entity's GoO accounts (an issuance, a transfer in, or an opening
 * balance) as a new lot. A device the device register lacks is described by its country and energy source.
 */
export function ReceiveLotsModal({ entityId, onClose }: { entityId: string; onClose: () => void }) {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const refresh = useRefreshInventory();
  const registries = useRegistries();
  const form = useForm<ReceiveValues>({ initialValues: emptyReceive() });
  const accounts = $api.useQuery(
    'get',
    '/api/v1/reference/registry-accounts',
    { params: { query: { holderType: 'LEGAL_ENTITY', holderId: entityId } } },
    { staleTime: 60_000 },
  );
  const devices = $api.useQuery('get', '/api/v1/reference/production-devices', {}, { staleTime: 5 * 60_000 });
  const sources = $api.useQuery(
    'get',
    '/api/v1/taxonomy/{scheme}',
    { params: { path: { scheme: 'ENERGY_SOURCE' } } },
    { staleTime: 5 * 60_000 },
  );
  const receive = $api.useMutation('post', '/api/v1/inventory/lots', {
    meta: { notify: false },
    onSuccess: (lot) => {
      notifySuccess(t('inventory.receive.done', { quantity: formatDecimal(lot.quantityMwh) }));
      void refresh();
      void navigate(`/inventory/lots/${lot.id}`);
    },
    onError: (error) => {
      const problem = asProblemError(error);
      if (problem.status === 422) applyProblemToForm(form, problem, RECEIVE_FIELDS);
    },
  });
  const values = form.values;
  const device = deviceFor(devices.data ?? [], values.deviceGsrn);
  const known = Boolean(device);
  const quantity = rangeQuantity(values.range);
  const gsrns = useMemo(() => (devices.data ?? []).map((candidate) => candidate.gsrn), [devices.data]);
  const missing = missingFields(values, known);
  const submit = form.onSubmit((submitted) => {
    receive.mutate({ body: toReceiveLots(submitted, entityId, known) });
  });
  return (
    <Modal opened onClose={onClose} title={t('inventory.receive.title')} size="xl">
      <form onSubmit={submit}>
        <Stack>
          <Text size="sm" c="dimmed">
            {t('inventory.receive.hint')}
          </Text>
          <ProblemAlert error={receive.error} />
          <Group grow align="flex-start">
            <Select
              label={t('inventory.receive.account')}
              data={gooAccounts(accounts.data ?? [], registries.data ?? []).map((account) => ({
                value: account.id,
                label: `${account.registryCode} · ${account.accountNumber}`,
              }))}
              searchable
              required
              {...form.getInputProps('registryAccountId')}
              data-testid="receive-account"
            />
            <Select
              label={t('reconciliation.acquisitionSource')}
              data={ACQUISITION_SOURCES.map((value) => ({ value, label: t(`reconciliation.sources.${value}`) }))}
              allowDeselect={false}
              {...form.getInputProps('acquisitionSource')}
              data-testid="receive-source"
            />
          </Group>
          <Group grow align="flex-start">
            <TextInput
              label={t('inventory.columns.issuance')}
              required
              {...form.getInputProps('issuanceRef')}
              data-testid="receive-issuance"
            />
            <NumberInput
              label={t('inventory.receive.rangeStart')}
              min={1}
              allowDecimal={false}
              required
              {...form.getInputProps('range.start')}
              data-testid="receive-range-start"
            />
            <NumberInput
              label={t('inventory.receive.rangeEnd')}
              description={quantity !== null ? t('inventory.receive.certificates', { count: quantity }) : undefined}
              min={1}
              allowDecimal={false}
              required
              {...form.getInputProps('range.end')}
              data-testid="receive-range-end"
            />
          </Group>
          <Autocomplete
            label={t('reconciliation.deviceGsrn')}
            description={device ? `${device.name} · ${device.country} · ${device.energySourceCode}` : undefined}
            data={gsrns}
            limit={20}
            required
            {...form.getInputProps('deviceGsrn')}
            data-testid="receive-device"
          />
          {values.deviceGsrn.trim() && !known && !devices.isPending && (
            <Stack gap="xs">
              <Alert color="gray">{t('inventory.receive.unknownDevice')}</Alert>
              <Group grow align="flex-start">
                <TextInput
                  label={t('inventory.receive.deviceName')}
                  {...form.getInputProps('device.name')}
                  data-testid="receive-device-name"
                />
                <TextInput
                  label={t('inventory.columns.country')}
                  maxLength={2}
                  required
                  {...form.getInputProps('device.country')}
                  data-testid="receive-device-country"
                />
                <Select
                  label={t('inventory.columns.source')}
                  data={(sources.data ?? []).map((node) => ({ value: node.code, label: node.name }))}
                  searchable
                  required
                  {...form.getInputProps('device.energySourceCode')}
                  data-testid="receive-device-source"
                />
              </Group>
            </Stack>
          )}
          <Group grow align="flex-start">
            <TextInput
              type="date"
              label={t('reconciliation.productionStart')}
              required
              {...form.getInputProps('productionPeriod.start')}
              data-testid="receive-production-start"
            />
            <TextInput
              type="date"
              label={t('reconciliation.productionEnd')}
              required
              {...form.getInputProps('productionPeriod.endExclusive')}
              data-testid="receive-production-end"
            />
            <TextInput
              type="date"
              label={t('reconciliation.issueDate')}
              required
              {...form.getInputProps('issueDate')}
              data-testid="receive-issue-date"
            />
          </Group>
          <Group grow align="flex-start">
            <TextInput
              label={t('reconciliation.issuingBody')}
              required
              {...form.getInputProps('issuingBody')}
              data-testid="receive-issuing-body"
            />
            <TextInput
              label={t('reconciliation.issuingCountry')}
              maxLength={2}
              required
              {...form.getInputProps('issuingCountry')}
              data-testid="receive-issuing-country"
            />
            <TextInput
              type="date"
              label={t('reconciliation.expiryDate')}
              description={t('inventory.receive.expiryHint')}
              {...form.getInputProps('expiryDate')}
              data-testid="receive-expiry"
            />
          </Group>
          <Group grow align="flex-start">
            <TextInput
              label={t('inventory.ops.costBasis')}
              inputMode="decimal"
              {...form.getInputProps('costBasisPerMwh')}
              data-testid="receive-cost"
            />
            <TextInput label={t('inventory.ops.currency')} maxLength={3} {...form.getInputProps('costCurrency')} />
            <TextInput label={t('inventory.receive.acquisitionRef')} {...form.getInputProps('acquisitionRef')} />
          </Group>
          <Group>
            <Checkbox
              label={t('reconciliation.supportProduction')}
              {...form.getInputProps('supportProduction', { type: 'checkbox' })}
            />
            <Checkbox
              label={t('reconciliation.supportInvestment')}
              {...form.getInputProps('supportInvestment', { type: 'checkbox' })}
            />
          </Group>
          <Group justify="flex-end">
            <Button variant="default" onClick={onClose}>
              {t('common.cancel')}
            </Button>
            <Button
              type="submit"
              disabled={missing.length > 0}
              loading={receive.isPending}
              data-testid="receive-submit"
            >
              {t('inventory.receive.submit')}
            </Button>
          </Group>
        </Stack>
      </form>
    </Modal>
  );
}
