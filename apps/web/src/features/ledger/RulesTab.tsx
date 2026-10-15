import { Alert, Button, Drawer, Group, Stack, Text } from '@mantine/core';
import { IconPencil, IconPlus } from '@tabler/icons-react';
import type { ColDef, ICellRendererParams } from 'ag-grid-community';
import { useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { $api } from '@/shared/api/client';
import type { Schema } from '@/shared/api/types';
import { Can } from '@/shared/auth/Can';
import { QueryState } from '@/shared/components/QueryState';
import { formatDateTime } from '@/shared/format/format';
import { DataGrid } from '@/shared/grid/DataGrid';
import { RuleChangeModal } from './RuleChangeModal';
import { ActiveBadge } from './shared';

export type Rule = Schema<'PostingRuleView'>;

export function RuleHistory({ rule, onPropose, onClose }: { rule: Rule; onPropose: () => void; onClose: () => void }) {
  const { t } = useTranslation();
  const ruleCode = rule.ruleCode;
  const history = $api.useQuery('get', '/api/v1/ledger/rules/{ruleCode}/history', { params: { path: { ruleCode } } });
  return (
    <Drawer opened onClose={onClose} position="right" size="lg" title={t('ledger.ruleHistory', { rule: ruleCode })}>
      <Can op="ledger_upsertRule">
        <Button
          variant="light"
          mb="md"
          leftSection={<IconPencil size={16} />}
          onClick={onPropose}
          data-testid="rule-propose-change"
        >
          {t('ledger.proposeChange')}
        </Button>
      </Can>
      <QueryState isPending={history.isPending} error={history.error}>
        {(history.data ?? []).length === 0 ? (
          <Text size="sm" c="dimmed">
            {t('ledger.noHistory')}
          </Text>
        ) : (
          <Stack>
            {(history.data ?? []).map((version) => (
              <Stack key={version.version} gap={4}>
                <Text fw={600} size="sm">
                  {t('ledger.ruleVersion', { version: version.version, at: formatDateTime(version.recordedAt) })}
                </Text>
                <Text size="xs" c="dimmed">
                  {version.rule.eventType} · {t('ledger.priority')} {version.rule.priority ?? '–'} ·{' '}
                  {version.rule.effectiveFrom ?? ''}
                </Text>
                {version.rule.lines.map((line, index) => (
                  <Text size="xs" key={index} ff="monospace">
                    {line.side} {line.account} {line.amount}
                  </Text>
                ))}
              </Stack>
            ))}
          </Stack>
        )}
      </QueryState>
    </Drawer>
  );
}

export function RulesTab({ entityId }: { entityId: string }) {
  const { t } = useTranslation();
  const [open, setOpen] = useState<Rule | null>(null);
  const [proposing, setProposing] = useState<{ rule?: Rule } | null>(null);
  const rules = $api.useQuery('get', '/api/v1/ledger/rules', { params: { query: { legalEntityId: entityId } } });
  const changes = $api.useQuery('get', '/api/v1/ledger/rules/changes', { params: { query: { status: 'PENDING' } } });
  const eventTypes = useMemo(() => [...new Set((rules.data ?? []).map((rule) => rule.eventType))].sort(), [rules.data]);
  const columns = useMemo<ColDef<Rule>[]>(
    () => [
      { field: 'ruleCode', headerName: t('ledger.rule'), width: 260, sort: 'asc' },
      { field: 'eventType', headerName: t('ledger.eventType'), width: 200 },
      { field: 'priority', headerName: t('ledger.priority'), width: 100 },
      { field: 'version', headerName: t('trades.version'), width: 100 },
      { field: 'effectiveFrom', headerName: t('risk.validFrom'), width: 130 },
      {
        colId: 'lines',
        headerName: t('ledger.lines'),
        flex: 1,
        minWidth: 240,
        valueGetter: (p) =>
          p.data?.lines.map((line) => `${line.side === 'DEBIT' ? 'Dr' : 'Cr'} ${line.account}`).join(', '),
      },
      {
        field: 'active',
        headerName: t('marketData.active'),
        width: 120,
        cellRenderer: (p: ICellRendererParams<Rule>) => p.data && <ActiveBadge active={p.data.active} />,
      },
    ],
    [t],
  );
  return (
    <Stack>
      <Group justify="flex-end">
        <Can op="ledger_upsertRule">
          <Button leftSection={<IconPlus size={16} />} onClick={() => setProposing({})} data-testid="rule-new">
            {t('ledger.newRule')}
          </Button>
        </Can>
      </Group>
      {(changes.data ?? []).length > 0 && (
        <Alert color="yellow" data-testid="pending-rule-changes">
          {t('ledger.pendingRuleChanges', { codes: (changes.data ?? []).map((c) => c.ruleCode).join(', ') })}
        </Alert>
      )}
      <QueryState isPending={rules.isPending} error={rules.error}>
        <DataGrid<Rule>
          rowData={rules.data ?? []}
          columnDefs={columns}
          rowId={(row) => row.id}
          height={520}
          onRowClicked={(event) => {
            if (event.data) setOpen(event.data);
          }}
        />
      </QueryState>
      {open && (
        <RuleHistory
          rule={open}
          onPropose={() => {
            setProposing({ rule: open });
            setOpen(null);
          }}
          onClose={() => setOpen(null)}
        />
      )}
      {proposing && (
        <RuleChangeModal
          entityId={entityId}
          rule={proposing.rule}
          eventTypes={eventTypes}
          onClose={() => setProposing(null)}
        />
      )}
    </Stack>
  );
}
