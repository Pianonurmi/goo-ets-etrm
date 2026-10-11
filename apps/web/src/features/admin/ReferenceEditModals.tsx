import {
  Button,
  Group,
  Modal,
  MultiSelect,
  NumberInput,
  Select,
  Stack,
  Switch,
  Text,
  TextInput,
  Textarea,
} from '@mantine/core';
import dayjs from 'dayjs';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { $api } from '@/shared/api/client';
import { asProblemError } from '@/shared/api/problem';
import { notifySuccess } from '@/shared/components/notify';
import { ProblemAlert } from '@/shared/components/ProblemAlert';
import { applyProblemToForm } from '@/shared/forms/applyProblemToForm';
import { useInvalidateReference } from './PartyDetails';
import {
  EMIR_CLASSIFICATIONS,
  OWNER_TYPES,
  counterpartyDraft,
  counterpartyPatch,
  deviceDraft,
  deviceEditProblems,
  devicePatch,
  deviceProblems,
  emptyDevice,
  entityDraft,
  entityPatch,
  gsrnValid,
  hasChanges,
  newDeviceBody,
  parseStringMap,
  type Counterparty,
  type CounterpartyDraft,
  type Device,
  type DeviceDraft,
  type EntityDraft,
  type LegalEntity,
} from './model/referenceEdits';

type TaxonomyScheme = 'ENERGY_SOURCE' | 'TECHNOLOGY' | 'LABEL' | 'SUPPORT_SCHEME';

/** Field errors of a 422 answer, placed on the inputs (the alert above the form repeats them). */
function useFieldErrors() {
  const [errors, setErrors] = useState<Record<string, string>>({});
  const sink = {
    setFieldError: (path: string, message: string) => setErrors((current) => ({ ...current, [path]: message })),
  };
  return {
    errors,
    reset: () => setErrors({}),
    onError: (error: unknown) => {
      const problem = asProblemError(error);
      if (problem.status === 422) applyProblemToForm(sink, problem);
    },
  };
}

function useTaxonomyOptions(scheme: TaxonomyScheme) {
  const nodes = $api.useQuery(
    'get',
    '/api/v1/taxonomy/{scheme}',
    { params: { path: { scheme }, query: { all: true } } },
    { staleTime: 5 * 60_000 },
  );
  // Every level of the hierarchy (a device names its leaf, e.g. HYDRO_RESERVOIR), in tree order.
  return (nodes.data ?? [])
    .slice()
    .sort((a, b) => a.path.localeCompare(b.path))
    .map((node) => ({ value: node.code, label: `${node.code} · ${node.name}` }));
}

function EmirSelect({
  value,
  onChange,
}: {
  value: EntityDraft['emirClassification'];
  onChange: (value: EntityDraft['emirClassification']) => void;
}) {
  const { t } = useTranslation();
  return (
    <Select
      label={t('admin.emirClassification')}
      data={EMIR_CLASSIFICATIONS.map((value) => ({ value, label: t(`admin.emirClassifications.${value}`) }))}
      value={value}
      onChange={(next) => next && onChange(next)}
      allowDeselect={false}
      data-testid="edit-emir"
    />
  );
}

/** Middle office edits a legal entity; the accounting policy changes through month end with a second person. */
export function EditEntityModal({ entity, onClose }: { entity: LegalEntity; onClose: () => void }) {
  const { t } = useTranslation();
  const invalidate = useInvalidateReference();
  const fields = useFieldErrors();
  const [draft, setDraft] = useState<EntityDraft>(() => entityDraft(entity));
  const save = $api.useMutation('patch', '/api/v1/reference/legal-entities/{id}', {
    meta: { notify: false },
    onSuccess: () => {
      notifySuccess(t('admin.saved'));
      void invalidate();
      onClose();
    },
    onError: fields.onError,
  });
  const set = (patch: Partial<EntityDraft>) => setDraft({ ...draft, ...patch });
  const body = entityPatch(entity, draft);
  return (
    <Modal opened onClose={onClose} title={t('admin.editTitle', { name: entity.code })}>
      <Stack>
        <ProblemAlert error={save.error} />
        <Text size="sm" c="dimmed">
          {t('admin.accountingPolicyNote')} {t('admin.blankKeeps')}
        </Text>
        <TextInput
          label={t('ets.name')}
          value={draft.name}
          error={fields.errors.name}
          onChange={(e) => set({ name: e.currentTarget.value })}
          data-testid="edit-name"
        />
        <Group grow>
          <TextInput
            label="LEI"
            value={draft.lei}
            error={fields.errors.lei}
            onChange={(e) => set({ lei: e.currentTarget.value.toUpperCase() })}
            data-testid="edit-lei"
          />
          <TextInput
            label={t('admin.vatNumber')}
            value={draft.vatNumber}
            error={fields.errors.vatNumber}
            onChange={(e) => set({ vatNumber: e.currentTarget.value })}
            data-testid="edit-vat"
          />
        </Group>
        <Group grow>
          <EmirSelect value={draft.emirClassification} onChange={(value) => set({ emirClassification: value })} />
          <Select
            label={t('admin.obligationsOpenAt')}
            data={(['APPROVED', 'CONFIRMED'] as const).map((value) => ({
              value,
              label: t(`admin.obligationsOpenAtOptions.${value}`),
            }))}
            value={draft.obligationsOpenAt}
            allowDeselect={false}
            onChange={(value) => value && set({ obligationsOpenAt: value })}
            data-testid="edit-obligations"
          />
        </Group>
        <Group grow align="flex-end">
          <TextInput
            type="date"
            label={t('admin.validTo')}
            value={draft.validTo}
            min={entity.validPeriod.validFrom}
            error={fields.errors.validTo}
            onChange={(e) => set({ validTo: e.currentTarget.value })}
            data-testid="edit-valid-to"
          />
          <Switch
            label={t('marketData.active')}
            checked={draft.active}
            onChange={(e) => set({ active: e.currentTarget.checked })}
            data-testid="edit-active"
            mb={8}
          />
        </Group>
        <Button
          disabled={!hasChanges(body) || !draft.name.trim()}
          loading={save.isPending}
          onClick={() => {
            fields.reset();
            save.mutate({ params: { path: { id: entity.id } }, body });
          }}
          data-testid="edit-save"
        >
          {t('common.save')}
        </Button>
      </Stack>
    </Modal>
  );
}

/**
 * Middle office edits a counterparty's terms. Its registry and bank accounts change through requests that a second
 * person approves, and its credit limit is a risk limit, so neither is offered here.
 */
export function EditCounterpartyModal({ counterparty, onClose }: { counterparty: Counterparty; onClose: () => void }) {
  const { t } = useTranslation();
  const invalidate = useInvalidateReference();
  const fields = useFieldErrors();
  const counterparties = $api.useQuery('get', '/api/v1/reference/counterparties');
  const [draft, setDraft] = useState<CounterpartyDraft>(() => counterpartyDraft(counterparty));
  const save = $api.useMutation('patch', '/api/v1/reference/counterparties/{id}', {
    meta: { notify: false },
    onSuccess: () => {
      notifySuccess(t('admin.saved'));
      void invalidate();
      onClose();
    },
    onError: fields.onError,
  });
  const set = (patch: Partial<CounterpartyDraft>) => setDraft({ ...draft, ...patch });
  const body = counterpartyPatch(counterparty, draft);
  const parents = (counterparties.data ?? [])
    .filter((candidate) => candidate.id !== counterparty.id)
    .map((candidate) => ({ value: candidate.id, label: `${candidate.code} · ${candidate.name}` }));
  return (
    <Modal opened onClose={onClose} title={t('admin.editTitle', { name: counterparty.code })} size="lg">
      <Stack>
        <ProblemAlert error={save.error} />
        <Text size="sm" c="dimmed">
          {t('admin.counterpartySensitiveNote')} {t('admin.blankKeeps')}
        </Text>
        <TextInput
          label={t('ets.name')}
          value={draft.name}
          error={fields.errors.name}
          onChange={(e) => set({ name: e.currentTarget.value })}
          data-testid="edit-name"
        />
        <Group grow>
          <TextInput
            label="LEI"
            value={draft.lei}
            error={fields.errors.lei}
            onChange={(e) => set({ lei: e.currentTarget.value.toUpperCase() })}
            data-testid="edit-lei"
          />
          <TextInput
            label={t('admin.vatNumber')}
            value={draft.vatNumber}
            error={fields.errors.vatNumber}
            onChange={(e) => set({ vatNumber: e.currentTarget.value })}
            data-testid="edit-vat"
          />
        </Group>
        <Group grow>
          <NumberInput
            label={t('admin.paymentTerms')}
            value={draft.paymentTermsDays}
            min={0}
            allowDecimal={false}
            error={fields.errors.paymentTermsDays}
            onChange={(value) => set({ paymentTermsDays: Number(value) || 0 })}
            data-testid="edit-payment-terms"
          />
          <TextInput
            type="email"
            label={t('admin.invoiceEmail')}
            value={draft.invoiceEmail}
            error={fields.errors.invoiceEmail}
            onChange={(e) => set({ invoiceEmail: e.currentTarget.value })}
            data-testid="edit-invoice-email"
          />
        </Group>
        <Group grow>
          <EmirSelect value={draft.emirClassification} onChange={(value) => set({ emirClassification: value })} />
          <Select
            label={t('admin.parentCounterparty')}
            data={parents}
            value={draft.parentCounterpartyId}
            searchable
            allowDeselect={false}
            error={fields.errors.parentCounterpartyId}
            onChange={(value) => value && set({ parentCounterpartyId: value })}
            data-testid="edit-parent"
          />
        </Group>
        <Group>
          <Switch
            label={t('admin.nettingAgreed')}
            checked={draft.nettingAgreed}
            onChange={(e) => set({ nettingAgreed: e.currentTarget.checked })}
            data-testid="edit-netting"
          />
          <Switch
            label={t('admin.selfBilling')}
            checked={draft.selfBilling}
            onChange={(e) => set({ selfBilling: e.currentTarget.checked })}
            data-testid="edit-self-billing"
          />
          <Switch
            label={t('marketData.active')}
            checked={draft.active}
            onChange={(e) => set({ active: e.currentTarget.checked })}
            data-testid="edit-active"
          />
        </Group>
        <Button
          disabled={!hasChanges(body) || !draft.name.trim()}
          loading={save.isPending}
          onClick={() => {
            fields.reset();
            save.mutate({ params: { path: { id: counterparty.id } }, body });
          }}
          data-testid="edit-save"
        >
          {t('common.save')}
        </Button>
      </Stack>
    </Modal>
  );
}

function CodeSelects({ draft, set }: { draft: DeviceDraft; set: (patch: Partial<DeviceDraft>) => void }) {
  const { t } = useTranslation();
  const schemes = useTaxonomyOptions('SUPPORT_SCHEME');
  const labels = useTaxonomyOptions('LABEL');
  return (
    <Group grow align="flex-start">
      <MultiSelect
        label={t('admin.supportSchemes')}
        data={schemes}
        value={draft.supportSchemeCodes}
        searchable
        onChange={(value) => set({ supportSchemeCodes: value })}
        data-testid="device-support-schemes"
      />
      <MultiSelect
        label={t('admin.labels')}
        data={labels}
        value={draft.labelCodes}
        searchable
        onChange={(value) => set({ labelCodes: value })}
        data-testid="device-labels"
      />
    </Group>
  );
}

function ExternalCodesInput({
  value,
  error,
  onChange,
}: {
  value: string;
  error?: string;
  onChange: (value: string) => void;
}) {
  const { t } = useTranslation();
  return (
    <Textarea
      label={t('admin.externalCodes')}
      description={t('admin.externalCodesHint')}
      value={value}
      autosize
      minRows={2}
      ff="monospace"
      error={parseStringMap(value) === null ? t('common.invalidJson') : error}
      onChange={(e) => onChange(e.currentTarget.value)}
      data-testid="device-external-codes"
    />
  );
}

/** Registers a production device (back office, registry operators and middle office). */
export function NewDeviceModal({ onClose, onCreated }: { onClose: () => void; onCreated: (id: string) => void }) {
  const { t } = useTranslation();
  const invalidate = useInvalidateReference();
  const fields = useFieldErrors();
  const [draft, setDraft] = useState<DeviceDraft>(() => emptyDevice(dayjs().format('YYYY-MM-DD')));
  const energySources = useTaxonomyOptions('ENERGY_SOURCE');
  const technologies = useTaxonomyOptions('TECHNOLOGY');
  const registries = $api.useQuery('get', '/api/v1/reference/registries', {}, { staleTime: 5 * 60_000 });
  const entities = $api.useQuery('get', '/api/v1/reference/legal-entities');
  const counterparties = $api.useQuery('get', '/api/v1/reference/counterparties');
  const create = $api.useMutation('post', '/api/v1/reference/production-devices', {
    meta: { notify: false },
    onSuccess: (device) => {
      notifySuccess(t('admin.deviceCreated', { gsrn: device.gsrn }));
      void invalidate();
      onCreated(device.id);
    },
    onError: fields.onError,
  });
  const set = (patch: Partial<DeviceDraft>) => setDraft({ ...draft, ...patch });
  const owners =
    draft.ownerType === 'LEGAL_ENTITY'
      ? (entities.data ?? []).map((e) => ({ value: e.id, label: `${e.code} · ${e.name}` }))
      : (counterparties.data ?? []).map((c) => ({ value: c.id, label: `${c.code} · ${c.name}` }));
  const problems = deviceProblems(draft);
  return (
    <Modal opened onClose={onClose} title={t('admin.newDevice')} size="lg">
      <Stack>
        <ProblemAlert error={create.error} />
        <Group grow align="flex-start">
          <TextInput
            label={t('admin.gsrn')}
            description={t('admin.gsrnHint')}
            value={draft.gsrn}
            inputMode="numeric"
            maxLength={18}
            error={
              fields.errors.gsrn ??
              (draft.gsrn.length === 18 && !gsrnValid(draft.gsrn) ? t('admin.gsrnInvalid') : undefined)
            }
            onChange={(e) => set({ gsrn: e.currentTarget.value.replace(/\D/g, '') })}
            data-testid="device-gsrn"
          />
          <TextInput
            label={t('ets.name')}
            value={draft.name}
            error={fields.errors.name}
            onChange={(e) => set({ name: e.currentTarget.value })}
            data-testid="device-name"
          />
        </Group>
        <Group grow align="flex-start">
          <TextInput
            label={t('ets.country')}
            value={draft.country}
            maxLength={2}
            error={fields.errors.country}
            onChange={(e) => set({ country: e.currentTarget.value.toUpperCase() })}
            data-testid="device-country"
          />
          <TextInput
            label={t('admin.capacityKw')}
            value={draft.capacityKw}
            inputMode="decimal"
            error={fields.errors.capacityKw}
            onChange={(e) => set({ capacityKw: e.currentTarget.value })}
            data-testid="device-capacity"
          />
        </Group>
        <Group grow align="flex-start">
          <TextInput
            type="date"
            label={t('admin.commissioned')}
            value={draft.commissioningDate}
            error={fields.errors.commissioningDate}
            onChange={(e) => set({ commissioningDate: e.currentTarget.value })}
            data-testid="device-commissioned"
          />
          <TextInput
            type="date"
            label={t('admin.decommissioned')}
            value={draft.decommissioningDate}
            error={fields.errors.decommissioningDate}
            onChange={(e) => set({ decommissioningDate: e.currentTarget.value })}
            data-testid="device-decommissioned"
          />
        </Group>
        <Group grow align="flex-start">
          <Select
            label={t('admin.energySource')}
            data={energySources}
            value={draft.energySourceCode}
            searchable
            error={fields.errors.energySourceCode}
            onChange={(value) => set({ energySourceCode: value })}
            data-testid="device-energy-source"
          />
          <Select
            label={t('admin.technology')}
            data={technologies}
            value={draft.technologyCode}
            searchable
            error={fields.errors.technologyCode}
            onChange={(value) => set({ technologyCode: value })}
            data-testid="device-technology"
          />
        </Group>
        <CodeSelects draft={draft} set={set} />
        <Group grow align="flex-start">
          <Select
            label={t('admin.ownerType')}
            data={OWNER_TYPES.map((value) => ({ value, label: t(`admin.ownerTypes.${value}`) }))}
            value={draft.ownerType}
            allowDeselect={false}
            onChange={(value) => value && set({ ownerType: value, ownerId: null, ownDevice: false })}
            data-testid="device-owner-type"
          />
          <Select
            label={t('admin.owner')}
            data={owners}
            value={draft.ownerId}
            searchable
            disabled={draft.ownerType === 'THIRD_PARTY'}
            error={fields.errors.ownerId}
            onChange={(value) => set({ ownerId: value })}
            data-testid="device-owner"
          />
        </Group>
        <Group grow align="flex-end">
          <Select
            label={t('admin.registry')}
            data={(registries.data ?? [])
              .filter((registry) => registry.registryType === 'GOO')
              .map((registry) => ({ value: registry.code, label: `${registry.code} · ${registry.name}` }))}
            value={draft.registryCode}
            clearable
            onChange={(value) => set({ registryCode: value })}
            data-testid="device-registry"
          />
          <Switch
            label={t('admin.ownDevice')}
            checked={draft.ownDevice}
            onChange={(e) => set({ ownDevice: e.currentTarget.checked })}
            data-testid="device-own"
            mb={8}
          />
        </Group>
        <ExternalCodesInput
          value={draft.externalCodes}
          error={fields.errors.externalCodes}
          onChange={(value) => set({ externalCodes: value })}
        />
        <Button
          disabled={problems.length > 0}
          loading={create.isPending}
          onClick={() => {
            fields.reset();
            create.mutate({ body: newDeviceBody(draft) });
          }}
          data-testid="device-save"
        >
          {t('common.save')}
        </Button>
      </Stack>
    </Modal>
  );
}

/** Edits what may change on a device: name, capacity, decommissioning, schemes, labels, codes and activity. */
export function EditDeviceModal({ device, onClose }: { device: Device; onClose: () => void }) {
  const { t } = useTranslation();
  const invalidate = useInvalidateReference();
  const fields = useFieldErrors();
  const [draft, setDraft] = useState<DeviceDraft>(() => deviceDraft(device));
  const save = $api.useMutation('patch', '/api/v1/reference/production-devices/{id}', {
    meta: { notify: false },
    onSuccess: () => {
      notifySuccess(t('admin.saved'));
      void invalidate();
      onClose();
    },
    onError: fields.onError,
  });
  const set = (patch: Partial<DeviceDraft>) => setDraft({ ...draft, ...patch });
  const problems = deviceEditProblems(draft);
  const body = devicePatch(device, draft);
  return (
    <Modal opened onClose={onClose} title={t('admin.editTitle', { name: device.name })} size="lg">
      <Stack>
        <ProblemAlert error={save.error} />
        <Text size="sm" c="dimmed">
          {t('admin.deviceIdentityFixed')} {t('admin.blankKeeps')}
        </Text>
        <Group grow align="flex-start">
          <TextInput
            label={t('ets.name')}
            value={draft.name}
            error={fields.errors.name}
            onChange={(e) => set({ name: e.currentTarget.value })}
            data-testid="device-name"
          />
          <TextInput
            label={t('admin.capacityKw')}
            value={draft.capacityKw}
            inputMode="decimal"
            error={fields.errors.capacityKw}
            onChange={(e) => set({ capacityKw: e.currentTarget.value })}
            data-testid="device-capacity"
          />
        </Group>
        <Group grow align="flex-end">
          <TextInput
            type="date"
            label={t('admin.decommissioned')}
            value={draft.decommissioningDate}
            min={device.commissioningDate}
            error={fields.errors.decommissioningDate}
            onChange={(e) => set({ decommissioningDate: e.currentTarget.value })}
            data-testid="device-decommissioned"
          />
          <Switch
            label={t('marketData.active')}
            checked={draft.active}
            onChange={(e) => set({ active: e.currentTarget.checked })}
            data-testid="device-active"
            mb={8}
          />
        </Group>
        <CodeSelects draft={draft} set={set} />
        <ExternalCodesInput
          value={draft.externalCodes}
          error={fields.errors.externalCodes}
          onChange={(value) => set({ externalCodes: value })}
        />
        <Button
          disabled={problems.length > 0 || !hasChanges(body)}
          loading={save.isPending}
          onClick={() => {
            fields.reset();
            save.mutate({ params: { path: { id: device.id } }, body });
          }}
          data-testid="device-save"
        >
          {t('common.save')}
        </Button>
      </Stack>
    </Modal>
  );
}
