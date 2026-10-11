import {
  ActionIcon,
  Autocomplete,
  Button,
  Group,
  Modal,
  NumberInput,
  Select,
  Stack,
  Switch,
  Text,
  TextInput,
  Textarea,
} from '@mantine/core';
import { IconPlus, IconTrash } from '@tabler/icons-react';
import dayjs from 'dayjs';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { $api } from '@/shared/api/client';
import { asProblemError } from '@/shared/api/problem';
import { notifySuccess } from '@/shared/components/notify';
import { ProblemAlert } from '@/shared/components/ProblemAlert';
import { applyProblemToForm } from '@/shared/forms/applyProblemToForm';
import { useAccounts, useInvalidateLedger } from './hooks';
import {
  emptyRule,
  parseConditions,
  ruleBody,
  ruleDraft,
  ruleProblems,
  type PostingRule,
  type RuleDraft,
  type RuleLineDraft,
} from './model/rules';

/**
 * Proposes a posting rule, new or changed. Nothing changes until a second accountant approves it in the inbox, so the
 * journal never posts by a rule only one person has seen.
 */
export function RuleChangeModal({
  entityId,
  rule,
  eventTypes,
  onClose,
}: {
  entityId: string;
  rule?: PostingRule;
  eventTypes: string[];
  onClose: () => void;
}) {
  const { t } = useTranslation();
  const invalidate = useInvalidateLedger();
  const accounts = useAccounts(entityId);
  const [draft, setDraft] = useState<RuleDraft>(() =>
    rule ? ruleDraft(rule) : emptyRule(dayjs().format('YYYY-MM-DD')),
  );
  const [errors, setErrors] = useState<Record<string, string>>({});
  const propose = $api.useMutation('put', '/api/v1/ledger/rules', {
    meta: { notify: false },
    onSuccess: (change) => {
      notifySuccess(t('ledger.ruleProposed', { rule: change.ruleCode }));
      void invalidate();
      onClose();
    },
    onError: (error) => {
      const problem = asProblemError(error);
      if (problem.status === 422) {
        applyProblemToForm(
          { setFieldError: (path, message) => setErrors((current) => ({ ...current, [path]: message })) },
          problem,
        );
      }
    },
  });
  const set = (patch: Partial<RuleDraft>) => setDraft({ ...draft, ...patch });
  const setLine = (index: number, patch: Partial<RuleLineDraft>) =>
    set({ lines: draft.lines.map((line, i) => (i === index ? { ...line, ...patch } : line)) });
  const problems = ruleProblems(draft);
  const accountOptions = (accounts.data ?? [])
    .filter((account) => account.active || draft.lines.some((line) => line.account === account.code))
    .map((account) => ({ value: account.code, label: `${account.code} · ${account.name}` }));
  // A rule of the entity keeps it; a group rule (no entity) stays a group rule. A new rule belongs to the entity.
  const legalEntityId = rule ? (rule.legalEntityId ?? null) : entityId;
  return (
    <Modal
      opened
      onClose={onClose}
      size="xl"
      title={rule ? t('ledger.changeRule', { rule: rule.ruleCode }) : t('ledger.newRule')}
    >
      <Stack>
        <ProblemAlert error={propose.error} />
        <Text size="sm" c="dimmed">
          {t('ledger.ruleFourEyes')}
        </Text>
        <Group grow align="flex-start">
          <TextInput
            label={t('ledger.rule')}
            description={t('admin.codeHint')}
            value={draft.ruleCode}
            disabled={Boolean(rule)}
            error={errors.ruleCode}
            onChange={(e) => set({ ruleCode: e.currentTarget.value.toUpperCase() })}
            data-testid="rule-code"
          />
          <Autocomplete
            label={t('ledger.eventType')}
            data={eventTypes}
            value={draft.eventType}
            error={errors.eventType}
            onChange={(value) => set({ eventType: value })}
            data-testid="rule-event-type"
          />
        </Group>
        <TextInput
          label={t('ledger.descriptionColumn')}
          value={draft.description}
          onChange={(e) => set({ description: e.currentTarget.value })}
          data-testid="rule-description"
        />
        <Group grow align="flex-end">
          <NumberInput
            label={t('ledger.priority')}
            value={draft.priority}
            allowDecimal={false}
            onChange={(value) => set({ priority: Number(value) || 0 })}
            data-testid="rule-priority"
          />
          <TextInput
            type="date"
            label={t('risk.validFrom')}
            value={draft.effectiveFrom}
            error={errors.effectiveFrom}
            onChange={(e) => set({ effectiveFrom: e.currentTarget.value })}
            data-testid="rule-effective-from"
          />
          <Switch
            label={t('marketData.active')}
            checked={draft.active}
            onChange={(e) => set({ active: e.currentTarget.checked })}
            data-testid="rule-active"
            mb={8}
          />
        </Group>
        <Textarea
          label={t('ledger.conditions')}
          description={t('ledger.conditionsHint')}
          value={draft.conditions}
          autosize
          minRows={2}
          ff="monospace"
          error={parseConditions(draft.conditions) === null ? t('common.invalidJson') : errors.conditions}
          onChange={(e) => set({ conditions: e.currentTarget.value })}
          data-testid="rule-conditions"
        />
        <Text size="sm" fw={600}>
          {t('ledger.lines')}
        </Text>
        <Text size="xs" c="dimmed">
          {t('ledger.ruleAmountHint')}
        </Text>
        {draft.lines.map((line, index) => (
          <Group key={index} align="flex-end" wrap="nowrap">
            <Select
              aria-label={t('ledger.side')}
              data={(['DEBIT', 'CREDIT'] as const).map((side) => ({ value: side, label: t(`ledger.sides.${side}`) }))}
              value={line.side}
              allowDeselect={false}
              onChange={(value) => value && setLine(index, { side: value })}
              w={130}
              data-testid={`rule-line-${index}-side`}
            />
            <Select
              aria-label={t('ledger.account')}
              placeholder={t('ledger.account')}
              data={accountOptions}
              value={line.account || null}
              searchable
              onChange={(value) => setLine(index, { account: value ?? '' })}
              style={{ flex: 2 }}
              data-testid={`rule-line-${index}-account`}
            />
            <TextInput
              aria-label={t('ledger.amount')}
              placeholder={t('ledger.amount')}
              value={line.amount}
              onChange={(e) => setLine(index, { amount: e.currentTarget.value })}
              style={{ flex: 1 }}
              data-testid={`rule-line-${index}-amount`}
            />
            <ActionIcon
              variant="subtle"
              color="red"
              aria-label={t('ledger.removeLine')}
              disabled={draft.lines.length <= 2}
              onClick={() => set({ lines: draft.lines.filter((_, i) => i !== index) })}
              mb={4}
            >
              <IconTrash size={16} />
            </ActionIcon>
          </Group>
        ))}
        <Group justify="space-between">
          <Button
            variant="subtle"
            leftSection={<IconPlus size={14} />}
            onClick={() => set({ lines: [...draft.lines, { side: 'DEBIT', account: '', amount: 'amount' }] })}
            data-testid="rule-add-line"
          >
            {t('ledger.addLine')}
          </Button>
          {problems.includes('sides') && (
            <Text size="xs" c="red">
              {t('ledger.ruleSidesHint')}
            </Text>
          )}
        </Group>
        <Button
          disabled={problems.length > 0}
          loading={propose.isPending}
          onClick={() => {
            setErrors({});
            propose.mutate({ body: ruleBody(draft, legalEntityId) });
          }}
          data-testid="rule-propose"
        >
          {t('risk.sendForApproval')}
        </Button>
      </Stack>
    </Modal>
  );
}
