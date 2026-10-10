import { ActionIcon, Button, Group, Modal, Select, Stack, Text, TextInput } from '@mantine/core';
import { IconPlus, IconTrash } from '@tabler/icons-react';
import dayjs from 'dayjs';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { $api } from '@/shared/api/client';
import { decimal, type Schema } from '@/shared/api/types';
import { notifySuccess } from '@/shared/components/notify';
import { ProblemAlert } from '@/shared/components/ProblemAlert';
import { useInvalidateLedger } from './hooks';
import { emptyLine, manualLines, readyToPost, type LineDraft } from './model/manual';

/** A manual journal entry; the server checks that it balances and that the period is open. */
export function ManualJournalModal({
  entityId,
  accounts,
  onClose,
}: {
  entityId: string;
  accounts: Schema<'GlAccountView'>[];
  onClose: () => void;
}) {
  const { t } = useTranslation();
  const invalidate = useInvalidateLedger();
  const [postingDate, setPostingDate] = useState(dayjs().format('YYYY-MM-DD'));
  const [currency, setCurrency] = useState('EUR');
  const [description, setDescription] = useState('');
  const [sourceRef, setSourceRef] = useState('');
  const [lines, setLines] = useState<LineDraft[]>([emptyLine(), emptyLine()]);
  const post = $api.useMutation('post', '/api/v1/ledger/entries', {
    meta: { notify: false },
    onSuccess: (entry) => {
      notifySuccess(t('ledger.posted', { number: entry.entryNumber }));
      void invalidate();
      onClose();
    },
  });
  const update = (index: number, patch: Partial<LineDraft>) =>
    setLines(lines.map((line, i) => (i === index ? { ...line, ...patch } : line)));
  const accountOptions = accounts
    .filter((account) => account.active)
    .map((account) => ({ value: account.code, label: `${account.code} · ${account.name}` }));
  return (
    <Modal opened onClose={onClose} title={t('ledger.manualEntry')} size="xl">
      <Stack>
        <ProblemAlert error={post.error} />
        <Group grow>
          <TextInput
            type="date"
            label={t('ledger.postingDate')}
            value={postingDate}
            onChange={(e) => setPostingDate(e.currentTarget.value)}
          />
          <TextInput
            label={t('trades.form.currency')}
            value={currency}
            maxLength={3}
            onChange={(e) => setCurrency(e.currentTarget.value.toUpperCase())}
          />
          <TextInput
            label={t('ledger.source')}
            value={sourceRef}
            onChange={(e) => setSourceRef(e.currentTarget.value)}
          />
        </Group>
        <TextInput
          label={t('ledger.descriptionColumn')}
          value={description}
          onChange={(e) => setDescription(e.currentTarget.value)}
          required
        />
        <Text size="sm" fw={600}>
          {t('ledger.lines')}
        </Text>
        {lines.map((line, index) => (
          <Group key={index} align="flex-end" wrap="nowrap">
            <Select
              label={index === 0 ? t('ledger.account') : undefined}
              aria-label={t('ledger.account')}
              data={accountOptions}
              value={line.accountCode || null}
              onChange={(code) => update(index, { accountCode: code ?? '' })}
              searchable
              style={{ flex: 2 }}
            />
            <TextInput
              label={index === 0 ? t('ledger.debit') : undefined}
              aria-label={t('ledger.debit')}
              inputMode="decimal"
              value={line.debit}
              onChange={(e) => update(index, { debit: e.currentTarget.value })}
              style={{ flex: 1 }}
            />
            <TextInput
              label={index === 0 ? t('ledger.credit') : undefined}
              aria-label={t('ledger.credit')}
              inputMode="decimal"
              value={line.credit}
              onChange={(e) => update(index, { credit: e.currentTarget.value })}
              style={{ flex: 1 }}
            />
            <TextInput
              label={index === 0 ? t('ledger.memo') : undefined}
              aria-label={t('ledger.memo')}
              value={line.memo}
              onChange={(e) => update(index, { memo: e.currentTarget.value })}
              style={{ flex: 2 }}
            />
            <ActionIcon
              variant="subtle"
              color="red"
              aria-label={t('ledger.removeLine')}
              disabled={lines.length <= 2}
              onClick={() => setLines(lines.filter((_, i) => i !== index))}
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
            onClick={() => setLines([...lines, emptyLine()])}
          >
            {t('ledger.addLine')}
          </Button>
          <Text size="xs" c="dimmed">
            {t('ledger.balanceHint')}
          </Text>
        </Group>
        <Button
          disabled={!description.trim() || !readyToPost(lines)}
          loading={post.isPending}
          onClick={() =>
            post.mutate({
              body: {
                legalEntityId: entityId,
                postingDate,
                currency,
                description,
                sourceRef: sourceRef || undefined,
                lines: manualLines(lines).map((line) => ({
                  ...line,
                  debit: line.debit === undefined ? undefined : decimal(line.debit),
                  credit: line.credit === undefined ? undefined : decimal(line.credit),
                })),
              },
            })
          }
        >
          {t('ledger.post')}
        </Button>
      </Stack>
    </Modal>
  );
}
