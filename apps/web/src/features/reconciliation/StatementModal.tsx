import { Alert, Anchor, Button, Group, Modal, Stack, Table, Text } from '@mantine/core';
import { IconRefresh } from '@tabler/icons-react';
import { useTranslation } from 'react-i18next';
import { Link } from 'react-router';
import { $api } from '@/shared/api/client';
import { Can } from '@/shared/auth/Can';
import { FactGrid } from '@/shared/components/DetailLayout';
import { notifySuccess } from '@/shared/components/notify';
import { ProblemAlert } from '@/shared/components/ProblemAlert';
import { QueryState } from '@/shared/components/QueryState';
import { StatusBadge } from '@/shared/components/StatusBadge';
import { formatDate } from '@/shared/format/format';
import { breakSubject, canReconcile } from './model/breaks';
import { BreakStatusBadge, useInvalidateReconciliation } from './ReconciliationPage';

/**
 * One registry statement as the server has it: date, source, lines, status and the breaks it found. A statement
 * imported without reconciling (a registry adapter's structured import) is reconciled from here.
 */
export function StatementModal({ statementId, onClose }: { statementId: string; onClose: () => void }) {
  const { t } = useTranslation();
  const invalidate = useInvalidateReconciliation();
  const statement = $api.useQuery('get', '/api/v1/inventory/statements/{id}', {
    params: { path: { id: statementId } },
  });
  const reconcile = $api.useMutation('post', '/api/v1/inventory/statements/{id}/reconcile', {
    meta: { notify: false },
    onSuccess: (result) => {
      notifySuccess(t('reconciliation.reconciled', { count: result.breaks.length }));
      void invalidate();
    },
  });
  const data = statement.data;
  return (
    <Modal
      opened
      onClose={onClose}
      title={data ? t('reconciliation.statementOf', { date: formatDate(data.statementDate) }) : ''}
      size="lg"
    >
      <QueryState isPending={statement.isPending} error={statement.error}>
        {data && (
          <Stack data-testid="statement-detail">
            <FactGrid
              cols={3}
              facts={[
                {
                  label: t('allocation.status'),
                  value: <StatusBadge status={data.status} data-testid="statement-status" />,
                },
                { label: t('registry.kind'), value: t(`reconciliation.statementKinds.${data.kind}`) },
                { label: t('registry.registry'), value: data.registryCode },
                { label: t('reconciliation.source'), value: data.source },
                { label: t('reconciliation.lines'), value: data.lineCount, testId: 'statement-lines' },
                { label: t('reconciliation.breaks'), value: data.breaks.length, testId: 'statement-breaks' },
              ]}
            />
            <ProblemAlert error={reconcile.error} />
            {canReconcile(data) && (
              <Alert color="yellow">
                <Group justify="space-between" wrap="nowrap">
                  <Text size="sm">{t('reconciliation.notReconciled')}</Text>
                  <Can op="inventory_reconcile">
                    <Button
                      size="xs"
                      leftSection={<IconRefresh size={14} />}
                      loading={reconcile.isPending}
                      onClick={() => reconcile.mutate({ params: { path: { id: data.id } } })}
                      data-testid="statement-reconcile"
                    >
                      {t('reconciliation.reconcile')}
                    </Button>
                  </Can>
                </Group>
              </Alert>
            )}
            {data.breaks.length === 0 ? (
              data.status !== 'IMPORTED' && <Text size="sm">{t('reconciliation.noBreaks')}</Text>
            ) : (
              <Table>
                <Table.Tbody>
                  {data.breaks.map((brk) => (
                    <Table.Tr key={brk.id}>
                      <Table.Td>
                        <Anchor component={Link} to={`/reconciliation/breaks/${brk.id}`} size="sm">
                          {t(`reconciliation.types.${brk.type}`)}
                        </Anchor>
                      </Table.Td>
                      <Table.Td>{breakSubject(brk)}</Table.Td>
                      <Table.Td>
                        <BreakStatusBadge status={brk.status} />
                      </Table.Td>
                    </Table.Tr>
                  ))}
                </Table.Tbody>
              </Table>
            )}
          </Stack>
        )}
      </QueryState>
    </Modal>
  );
}
