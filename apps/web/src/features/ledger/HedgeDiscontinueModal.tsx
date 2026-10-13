import { Button, Group, Modal, Stack, Switch, Text, Textarea } from '@mantine/core';
import { useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { $api } from '@/shared/api/client';
import { notifySuccess } from '@/shared/components/notify';
import { ProblemAlert } from '@/shared/components/ProblemAlert';
import { useInvalidateLedger } from './hooks';
import { toDiscontinuation } from './model/hedge';

/**
 * Proposes to stop hedge accounting. A second accountant approves; the reserve is then frozen (the forecast is still
 * expected) or recycled to profit or loss (it is not).
 */
export function HedgeDiscontinueModal({
  hedgeId,
  number,
  onClose,
}: {
  hedgeId: string;
  number: string;
  onClose: () => void;
}) {
  const { t } = useTranslation();
  const invalidate = useInvalidateLedger();
  const queryClient = useQueryClient();
  const [reason, setReason] = useState('');
  const [stillExpected, setStillExpected] = useState(true);
  const discontinue = $api.useMutation('post', '/api/v1/ledger/hedges/{id}/discontinuation', {
    meta: { notify: false },
    onSuccess: () => {
      notifySuccess(t('hedges.discontinue.requested', { number }));
      void invalidate();
      void queryClient.invalidateQueries({
        predicate: (q) => typeof q.queryKey[1] === 'string' && q.queryKey[1].startsWith('/api/v1/workflow'),
      });
      onClose();
    },
  });
  return (
    <Modal
      opened
      onClose={onClose}
      title={t('hedges.discontinue.title', { number })}
      data-testid="hedge-discontinue-modal"
    >
      <Stack>
        <ProblemAlert error={discontinue.error} />
        <Text size="sm" c="dimmed">
          {t('hedges.discontinue.intro')}
        </Text>
        <Textarea
          label={t('hedges.discontinue.reason')}
          value={reason}
          onChange={(e) => setReason(e.currentTarget.value)}
          required
          autosize
          minRows={2}
          data-testid="hedge-discontinue-reason"
        />
        <Switch
          label={t('hedges.discontinue.stillExpected')}
          description={t('hedges.discontinue.stillExpectedHint')}
          checked={stillExpected}
          onChange={(e) => setStillExpected(e.currentTarget.checked)}
          data-testid="hedge-discontinue-expected"
        />
        <Group justify="flex-end">
          <Button variant="default" onClick={onClose}>
            {t('common.cancel')}
          </Button>
          <Button
            color="red"
            disabled={!reason.trim()}
            loading={discontinue.isPending}
            onClick={() =>
              discontinue.mutate({
                params: { path: { id: hedgeId } },
                body: toDiscontinuation(reason, stillExpected),
              })
            }
            data-testid="hedge-discontinue-submit"
          >
            {t('hedges.discontinue.submit')}
          </Button>
        </Group>
      </Stack>
    </Modal>
  );
}
