import { Button, Group, Modal, NumberInput, Select, Stack, Text, TextInput } from '@mantine/core';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { $api } from '@/shared/api/client';
import { notifySuccess } from '@/shared/components/notify';
import { ProblemAlert } from '@/shared/components/ProblemAlert';
import { useRegistries } from '@/features/registry/TransferModal';
import { useRefreshInventory } from './LotOperations';
import {
  MOVEMENT_TYPES,
  allowanceAccounts,
  direction,
  emptyMovement,
  movementReady,
  toMovement,
  type MovementValues,
} from './model/movements';

/**
 * Books an allowance movement on one of the entity's allowance accounts: a purchase, a free allocation, an opening
 * balance or a correction (signed). Transfers that the registry executes go through registry instructions instead.
 */
export function RecordMovementModal({
  entityId,
  registryAccountId = null,
  productCode = null,
  onClose,
}: {
  entityId: string;
  registryAccountId?: string | null;
  productCode?: string | null;
  onClose: () => void;
}) {
  const { t } = useTranslation();
  const refresh = useRefreshInventory();
  const registries = useRegistries();
  const [values, setValues] = useState<MovementValues>(() => emptyMovement(registryAccountId, productCode));
  const set = (changes: Partial<MovementValues>) => setValues((current) => ({ ...current, ...changes }));
  const accounts = $api.useQuery(
    'get',
    '/api/v1/reference/registry-accounts',
    { params: { query: { holderType: 'LEGAL_ENTITY', holderId: entityId } } },
    { staleTime: 60_000 },
  );
  const products = $api.useQuery('get', '/api/v1/products/allowances', {}, { staleTime: 5 * 60_000 });
  const record = $api.useMutation('post', '/api/v1/inventory/holdings/movements', {
    meta: { notify: false },
    onSuccess: (holding) => {
      notifySuccess(t('inventory.movement.recorded', { product: holding.productCode, quantity: holding.quantity }));
      void refresh();
      onClose();
    },
  });
  const sign = direction(values.type);
  return (
    <Modal opened onClose={onClose} title={t('inventory.movement.title')} size="lg">
      <Stack>
        <Text size="sm" c="dimmed">
          {t('inventory.movement.hint')}
        </Text>
        <ProblemAlert error={record.error} />
        <Group grow align="flex-start">
          <Select
            label={t('inventory.columns.account')}
            data={allowanceAccounts(accounts.data ?? [], registries.data ?? []).map((account) => ({
              value: account.id,
              label: `${account.registryCode} · ${account.accountNumber} · ${account.name}`,
            }))}
            value={values.registryAccountId}
            onChange={(value) => set({ registryAccountId: value })}
            searchable
            required
            data-testid="movement-account"
          />
          <Select
            label={t('inventory.columns.product')}
            data={(products.data ?? [])
              .filter((product) => product.active)
              .map((product) => ({ value: product.code, label: `${product.code} · ${product.name}` }))}
            value={values.productCode}
            onChange={(value) => set({ productCode: value })}
            required
            data-testid="movement-product"
          />
        </Group>
        <Group grow align="flex-start">
          <Select
            label={t('inventory.columns.type')}
            data={MOVEMENT_TYPES.map((type) => ({ value: type, label: t(`inventory.movement.types.${type}`) }))}
            value={values.type}
            onChange={(value) => value && set({ type: value })}
            allowDeselect={false}
            data-testid="movement-type"
          />
          <NumberInput
            label={t('inventory.movement.quantity')}
            description={
              sign === 0
                ? t('inventory.movement.signedHint')
                : sign > 0
                  ? t('inventory.movement.addsHint')
                  : t('inventory.movement.takesHint')
            }
            value={values.quantity}
            onChange={(value) => set({ quantity: typeof value === 'number' ? value : '' })}
            allowDecimal={false}
            allowNegative={sign === 0}
            required
            data-testid="movement-quantity"
          />
          <TextInput
            type="date"
            label={t('inventory.movement.effectiveDate')}
            value={values.effectiveDate}
            onChange={(e) => set({ effectiveDate: e.currentTarget.value })}
            required
            data-testid="movement-date"
          />
        </Group>
        <Group grow align="flex-start">
          <TextInput
            label={t('inventory.columns.reference')}
            value={values.reference}
            onChange={(e) => set({ reference: e.currentTarget.value })}
            data-testid="movement-reference"
          />
          <NumberInput
            label={t('inventory.columns.complianceYear')}
            value={values.complianceYear}
            onChange={(value) => set({ complianceYear: typeof value === 'number' ? value : '' })}
            allowDecimal={false}
            min={2005}
            max={2100}
          />
        </Group>
        <Group grow align="flex-start">
          <TextInput
            label={t('inventory.movement.costPerUnit')}
            inputMode="decimal"
            value={values.costPerUnit}
            onChange={(e) => set({ costPerUnit: e.currentTarget.value })}
            data-testid="movement-cost"
          />
          <TextInput
            label={t('inventory.ops.currency')}
            value={values.costCurrency}
            maxLength={3}
            onChange={(e) => set({ costCurrency: e.currentTarget.value.toUpperCase() })}
          />
        </Group>
        <Button
          disabled={!movementReady(values)}
          loading={record.isPending}
          onClick={() => record.mutate({ body: toMovement(values, entityId) })}
          data-testid="movement-submit"
        >
          {t('inventory.movement.submit')}
        </Button>
      </Stack>
    </Modal>
  );
}
