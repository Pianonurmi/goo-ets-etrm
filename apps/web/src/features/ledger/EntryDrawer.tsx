import { Anchor, Button, Drawer, Group, Stack, Table, Text, Textarea } from '@mantine/core';
import { IconRotate2 } from '@tabler/icons-react';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { $api } from '@/shared/api/client';
import { Can } from '@/shared/auth/Can';
import { notifySuccess } from '@/shared/components/notify';
import { ProblemAlert } from '@/shared/components/ProblemAlert';
import { QueryState } from '@/shared/components/QueryState';
import { formatDate, formatDateTime } from '@/shared/format/format';
import { useInvalidateLedger } from './hooks';
import { EntryStatusBadge, amount } from './shared';

export function EntryDrawer({
  entryId,
  onClose,
  onOpen,
}: {
  entryId: string;
  onClose: () => void;
  onOpen: (id: string) => void;
}) {
  const { t } = useTranslation();
  const invalidate = useInvalidateLedger();
  const [reason, setReason] = useState('');
  const entry = $api.useQuery('get', '/api/v1/ledger/entries/{id}', { params: { path: { id: entryId } } });
  const reverse = $api.useMutation('post', '/api/v1/ledger/entries/{id}/reverse', {
    meta: { notify: false },
    onSuccess: (reversal) => {
      notifySuccess(t('ledger.reversed', { number: reversal.entryNumber }));
      setReason('');
      void invalidate();
    },
  });
  const e = entry.data;
  return (
    <Drawer
      opened
      onClose={onClose}
      position="right"
      size="xl"
      title={e ? t('ledger.entryTitle', { number: e.entryNumber }) : ''}
    >
      <QueryState isPending={entry.isPending} error={entry.error}>
        {e && (
          <Stack>
            <Group gap="xs">
              <EntryStatusBadge status={e.status} />
              <Text size="sm">{e.description}</Text>
            </Group>
            <Table withRowBorders={false} fz="sm">
              <Table.Tbody>
                <Table.Tr>
                  <Table.Td c="dimmed">{t('ledger.postingDate')}</Table.Td>
                  <Table.Td>
                    {formatDate(e.postingDate)} · {e.period}
                  </Table.Td>
                </Table.Tr>
                <Table.Tr>
                  <Table.Td c="dimmed">{t('ledger.rule')}</Table.Td>
                  <Table.Td>
                    {e.ruleCode ?? t('ledger.manual')} · {e.eventType}
                  </Table.Td>
                </Table.Tr>
                <Table.Tr>
                  <Table.Td c="dimmed">{t('ledger.source')}</Table.Td>
                  <Table.Td>{[e.sourceType, e.sourceRef].filter(Boolean).join(' ') || '–'}</Table.Td>
                </Table.Tr>
                <Table.Tr>
                  <Table.Td c="dimmed">{t('ledger.createdBy')}</Table.Td>
                  <Table.Td>
                    {e.createdBy} · {formatDateTime(e.createdAt)}
                  </Table.Td>
                </Table.Tr>
                {(e.reversalOf ?? e.reversedBy) && (
                  <Table.Tr>
                    <Table.Td c="dimmed">{e.reversalOf ? t('ledger.reversalOf') : t('ledger.reversedBy')}</Table.Td>
                    <Table.Td>
                      <Anchor size="sm" onClick={() => onOpen((e.reversalOf ?? e.reversedBy) as string)}>
                        {t('ledger.openEntry')}
                      </Anchor>
                    </Table.Td>
                  </Table.Tr>
                )}
                <Table.Tr>
                  <Table.Td c="dimmed">{t('ledger.hash')}</Table.Td>
                  <Table.Td>
                    <Text size="xs" ff="monospace">
                      {e.hash.slice(0, 16)}…
                    </Text>
                  </Table.Td>
                </Table.Tr>
              </Table.Tbody>
            </Table>
            <Table withTableBorder striped data-testid="entry-lines">
              <Table.Thead>
                <Table.Tr>
                  <Table.Th>{t('ledger.account')}</Table.Th>
                  <Table.Th ta="right">{t('ledger.debit')}</Table.Th>
                  <Table.Th ta="right">{t('ledger.credit')}</Table.Th>
                  <Table.Th>{t('ledger.memo')}</Table.Th>
                </Table.Tr>
              </Table.Thead>
              <Table.Tbody>
                {e.lines.map((line) => (
                  <Table.Tr key={line.lineNo}>
                    <Table.Td>
                      {line.accountCode} · {line.accountName}
                    </Table.Td>
                    <Table.Td ta="right">{line.debit > 0 ? amount(line.debit) : ''}</Table.Td>
                    <Table.Td ta="right">{line.credit > 0 ? amount(line.credit) : ''}</Table.Td>
                    <Table.Td>
                      <Text size="xs">
                        {[line.memo, ...Object.entries(line.dimensions).map(([k, v]) => `${k}=${v}`)]
                          .filter(Boolean)
                          .join(' · ')}
                      </Text>
                    </Table.Td>
                  </Table.Tr>
                ))}
                <Table.Tr fw={600}>
                  <Table.Td>{e.currency}</Table.Td>
                  <Table.Td ta="right">{amount(e.totalDebit)}</Table.Td>
                  <Table.Td ta="right">{amount(e.totalCredit)}</Table.Td>
                  <Table.Td />
                </Table.Tr>
              </Table.Tbody>
            </Table>
            {e.status === 'POSTED' && (
              <Can op="ledger_reverse">
                <Stack gap="xs">
                  <ProblemAlert error={reverse.error} />
                  <Textarea
                    label={t('ledger.reverseReason')}
                    value={reason}
                    onChange={(event) => setReason(event.currentTarget.value)}
                    autosize
                    minRows={2}
                  />
                  <Button
                    color="orange"
                    variant="light"
                    leftSection={<IconRotate2 size={16} />}
                    disabled={!reason.trim()}
                    loading={reverse.isPending}
                    onClick={() => reverse.mutate({ params: { path: { id: e.id } }, body: { reason } })}
                  >
                    {t('ledger.reverse')}
                  </Button>
                </Stack>
              </Can>
            )}
          </Stack>
        )}
      </QueryState>
    </Drawer>
  );
}
