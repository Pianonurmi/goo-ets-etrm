import { Alert, Button, Group, Modal, NumberInput, Select, Stack, Text, TextInput } from '@mantine/core';
import { useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useNavigate } from 'react-router';
import { $api } from '@/shared/api/client';
import type { Schema } from '@/shared/api/types';
import { notifySuccess } from '@/shared/components/notify';
import { ProblemAlert } from '@/shared/components/ProblemAlert';
import { formatDecimal } from '@/shared/format/format';
import { newKey } from '@/shared/ids';
import { routeSummary } from './model/instructions';

type Lot = Schema<'LotView'>;

export function useInvalidateRegistry() {
  const queryClient = useQueryClient();
  return () =>
    queryClient.invalidateQueries({
      predicate: (q) =>
        typeof q.queryKey[1] === 'string' &&
        ['/api/v1/registry', '/api/v1/workflow', '/api/v1/inventory'].some((prefix) =>
          (q.queryKey[1] as string).startsWith(prefix),
        ),
    });
}

export function useRegistries() {
  return $api.useQuery('get', '/api/v1/reference/registries', {}, { staleTime: 5 * 60_000 });
}

/**
 * An unplanned transfer of a lot (or part of it) to another account. It waits for a second person's approval in
 * the inbox; the route between the registries is shown before submitting.
 */
export function TransferModal({ entityId, lot, onClose }: { entityId: string; lot?: Lot; onClose: () => void }) {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const invalidate = useInvalidateRegistry();
  const registries = useRegistries();
  const [lotId, setLotId] = useState<string | null>(lot?.id ?? null);
  const [quantity, setQuantity] = useState<number | ''>('');
  const [toRegistry, setToRegistry] = useState<string | null>(lot?.registryCode ?? null);
  const [toAccount, setToAccount] = useState('');
  const [key] = useState(() => newKey('manual'));
  const lots = $api.useQuery(
    'get',
    '/api/v1/inventory/lots',
    { params: { query: { holderEntityId: entityId, status: ['HELD'] } } },
    { enabled: !lot },
  );
  const chosen = lot ?? (lots.data ?? []).find((candidate) => candidate.id === lotId);
  const routes = $api.useQuery(
    'get',
    '/api/v1/registry/routes',
    { params: { query: { from: chosen?.registryCode ?? '' } } },
    { enabled: Boolean(chosen) },
  );
  const route = toRegistry ? routes.data?.[toRegistry] : undefined;
  const summary = routeSummary(route);
  const submit = $api.useMutation('post', '/api/v1/registry/instructions/transfers', {
    meta: { notify: false },
    onSuccess: (instruction) => {
      notifySuccess(t('registry.transferSubmitted'));
      void invalidate();
      void navigate(`/registry/instructions/${instruction.id}`);
    },
  });
  const ready = chosen && toRegistry && toAccount.trim() && summary?.feasible !== false;
  return (
    <Modal opened onClose={onClose} title={t('registry.newTransfer')} size="lg">
      <Stack>
        <ProblemAlert error={submit.error} />
        <Text size="sm" c="dimmed">
          {t('registry.transferHint')}
        </Text>
        {lot ? (
          <Text size="sm">
            {t('registry.transferOf', { issuance: lot.issuanceRef, start: lot.range.start, end: lot.range.end })}
          </Text>
        ) : (
          <Select
            label={t('registry.lot')}
            data={(lots.data ?? []).map((candidate) => ({
              value: candidate.id,
              label: `${candidate.issuanceRef} · ${candidate.range.start}–${candidate.range.end} · ${candidate.quantityMwh} MWh`,
            }))}
            value={lotId}
            onChange={setLotId}
            searchable
            required
            data-testid="transfer-lot"
          />
        )}
        <NumberInput
          label={t('registry.quantityMwh')}
          description={t('registry.quantityHint', { max: chosen?.quantityMwh ?? '' })}
          value={quantity}
          onChange={(value) => setQuantity(typeof value === 'number' ? value : '')}
          min={1}
          max={chosen?.quantityMwh}
          allowDecimal={false}
          data-testid="transfer-quantity"
        />
        <Group grow>
          <Select
            label={t('registry.toRegistry')}
            data={(registries.data ?? []).map((registry) => ({
              value: registry.code,
              label: `${registry.code} · ${registry.name}`,
            }))}
            value={toRegistry}
            onChange={setToRegistry}
            allowDeselect={false}
            searchable
            required
            data-testid="transfer-registry"
          />
          <TextInput
            label={t('registry.toAccount')}
            value={toAccount}
            onChange={(e) => setToAccount(e.currentTarget.value)}
            required
            data-testid="transfer-account"
          />
        </Group>
        {chosen && toRegistry && (
          <Alert color={summary === null ? 'gray' : summary.feasible ? 'teal' : 'red'} data-testid="transfer-route">
            {summary === null
              ? t('registry.noRoute')
              : summary.feasible && route
                ? t('registry.routeFeasible', {
                    fee: formatDecimal(route.feePerMwh, 2, 4),
                    days: route.expectedDays,
                  })
                : t('registry.routeInfeasible')}
          </Alert>
        )}
        <Button
          disabled={!ready}
          loading={submit.isPending}
          data-testid="transfer-submit"
          onClick={() =>
            chosen &&
            toRegistry &&
            submit.mutate({
              body: {
                lotId: chosen.id,
                quantityMwh: quantity === '' ? undefined : quantity,
                toRegistryCode: toRegistry,
                toAccountRef: toAccount.trim(),
                idempotencyKey: key,
              },
            })
          }
        >
          {t('risk.sendForApproval')}
        </Button>
      </Stack>
    </Modal>
  );
}
