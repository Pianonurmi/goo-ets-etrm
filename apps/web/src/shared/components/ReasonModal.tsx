import { Button, Modal, Stack, Text, Textarea } from '@mantine/core';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { ProblemAlert } from './ProblemAlert';

/** Asks for the reason of a destructive or audited action (discard, cancel, fail, credit). */
export function ReasonModal({
  title,
  hint,
  action,
  pending,
  error,
  onSubmit,
  onClose,
}: {
  title: string;
  hint: string;
  action: string;
  pending: boolean;
  error: unknown;
  onSubmit: (reason: string) => void;
  onClose: () => void;
}) {
  const { t } = useTranslation();
  const [reason, setReason] = useState('');
  return (
    <Modal opened onClose={onClose} title={title}>
      <Stack>
        <ProblemAlert error={error} />
        <Text size="sm" c="dimmed">
          {hint}
        </Text>
        <Textarea
          label={t('common.reason')}
          value={reason}
          onChange={(e) => setReason(e.currentTarget.value)}
          required
          autosize
          minRows={2}
          data-testid="reason"
        />
        <Button
          color="red"
          disabled={!reason.trim()}
          loading={pending}
          onClick={() => onSubmit(reason.trim())}
          data-testid="reason-submit"
        >
          {action}
        </Button>
      </Stack>
    </Modal>
  );
}
