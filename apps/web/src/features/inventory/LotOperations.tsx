import {
  Button,
  Checkbox,
  Group,
  Modal,
  MultiSelect,
  NumberInput,
  Paper,
  Select,
  Stack,
  Text,
  TextInput,
  Textarea,
  Title,
} from '@mantine/core';
import { useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useNavigate } from 'react-router';
import { $api } from '@/shared/api/client';
import type { Schema } from '@/shared/api/types';
import { can } from '@/shared/auth/permissions';
import { useSession } from '@/shared/auth/SessionContext';
import { notifySuccess } from '@/shared/components/notify';
import { ProblemAlert } from '@/shared/components/ProblemAlert';
import { CancellationModal } from '@/features/registry/CancellationModal';
import { TransferModal } from '@/features/registry/TransferModal';
import type { Lot } from './lotColumns';
import {
  LOT_ACTION_OPS,
  REGISTRY_ACTIONS,
  correctionBody,
  correctionValues,
  lotActions,
  newReservationRef,
  reservationBody,
  validReservation,
  type CorrectionValues,
  type LotAction,
} from './model/lotOps';

type Purpose = Schema<'CancellationDetails'>['purpose'];
const PURPOSES: Purpose[] = ['DISCLOSURE', 'OWN_CONSUMPTION', 'VOLUNTARY', 'COMPLIANCE', 'ON_BEHALF_OF_THIRD_PARTY'];

/** Test ids of the action buttons; `lot-transfer` and `withdraw` predate this list and stay as they are. */
const TEST_IDS: Record<LotAction, string> = {
  transfer: 'lot-transfer',
  registryCancel: 'lot-registry-cancel',
  reserve: 'lot-reserve',
  release: 'lot-release',
  split: 'lot-split',
  correct: 'lot-correct',
  cancel: 'lot-cancel',
  withdraw: 'withdraw',
};

/** Removing certificates from our books is the quiet, red end of the list; the transfer leads. */
const DESTRUCTIVE: ReadonlySet<LotAction> = new Set(['cancel', 'withdraw']);

export function useRefreshInventory() {
  const queryClient = useQueryClient();
  return () =>
    queryClient.invalidateQueries({
      predicate: (query) => typeof query.queryKey[1] === 'string' && query.queryKey[1].startsWith('/api/v1/inventory'),
    });
}

/** Reserves the lot, or its first certificates (split off first), under a new reservation reference. */
function ReserveModal({ lot, onClose }: { lot: Lot; onClose: () => void }) {
  const { t } = useTranslation();
  const refresh = useRefreshInventory();
  const navigate = useNavigate();
  const [quantity, setQuantity] = useState<number | ''>('');
  const [reservationRef] = useState(newReservationRef);
  const reserve = $api.useMutation('post', '/api/v1/inventory/lots/{id}/reserve', {
    meta: { notify: false },
    onSuccess: (reserved) => {
      notifySuccess(t('inventory.ops.reserved'));
      void refresh();
      onClose();
      if (reserved.id !== lot.id) void navigate(`/inventory/lots/${reserved.id}`);
    },
  });
  return (
    <Modal opened onClose={onClose} title={t('inventory.ops.reserve')}>
      <Stack>
        <Text size="sm" c="dimmed">
          {t('inventory.ops.reserveHint')}
        </Text>
        <ProblemAlert error={reserve.error} />
        <NumberInput
          label={t('inventory.ops.reserveQuantity')}
          description={t('inventory.ops.reserveQuantityHint', { quantity: lot.quantityMwh })}
          value={quantity}
          onChange={(value) => setQuantity(typeof value === 'number' ? value : '')}
          min={1}
          max={lot.quantityMwh}
          allowDecimal={false}
          data-testid="reserve-quantity"
        />
        <Text size="xs" c="dimmed">
          {t('inventory.ops.reservationRef', { ref: reservationRef })}
        </Text>
        <Button
          disabled={!validReservation(quantity, lot.quantityMwh)}
          loading={reserve.isPending}
          onClick={() =>
            reserve.mutate({ params: { path: { id: lot.id } }, body: reservationBody(quantity, reservationRef) })
          }
          data-testid="reserve-submit"
        >
          {t('inventory.ops.reserve')}
        </Button>
      </Stack>
    </Modal>
  );
}

/** Releases the lot's reservation; the plan or person that made it no longer holds the certificates. */
function ReleaseModal({ lot, onClose }: { lot: Lot; onClose: () => void }) {
  const { t } = useTranslation();
  const refresh = useRefreshInventory();
  const release = $api.useMutation('post', '/api/v1/inventory/lots/{id}/release', {
    meta: { notify: false },
    onSuccess: () => {
      notifySuccess(t('inventory.ops.released'));
      void refresh();
      onClose();
    },
  });
  return (
    <Modal opened onClose={onClose} title={t('inventory.ops.release')}>
      <Stack>
        <Text size="sm">{t('inventory.ops.releaseHint', { ref: lot.reservationRef ?? '' })}</Text>
        <ProblemAlert error={release.error} />
        <Group justify="flex-end">
          <Button variant="default" onClick={onClose}>
            {t('common.cancel')}
          </Button>
          <Button
            loading={release.isPending}
            onClick={() =>
              lot.reservationRef &&
              release.mutate({ params: { path: { id: lot.id } }, body: { reservationRef: lot.reservationRef } })
            }
            data-testid="release-submit"
          >
            {t('inventory.ops.release')}
          </Button>
        </Group>
      </Stack>
    </Modal>
  );
}

/** Corrects the fields a registry may correct in place (expiry, support, labels, cost), with the reason. */
function CorrectModal({ lot, onClose }: { lot: Lot; onClose: () => void }) {
  const { t } = useTranslation();
  const refresh = useRefreshInventory();
  const [values, setValues] = useState<CorrectionValues>(() => correctionValues(lot));
  const labels = $api.useQuery(
    'get',
    '/api/v1/taxonomy/{scheme}',
    { params: { path: { scheme: 'LABEL' }, query: { all: true } } },
    { staleTime: 5 * 60_000 },
  );
  const schemes = $api.useQuery(
    'get',
    '/api/v1/taxonomy/{scheme}',
    { params: { path: { scheme: 'SUPPORT_SCHEME' }, query: { all: true } } },
    { staleTime: 5 * 60_000 },
  );
  const correct = $api.useMutation('post', '/api/v1/inventory/lots/{id}/correct', {
    meta: { notify: false },
    onSuccess: () => {
      notifySuccess(t('inventory.ops.corrected'));
      void refresh();
      onClose();
    },
  });
  const set = (changes: Partial<CorrectionValues>) => setValues((current) => ({ ...current, ...changes }));
  const body = correctionBody(lot, values);
  return (
    <Modal opened onClose={onClose} title={t('inventory.ops.correct')} size="lg">
      <Stack>
        <Text size="sm" c="dimmed">
          {t('inventory.ops.correctHint')}
        </Text>
        <ProblemAlert error={correct.error} />
        <Group grow>
          <TextInput
            type="date"
            label={t('inventory.columns.expiry')}
            value={values.expiryDate}
            onChange={(e) => set({ expiryDate: e.currentTarget.value })}
            data-testid="correct-expiry"
          />
          <TextInput
            label={t('inventory.ops.costBasis')}
            inputMode="decimal"
            value={values.costBasis}
            onChange={(e) => set({ costBasis: e.currentTarget.value })}
            data-testid="correct-cost"
          />
          <TextInput
            label={t('inventory.ops.currency')}
            value={values.costCurrency}
            maxLength={3}
            onChange={(e) => set({ costCurrency: e.currentTarget.value.toUpperCase() })}
          />
        </Group>
        <Group>
          <Checkbox
            label={t('reconciliation.supportProduction')}
            checked={values.supportProduction}
            onChange={(e) => set({ supportProduction: e.currentTarget.checked })}
          />
          <Checkbox
            label={t('reconciliation.supportInvestment')}
            checked={values.supportInvestment}
            onChange={(e) => set({ supportInvestment: e.currentTarget.checked })}
          />
        </Group>
        <Group grow>
          <Select
            label={t('inventory.ops.supportScheme')}
            data={(schemes.data ?? []).map((node) => ({ value: node.code, label: node.name }))}
            value={values.supportSchemeCode}
            onChange={(value) => set({ supportSchemeCode: value })}
            clearable
            searchable
          />
          <MultiSelect
            label={t('inventory.labels')}
            data={(labels.data ?? []).map((node) => ({ value: node.code, label: node.name }))}
            value={values.labels}
            onChange={(value) => set({ labels: value })}
            clearable
            searchable
          />
        </Group>
        <Textarea
          label={t('common.reason')}
          value={values.reason}
          onChange={(e) => set({ reason: e.currentTarget.value })}
          required
          autosize
          minRows={2}
          data-testid="correct-reason"
        />
        {!body && (
          <Text size="xs" c="dimmed">
            {t('inventory.ops.nothingChanged')}
          </Text>
        )}
        <Button
          disabled={!body || !values.reason.trim()}
          loading={correct.isPending}
          onClick={() => body && correct.mutate({ params: { path: { id: lot.id } }, body })}
          data-testid="correct-submit"
        >
          {t('inventory.ops.correct')}
        </Button>
      </Stack>
    </Modal>
  );
}

function SplitModal({ lot, onClose }: { lot: Lot; onClose: () => void }) {
  const { t } = useTranslation();
  const refresh = useRefreshInventory();
  const [quantity, setQuantity] = useState<number | string>('');
  const split = $api.useMutation('post', '/api/v1/inventory/lots/{id}/split', {
    meta: { notify: false },
    onSuccess: () => {
      notifySuccess(t('inventory.splitDone'));
      void refresh();
      onClose();
    },
  });
  return (
    <Modal opened onClose={onClose} title={t('inventory.split')}>
      <Stack>
        <ProblemAlert error={split.error} />
        <NumberInput
          label={t('inventory.splitQuantity')}
          description={t('inventory.splitHint', { quantity: lot.quantityMwh })}
          min={1}
          max={lot.quantityMwh - 1}
          value={quantity}
          onChange={setQuantity}
          allowDecimal={false}
          data-testid="split-quantity"
        />
        <Button
          disabled={!quantity}
          loading={split.isPending}
          onClick={() => split.mutate({ params: { path: { id: lot.id } }, body: { quantity: Number(quantity) } })}
          data-testid="split-submit"
        >
          {t('inventory.split')}
        </Button>
      </Stack>
    </Modal>
  );
}

/** Cancels the certificates in our books only (the registry instruction is separate). */
function BookCancelModal({ lot, onClose }: { lot: Lot; onClose: () => void }) {
  const { t } = useTranslation();
  const refresh = useRefreshInventory();
  const [cancellation, setCancellation] = useState({
    purpose: 'DISCLOSURE' as Purpose,
    country: lot.deviceCountry,
    from: '',
    to: '',
    beneficiary: '',
  });
  const cancel = $api.useMutation('post', '/api/v1/inventory/lots/{id}/cancel', {
    meta: { notify: false },
    onSuccess: () => {
      notifySuccess(t('inventory.cancelled'));
      void refresh();
      onClose();
    },
  });
  return (
    <Modal opened onClose={onClose} title={t('inventory.cancel')} size="lg">
      <Stack>
        <Text size="sm">{t('inventory.cancelHint')}</Text>
        <ProblemAlert error={cancel.error} />
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
              params: { path: { id: lot.id } },
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
  );
}

function WithdrawModal({ lot, onClose }: { lot: Lot; onClose: () => void }) {
  const { t } = useTranslation();
  const refresh = useRefreshInventory();
  const [reason, setReason] = useState('');
  const withdraw = $api.useMutation('post', '/api/v1/inventory/lots/{id}/withdraw', {
    meta: { notify: false },
    onSuccess: () => {
      notifySuccess(t('inventory.withdrawn'));
      void refresh();
      onClose();
    },
  });
  return (
    <Modal opened onClose={onClose} title={t('inventory.withdraw')}>
      <Stack>
        <Text size="sm">{t('inventory.withdrawHint')}</Text>
        <ProblemAlert error={withdraw.error} />
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
          onClick={() => withdraw.mutate({ params: { path: { id: lot.id } }, body: { reason: reason.trim() } })}
        >
          {t('inventory.withdraw')}
        </Button>
      </Stack>
    </Modal>
  );
}

const LABELS: Record<LotAction, string> = {
  transfer: 'registry.newTransfer',
  registryCancel: 'inventory.ops.registryCancel',
  reserve: 'inventory.ops.reserve',
  release: 'inventory.ops.release',
  split: 'inventory.split',
  correct: 'inventory.ops.correct',
  cancel: 'inventory.cancel',
  withdraw: 'inventory.withdraw',
};

/**
 * The manual operations of one lot, by its status and the user's roles: registry instructions (transfer,
 * cancellation for a beneficiary) and changes to our books (reserve, release, split, correct, cancel, withdraw).
 */
export function LotActions({ lot }: { lot: Lot }) {
  const { t } = useTranslation();
  const roles = useSession().me.roles;
  const [open, setOpen] = useState<LotAction | null>(null);
  const actions = lotActions(lot).filter((action) => can(roles, LOT_ACTION_OPS[action]));
  if (actions.length === 0) return null;
  const close = () => setOpen(null);
  const button = (action: LotAction) => (
    <Button
      key={action}
      variant={action === 'transfer' ? 'filled' : DESTRUCTIVE.has(action) ? 'subtle' : 'default'}
      size="xs"
      color={DESTRUCTIVE.has(action) ? 'red' : undefined}
      onClick={() => setOpen(action)}
      data-testid={TEST_IDS[action]}
    >
      {t(LABELS[action])}
    </Button>
  );
  const registry = actions.filter((action) => REGISTRY_ACTIONS.has(action));
  const books = actions.filter((action) => !REGISTRY_ACTIONS.has(action));
  return (
    <Paper withBorder radius="lg" p="md" data-testid="lot-actions">
      <Title order={3} size="h6" mb="sm">
        {t('inventory.actions')}
      </Title>
      <Stack gap="sm">
        {registry.length > 0 && (
          <Stack gap={6}>
            <Text size="xs" c="dimmed" fw={500}>
              {t('inventory.ops.inRegistry')}
            </Text>
            <Group gap="xs">{registry.map(button)}</Group>
          </Stack>
        )}
        {books.length > 0 && (
          <Stack gap={6}>
            <Text size="xs" c="dimmed" fw={500}>
              {t('inventory.ops.inBooks')}
            </Text>
            <Group gap="xs">{books.map(button)}</Group>
          </Stack>
        )}
      </Stack>
      {open === 'transfer' && <TransferModal entityId={lot.holderEntityId} lot={lot} onClose={close} />}
      {open === 'registryCancel' && <CancellationModal lot={lot} onClose={close} />}
      {open === 'reserve' && <ReserveModal lot={lot} onClose={close} />}
      {open === 'release' && <ReleaseModal lot={lot} onClose={close} />}
      {open === 'split' && <SplitModal lot={lot} onClose={close} />}
      {open === 'correct' && <CorrectModal lot={lot} onClose={close} />}
      {open === 'cancel' && <BookCancelModal lot={lot} onClose={close} />}
      {open === 'withdraw' && <WithdrawModal lot={lot} onClose={close} />}
    </Paper>
  );
}
