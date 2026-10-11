import {
  ActionIcon,
  Alert,
  Button,
  Divider,
  Drawer,
  Group,
  Modal,
  Paper,
  Select,
  SimpleGrid,
  Stack,
  Text,
  TextInput,
  Title,
} from '@mantine/core';
import { useForm } from '@mantine/form';
import { IconAdjustments, IconLock, IconPlus, IconTrash } from '@tabler/icons-react';
import { useQueryClient } from '@tanstack/react-query';
import dayjs from 'dayjs';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { $api } from '@/shared/api/client';
import { asProblemError } from '@/shared/api/problem';
import { Can, useCan } from '@/shared/auth/Can';
import { FactGrid } from '@/shared/components/DetailLayout';
import { Quantity } from '@/shared/components/Figures';
import { notifySuccess } from '@/shared/components/notify';
import { ProblemAlert } from '@/shared/components/ProblemAlert';
import { QueryState } from '@/shared/components/QueryState';
import { ReasonModal } from '@/shared/components/ReasonModal';
import { StatusBadge } from '@/shared/components/StatusBadge';
import { applyProblemToForm } from '@/shared/forms/applyProblemToForm';
import { formatDate } from '@/shared/format/format';
import { CriteriaBuilder } from '@/features/criteria/CriteriaBuilder';
import { EXAMPLE, type Criterion } from '@/features/criteria/model/tree';
import {
  emptyObligation,
  fromConstraintsForm,
  needsCancellation,
  needsTarget,
  OBLIGATION_KINDS,
  obligationActions,
  PURPOSES,
  toConstraintsForm,
  toOpenObligation,
  validateConstraints,
  validateObligation,
  type Constraint,
  type ConstraintsForm,
  type Obligation,
  type ObligationForm,
} from './model/obligation';

const FORM_FIELDS = new Set([
  'kind',
  'quantityMwh',
  'deliveryWindow',
  'deliveryDate',
  'priorityClass',
  'criterion',
  'targetRegistryCode',
  'targetAccountRef',
  'cancellation',
]);

function useRefreshAllocation() {
  const queryClient = useQueryClient();
  return () =>
    queryClient.invalidateQueries({
      predicate: (q) =>
        typeof q.queryKey[1] === 'string' &&
        (q.queryKey[1].startsWith('/api/v1/allocation') || q.queryKey[1].startsWith('/api/v1/positions')),
    });
}

function translated(errors: Record<string, string>, t: (key: string) => string): Record<string, string> {
  return Object.fromEntries(Object.entries(errors).map(([field, key]) => [field, t(key)]));
}

/** Opens a manual obligation (disclosure, own consumption, or a sale outside a trade) for the selected entity. */
export function OpenObligationModal({
  entityId,
  onClose,
  onOpened,
}: {
  entityId: string;
  onClose: () => void;
  onOpened: (obligation: Obligation) => void;
}) {
  const { t } = useTranslation();
  const refresh = useRefreshAllocation();
  const form = useForm<ObligationForm>({
    initialValues: emptyObligation(dayjs().format('YYYY-MM-DD')),
    validate: (values) => translated(validateObligation(values), t),
  });
  const open = $api.useMutation('post', '/api/v1/allocation/obligations', {
    meta: { notify: false },
    onSuccess: (obligation) => {
      notifySuccess(t('allocation.obligationOpened'));
      void refresh();
      onOpened(obligation);
    },
    onError: (error) => {
      const problem = asProblemError(error);
      if (problem.status === 422) applyProblemToForm(form, problem, FORM_FIELDS);
    },
  });
  const kind = form.values.kind;
  const submit = form.onSubmit((values) => open.mutate({ body: toOpenObligation(values, entityId) }));
  return (
    <Modal opened onClose={onClose} title={t('allocation.openObligationTitle')} size="xl">
      <form onSubmit={submit}>
        <Stack>
          <Text size="sm" c="dimmed">
            {t('allocation.openObligationHint')}
          </Text>
          <ProblemAlert error={open.error} />
          <SimpleGrid cols={{ base: 1, sm: 3 }}>
            <Select
              label={t('allocation.kind')}
              data={OBLIGATION_KINDS.map((value) => ({ value, label: t(`allocation.kinds.${value}`) }))}
              allowDeselect={false}
              data-testid="obligation-kind"
              {...form.getInputProps('kind')}
              onChange={(value) => {
                if (!value) return;
                const next = value as ObligationForm['kind'];
                form.setFieldValue('kind', next);
                if (needsCancellation(next))
                  form.setFieldValue('purpose', next === 'DISCLOSURE' ? 'DISCLOSURE' : 'OWN_CONSUMPTION');
              }}
            />
            <TextInput
              label={t('allocation.quantityMwh')}
              inputMode="numeric"
              required
              data-testid="obligation-quantity"
              {...form.getInputProps('quantityMwh')}
            />
            <TextInput
              label={t('allocation.priorityClass')}
              description={t('allocation.priorityHint')}
              inputMode="numeric"
              data-testid="obligation-priority"
              {...form.getInputProps('priorityClass')}
            />
            <TextInput
              type="date"
              label={t('allocation.windowStart')}
              required
              data-testid="obligation-window-start"
              {...form.getInputProps('windowStart')}
            />
            <TextInput
              type="date"
              label={t('allocation.windowEnd')}
              required
              data-testid="obligation-window-end"
              {...form.getInputProps('windowEnd')}
            />
            <TextInput
              type="date"
              label={t('allocation.deliveryDate')}
              required
              data-testid="obligation-delivery-date"
              {...form.getInputProps('deliveryDate')}
            />
          </SimpleGrid>
          {needsTarget(kind) && (
            <SimpleGrid cols={{ base: 1, sm: 2 }}>
              <TextInput
                label={t('allocation.targetRegistry')}
                data-testid="obligation-target-registry"
                {...form.getInputProps('targetRegistryCode')}
              />
              <TextInput
                label={t('allocation.targetAccount')}
                required
                data-testid="obligation-target-account"
                {...form.getInputProps('targetAccountRef')}
              />
            </SimpleGrid>
          )}
          {needsCancellation(kind) && (
            <>
              <Divider label={t('allocation.cancellation')} labelPosition="left" />
              <SimpleGrid cols={{ base: 1, sm: 3 }}>
                <Select
                  label={t('allocation.purpose')}
                  data={PURPOSES.map((value) => ({ value, label: t(`allocation.purposes.${value}`) }))}
                  allowDeselect={false}
                  data-testid="obligation-purpose"
                  {...form.getInputProps('purpose')}
                />
                <TextInput
                  label={t('allocation.beneficiary')}
                  data-testid="obligation-beneficiary"
                  {...form.getInputProps('beneficiaryName')}
                />
                <TextInput
                  label={t('allocation.consumptionCountry')}
                  maxLength={2}
                  required
                  data-testid="obligation-country"
                  {...form.getInputProps('consumptionCountry')}
                />
                <TextInput
                  type="date"
                  label={t('allocation.consumptionStart')}
                  required
                  data-testid="obligation-consumption-start"
                  {...form.getInputProps('consumptionStart')}
                />
                <TextInput
                  type="date"
                  label={t('allocation.consumptionEnd')}
                  required
                  data-testid="obligation-consumption-end"
                  {...form.getInputProps('consumptionEnd')}
                />
              </SimpleGrid>
            </>
          )}
          <Divider label={t('allocation.criteria')} labelPosition="left" />
          <CriteriaBuilder value={form.values.criterion} onChange={(c) => form.setFieldValue('criterion', c)} />
          {form.errors.criterion && (
            <Text size="sm" c="red">
              {form.errors.criterion}
            </Text>
          )}
          <Group justify="flex-end">
            <Button variant="default" onClick={onClose}>
              {t('common.cancel')}
            </Button>
            <Button type="submit" loading={open.isPending} data-testid="obligation-submit">
              {t('allocation.openObligation')}
            </Button>
          </Group>
        </Stack>
      </form>
    </Modal>
  );
}

/** The server's reading of a criterion, e.g. "Hydro in the Nordics". */
function CriterionSummary({ criterion }: { criterion: Criterion }) {
  const summary = $api.useQuery(
    'post',
    '/api/v1/products/criteria/validate',
    { body: criterion },
    { retry: false, staleTime: Infinity },
  );
  return <>{summary.data?.summary ?? '…'}</>;
}

function ConstraintLines({ constraints }: { constraints: readonly Constraint[] }) {
  const { t } = useTranslation();
  if (constraints.length === 0) {
    return (
      <Text size="sm" c="dimmed" data-testid="obligation-constraints-none">
        {t('allocation.noConstraints')}
      </Text>
    );
  }
  return (
    <Stack gap={4} data-testid="obligation-constraints">
      {constraints.map((constraint, index) => (
        <Text size="sm" key={index}>
          {constraint.type === 'maxSharePerDevice' ? (
            t('allocation.constraintMax', { percent: constraint.percent })
          ) : (
            <>
              {t('allocation.constraintMin', { percent: constraint.percent })}{' '}
              <CriterionSummary criterion={constraint.criterion} />
            </>
          )}
        </Text>
      ))}
    </Stack>
  );
}

/** Replaces an obligation's share constraints: a device limit and minimum shares by criterion. */
export function ConstraintsModal({ obligation, onClose }: { obligation: Obligation; onClose: () => void }) {
  const { t } = useTranslation();
  const refresh = useRefreshAllocation();
  const form = useForm<ConstraintsForm>({
    initialValues: toConstraintsForm(obligation.constraints),
    validate: (values) => translated(validateConstraints(values), t),
  });
  const save = $api.useMutation('put', '/api/v1/allocation/obligations/{id}/constraints', {
    meta: { notify: false },
    onSuccess: () => {
      notifySuccess(t('allocation.constraintsSaved'));
      void refresh();
      onClose();
    },
  });
  const submit = form.onSubmit((values) =>
    save.mutate({ params: { path: { id: obligation.id } }, body: fromConstraintsForm(values) }),
  );
  return (
    <Modal opened onClose={onClose} title={t('allocation.constraints')} size="xl">
      <form onSubmit={submit}>
        <Stack>
          <Text size="sm" c="dimmed">
            {t('allocation.constraintsHint')}
          </Text>
          <ProblemAlert error={save.error} />
          <TextInput
            label={t('allocation.maxSharePerDevice')}
            description={t('allocation.maxShareHint')}
            inputMode="numeric"
            w={260}
            data-testid="constraints-max-share"
            {...form.getInputProps('maxSharePerDevice')}
          />
          <Title order={6}>{t('allocation.minShares')}</Title>
          {form.values.minShares.map((share, index) => (
            <Paper withBorder p="sm" radius="md" key={index} data-testid={`constraints-min-share-${index}`}>
              <Stack gap="xs">
                <Group align="flex-end" justify="space-between">
                  <TextInput
                    label={t('allocation.minSharePercent')}
                    inputMode="numeric"
                    w={200}
                    data-testid={`constraints-min-share-${index}-percent`}
                    {...form.getInputProps(`minShares.${index}.percent`)}
                  />
                  <ActionIcon
                    variant="subtle"
                    color="red"
                    aria-label={t('allocation.removeMinShare')}
                    onClick={() => form.removeListItem('minShares', index)}
                    data-testid={`constraints-min-share-${index}-remove`}
                  >
                    <IconTrash size={16} />
                  </ActionIcon>
                </Group>
                <CriteriaBuilder
                  value={share.criterion}
                  onChange={(criterion) => form.setFieldValue(`minShares.${index}.criterion`, criterion)}
                />
                {form.errors[`minShares.${index}.criterion`] && (
                  <Text size="sm" c="red">
                    {form.errors[`minShares.${index}.criterion`]}
                  </Text>
                )}
              </Stack>
            </Paper>
          ))}
          <Group>
            <Button
              variant="light"
              size="xs"
              leftSection={<IconPlus size={14} />}
              onClick={() => form.insertListItem('minShares', { criterion: EXAMPLE, percent: '' })}
              data-testid="constraints-add-min-share"
            >
              {t('allocation.addMinShare')}
            </Button>
          </Group>
          <Group justify="flex-end">
            <Button variant="default" onClick={onClose}>
              {t('common.cancel')}
            </Button>
            <Button type="submit" loading={save.isPending} data-testid="constraints-save">
              {t('common.save')}
            </Button>
          </Group>
        </Stack>
      </form>
    </Modal>
  );
}

/** One obligation in a drawer: its facts, constraints and the actions its state allows. */
export function ObligationDrawer({ obligationId, onClose }: { obligationId: string | null; onClose: () => void }) {
  const { t } = useTranslation();
  const refresh = useRefreshAllocation();
  const obligation = $api.useQuery(
    'get',
    '/api/v1/allocation/obligations/{id}',
    { params: { path: { id: obligationId ?? '' } } },
    { enabled: Boolean(obligationId) },
  );
  const canClose = useCan('allocation_closeObligation');
  const [editing, setEditing] = useState(false);
  const [closing, setClosing] = useState(false);
  const close = $api.useMutation('post', '/api/v1/allocation/obligations/{id}/close', {
    meta: { notify: false },
    onSuccess: () => {
      notifySuccess(t('allocation.obligationClosed'));
      setClosing(false);
      void refresh();
    },
  });
  const data = obligation.data;
  const actions = data ? obligationActions(data) : { close: false, constraints: false };
  return (
    <Drawer
      opened={Boolean(obligationId)}
      onClose={onClose}
      position="right"
      size="lg"
      title={
        data
          ? t('allocation.obligationTitle', {
              kind: t(`allocation.kinds.${data.kind}`),
              date: formatDate(data.deliveryDate),
            })
          : ''
      }
      data-testid="obligation-drawer"
    >
      <QueryState isPending={obligation.isPending && Boolean(obligationId)} error={obligation.error}>
        {data && (
          <Stack>
            <Group justify="space-between">
              <StatusBadge status={data.status} data-testid="obligation-status" />
              <Group gap="xs">
                {actions.constraints && (
                  <Can op="allocation_setConstraints">
                    <Button
                      size="xs"
                      variant="light"
                      leftSection={<IconAdjustments size={14} />}
                      onClick={() => setEditing(true)}
                      data-testid="obligation-edit-constraints"
                    >
                      {t('allocation.editConstraints')}
                    </Button>
                  </Can>
                )}
                {actions.close && canClose && (
                  <Button
                    size="xs"
                    variant="light"
                    color="red"
                    leftSection={<IconLock size={14} />}
                    onClick={() => setClosing(true)}
                    data-testid="obligation-close"
                  >
                    {t('allocation.closeObligation')}
                  </Button>
                )}
              </Group>
            </Group>
            <Text size="sm" fw={500} data-testid="obligation-criteria">
              {data.criteriaSummary}
            </Text>
            <FactGrid
              cols={2}
              facts={[
                {
                  label: t('allocation.quantity'),
                  value: <Quantity value={data.quantityMwh} unit="MWh" />,
                  testId: 'obligation-quantity-value',
                },
                { label: t('allocation.fulfilled'), value: <Quantity value={data.fulfilledMwh} unit="MWh" /> },
                { label: t('allocation.remaining'), value: <Quantity value={data.remainingMwh} unit="MWh" /> },
                { label: t('allocation.priority'), value: data.priorityClass },
                {
                  label: t('allocation.window'),
                  value: `${formatDate(data.deliveryWindow.start)} – ${formatDate(data.deliveryWindow.endInclusive)}`,
                },
                { label: t('allocation.trade'), value: data.tradeRef ?? '—' },
                {
                  label: t('allocation.target'),
                  value: data.targetAccountRef
                    ? [data.targetRegistryCode, data.targetAccountRef].filter(Boolean).join(':')
                    : '—',
                },
              ]}
            />
            {data.cancellation && (
              <>
                <Divider label={t('allocation.cancellation')} labelPosition="left" />
                <FactGrid
                  cols={2}
                  facts={[
                    { label: t('allocation.purpose'), value: t(`allocation.purposes.${data.cancellation.purpose}`) },
                    { label: t('allocation.beneficiary'), value: data.cancellation.beneficiaryName ?? '—' },
                    { label: t('allocation.consumptionCountry'), value: data.cancellation.consumptionCountry },
                    {
                      label: t('allocation.consumptionStart'),
                      value: `${formatDate(data.cancellation.consumptionPeriod.start)} – ${formatDate(
                        data.cancellation.consumptionPeriod.endInclusive,
                      )}`,
                    },
                  ]}
                />
              </>
            )}
            <Divider label={t('allocation.constraints')} labelPosition="left" />
            <ConstraintLines constraints={data.constraints} />
            {data.status === 'CLOSED' && (
              <Alert color="gray" data-testid="obligation-closed">
                {t('allocation.obligationClosed')}
              </Alert>
            )}
          </Stack>
        )}
      </QueryState>
      {editing && data && <ConstraintsModal obligation={data} onClose={() => setEditing(false)} />}
      {closing && data && (
        <ReasonModal
          title={t('allocation.closeObligation')}
          hint={
            data.tradeRef
              ? t('allocation.closeHintTrade', { mwh: data.remainingMwh, trade: data.tradeRef })
              : t('allocation.closeHint', { mwh: data.remainingMwh })
          }
          action={t('allocation.closeObligation')}
          pending={close.isPending}
          error={close.error}
          onSubmit={(reason) => close.mutate({ params: { path: { id: data.id } }, body: { reason } })}
          onClose={() => setClosing(false)}
        />
      )}
    </Drawer>
  );
}
