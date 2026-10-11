import {
  Alert,
  Button,
  Group,
  Modal,
  NumberInput,
  SegmentedControl,
  Select,
  Stack,
  Text,
  TextInput,
} from '@mantine/core';
import { useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useNavigate } from 'react-router';
import { $api } from '@/shared/api/client';
import { notifySuccess } from '@/shared/components/notify';
import { ProblemAlert } from '@/shared/components/ProblemAlert';
import { formatDecimal } from '@/shared/format/format';
import { newKey } from '@/shared/ids';
import {
  allowanceTransferReady,
  destinationAccounts,
  freeQuantity,
  sourceAccounts,
  toAllowanceTransfer,
  transferableHoldings,
  type AllowanceTransferValues,
} from './model/allowances';
import { useInvalidateRegistry, useRegistries } from './TransferModal';

type Receiver = 'own' | 'counterparty';

/**
 * Allowances from one of our emission allowance accounts to another account in the same registry (Union Registry):
 * one of ours, or a counterparty's registered account. The allowances are reserved at once; the transfer waits for
 * a second person's approval and is then executed in the registry by hand.
 */
export function AllowanceTransferModal({
  entityId,
  fromAccountId = null,
  productCode = null,
  onClose,
}: {
  entityId: string;
  fromAccountId?: string | null;
  productCode?: string | null;
  onClose: () => void;
}) {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const invalidate = useInvalidateRegistry();
  const registries = useRegistries();
  const [key] = useState(() => newKey('ui-allowance-transfer'));
  const [receiver, setReceiver] = useState<Receiver>('own');
  const [counterpartyId, setCounterpartyId] = useState<string | null>(null);
  const [values, setValues] = useState<AllowanceTransferValues>({
    fromAccountId,
    productCode,
    quantity: '',
    toAccountId: null,
    movementType: 'TRANSFER_OUT',
    unitPrice: '',
    priceCurrency: 'EUR',
  });
  const set = (changes: Partial<AllowanceTransferValues>) => setValues((current) => ({ ...current, ...changes }));
  const ownAccounts = $api.useQuery(
    'get',
    '/api/v1/reference/registry-accounts',
    { params: { query: { holderType: 'LEGAL_ENTITY', holderId: entityId } } },
    { staleTime: 60_000 },
  );
  const holdings = $api.useQuery('get', '/api/v1/inventory/holdings', {
    params: { query: { holderEntityId: entityId } },
  });
  const counterparties = $api.useQuery('get', '/api/v1/reference/counterparties', {}, { staleTime: 5 * 60_000 });
  const counterpartyAccounts = $api.useQuery(
    'get',
    '/api/v1/reference/registry-accounts',
    { params: { query: { holderType: 'COUNTERPARTY', holderId: counterpartyId ?? '' } } },
    { enabled: Boolean(counterpartyId) },
  );
  const etsRegistries = useMemo(
    () =>
      new Set(
        (registries.data ?? []).filter((r) => r.registryType === 'EMISSION_ALLOWANCE').map((registry) => registry.code),
      ),
    [registries.data],
  );
  const sources = sourceAccounts(ownAccounts.data ?? []).filter((account) => etsRegistries.has(account.registryCode));
  const from = sources.find((account) => account.id === values.fromAccountId);
  const products = transferableHoldings(holdings.data ?? [], values.fromAccountId);
  const holding = products.find((candidate) => candidate.productCode === values.productCode);
  const free = holding ? freeQuantity(holding) : null;
  const destinations = destinationAccounts(
    receiver === 'own' ? (ownAccounts.data ?? []) : (counterpartyAccounts.data ?? []),
    from,
  );
  const submit = $api.useMutation('post', '/api/v1/registry/instructions/allowance-transfers', {
    meta: { notify: false },
    onSuccess: (instruction) => {
      notifySuccess(t('registry.allowanceTransferSubmitted'));
      void invalidate();
      void navigate(`/registry/instructions/${instruction.id}`);
    },
  });
  const accountLabel = (account: { registryCode: string; accountNumber: string; name: string }) =>
    `${account.registryCode} · ${account.accountNumber} · ${account.name}`;
  return (
    <Modal opened onClose={onClose} title={t('registry.allowanceTransfer')} size="lg">
      <Stack>
        <Text size="sm" c="dimmed">
          {t('registry.allowanceTransferHint')}
        </Text>
        <ProblemAlert error={submit.error} />
        <Group grow align="flex-start">
          <Select
            label={t('registry.fromAccount')}
            data={sources.map((account) => ({ value: account.id, label: accountLabel(account) }))}
            value={values.fromAccountId}
            onChange={(value) => set({ fromAccountId: value, productCode: null, toAccountId: null })}
            allowDeselect={false}
            searchable
            required
            data-testid="allowance-from"
          />
          <Select
            label={t('registry.product')}
            data={products.map((candidate) => ({
              value: candidate.productCode,
              label: t('registry.productFree', {
                product: candidate.productCode,
                free: formatDecimal(freeQuantity(candidate)),
              }),
            }))}
            value={values.productCode}
            onChange={(value) => set({ productCode: value })}
            allowDeselect={false}
            disabled={!values.fromAccountId}
            required
            data-testid="allowance-product"
          />
        </Group>
        {values.fromAccountId && products.length === 0 && !holdings.isPending && (
          <Alert color="gray" data-testid="allowance-no-holdings">
            {t('registry.noFreeAllowances')}
          </Alert>
        )}
        <SegmentedControl
          value={receiver}
          onChange={(value) => {
            setReceiver(value);
            set({ toAccountId: null });
          }}
          data={[
            { value: 'own', label: t('registry.ownAccount') },
            { value: 'counterparty', label: t('registry.counterpartyAccount') },
          ]}
          data-testid="allowance-receiver"
        />
        <Group grow align="flex-start">
          {receiver === 'counterparty' && (
            <Select
              label={t('registry.counterparty')}
              data={(counterparties.data ?? [])
                .filter((counterparty) => counterparty.active)
                .map((counterparty) => ({ value: counterparty.id, label: counterparty.name }))}
              value={counterpartyId}
              onChange={(value) => {
                setCounterpartyId(value);
                set({ toAccountId: null });
              }}
              searchable
              data-testid="allowance-counterparty"
            />
          )}
          <Select
            label={t('registry.toAccount')}
            description={from ? t('registry.sameRegistry', { registry: from.registryCode }) : undefined}
            data={destinations.map((account) => ({ value: account.id, label: accountLabel(account) }))}
            value={values.toAccountId}
            onChange={(value) => set({ toAccountId: value })}
            disabled={!from}
            searchable
            required
            nothingFoundMessage={t('registry.noAccounts')}
            data-testid="allowance-to"
          />
        </Group>
        <Group grow align="flex-start">
          <NumberInput
            label={t('registry.allowanceQuantity')}
            description={free !== null ? t('registry.freeHint', { free: formatDecimal(free) }) : undefined}
            value={values.quantity}
            onChange={(value) => set({ quantity: typeof value === 'number' ? value : '' })}
            min={1}
            max={free ?? undefined}
            allowDecimal={false}
            required
            data-testid="allowance-quantity"
          />
          <Select
            label={t('registry.movementType')}
            data={(['TRANSFER_OUT', 'SALE'] as const).map((value) => ({
              value,
              label: t(`registry.movements.${value}`),
            }))}
            value={values.movementType}
            onChange={(value) => value && set({ movementType: value })}
            allowDeselect={false}
            data-testid="allowance-movement"
          />
        </Group>
        {values.movementType === 'SALE' && (
          <Group grow>
            <TextInput
              label={t('registry.unitPrice')}
              inputMode="decimal"
              value={values.unitPrice}
              onChange={(e) => set({ unitPrice: e.currentTarget.value })}
              required
              data-testid="allowance-price"
            />
            <TextInput
              label={t('inventory.ops.currency')}
              value={values.priceCurrency}
              maxLength={3}
              onChange={(e) => set({ priceCurrency: e.currentTarget.value.toUpperCase() })}
              required
            />
          </Group>
        )}
        <Button
          disabled={!allowanceTransferReady(values, free)}
          loading={submit.isPending}
          onClick={() => submit.mutate({ body: toAllowanceTransfer(values, key) })}
          data-testid="allowance-submit"
        >
          {t('risk.sendForApproval')}
        </Button>
      </Stack>
    </Modal>
  );
}
