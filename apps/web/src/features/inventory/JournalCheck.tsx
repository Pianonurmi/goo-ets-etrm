import { Alert, Anchor, Button, List, Modal, Stack, Text } from '@mantine/core';
import { IconShieldCheck } from '@tabler/icons-react';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link } from 'react-router';
import { $api } from '@/shared/api/client';
import { QueryState } from '@/shared/components/QueryState';
import { journalResult } from './model/lotOps';

function JournalResult({ onClose }: { onClose: () => void }) {
  const { t } = useTranslation();
  // Each opening checks again; the result is not cached.
  const check = $api.useQuery('get', '/api/v1/inventory/journal/verify', {}, { gcTime: 0, staleTime: 0 });
  const result = journalResult(check.data);
  return (
    <Modal opened onClose={onClose} title={t('inventory.journal.title')}>
      <Stack>
        <Text size="sm" c="dimmed">
          {t('inventory.journal.hint')}
        </Text>
        <QueryState isPending={check.isPending} error={check.error}>
          {result &&
            (result.ok ? (
              <Alert color="teal" data-testid="journal-result">
                {t('inventory.journal.intact')}
              </Alert>
            ) : (
              <Alert color="red" title={t('inventory.journal.broken')} data-testid="journal-result">
                <List size="sm">
                  {result.brokenLots.map((id) => (
                    <List.Item key={id}>
                      <Anchor component={Link} to={`/inventory/lots/${id}`} size="sm" onClick={onClose}>
                        {id}
                      </Anchor>
                    </List.Item>
                  ))}
                </List>
                {result.brokenHoldings.length > 0 && (
                  <>
                    <Text size="sm" mt="sm">
                      {t('inventory.journal.brokenHoldings')}
                    </Text>
                    <List size="sm">
                      {result.brokenHoldings.map((id) => (
                        <List.Item key={id}>{id}</List.Item>
                      ))}
                    </List>
                  </>
                )}
              </Alert>
            ))}
        </QueryState>
      </Stack>
    </Modal>
  );
}

/** Recomputes the hash chains of the lot journals and allowance movements and lists those that no longer verify. */
export function JournalCheckButton() {
  const { t } = useTranslation();
  const [open, setOpen] = useState(false);
  return (
    <>
      <Button
        variant="default"
        leftSection={<IconShieldCheck size={16} />}
        onClick={() => setOpen(true)}
        data-testid="verify-journal"
      >
        {t('inventory.journal.verify')}
      </Button>
      {open && <JournalResult onClose={() => setOpen(false)} />}
    </>
  );
}
