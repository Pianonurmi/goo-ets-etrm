import { useUrlState } from '@/shared/useUrlState';
import {
  Alert,
  Anchor,
  Button,
  Drawer,
  FileButton,
  Group,
  Modal,
  Paper,
  Stack,
  Table,
  Text,
  Textarea,
} from '@mantine/core';
import { IconFileUpload } from '@tabler/icons-react';
import { useMutation } from '@tanstack/react-query';
import type { ColDef, ICellRendererParams } from 'ag-grid-community';
import dayjs from 'dayjs';
import { useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link } from 'react-router';
import { $api } from '@/shared/api/client';
import { uploadText } from '@/shared/api/upload';
import { Can } from '@/shared/auth/Can';
import { FactGrid } from '@/shared/components/DetailLayout';
import { EmptyState } from '@/shared/components/EmptyState';
import { Money, Quantity } from '@/shared/components/Figures';
import { ProblemAlert } from '@/shared/components/ProblemAlert';
import { QueryState } from '@/shared/components/QueryState';
import { ScrollTable } from '@/shared/components/ScrollTable';
import { TableSkeleton } from '@/shared/components/Skeletons';
import { StatusBadge } from '@/shared/components/StatusBadge';
import { formatDate, formatDateTime } from '@/shared/format/format';
import { DataGrid } from '@/shared/grid/DataGrid';
import { Price, RangeFilter } from './components';
import { useInvalidateClearing } from './hooks';
import {
  importSummary,
  newestFirst,
  rangeIsValid,
  readRange,
  type Account,
  type Statement,
  type StatementImport,
} from './model/clearing';

/** A clearing member's statement file (one or more days): new versions are stored and each day reconciled. */
function ImportStatementModal({ account, onClose }: { account: Account; onClose: () => void }) {
  const { t } = useTranslation();
  const invalidate = useInvalidateClearing();
  const [text, setText] = useState('');
  const [source, setSource] = useState('statement.csv');
  const [result, setResult] = useState<StatementImport | null>(null);
  const upload = useMutation({
    mutationFn: () =>
      uploadText<StatementImport>(`/api/v1/clearing/accounts/${account.id}/statements`, { source }, text),
    meta: { notify: false },
    onSuccess: (answer) => {
      setResult(answer);
      void invalidate();
    },
  });
  const summary = result ? importSummary(result) : null;
  return (
    <Modal opened onClose={onClose} title={t('clearing.importStatement')} size="lg" data-testid="clearing-import-modal">
      <Stack>
        <ProblemAlert error={upload.error} />
        <Text size="sm" c="dimmed">
          {t('clearing.importHint', { account: account.code })}
        </Text>
        <Text size="xs" ff="monospace" c="dimmed">
          {t('clearing.importLayout')}
        </Text>
        <Textarea
          label={t('clearing.importContent')}
          value={text}
          onChange={(e) => {
            setText(e.currentTarget.value);
            setResult(null);
          }}
          autosize
          minRows={6}
          maxRows={14}
          styles={{ input: { fontFamily: 'monospace' } }}
          data-testid="clearing-import-csv"
        />
        <Group justify="space-between">
          <Group gap="xs">
            <FileButton
              accept=".csv,text/csv,text/plain"
              onChange={(file) => {
                if (!file) return;
                setSource(file.name);
                setResult(null);
                void file.text().then(setText);
              }}
            >
              {(props) => (
                <Button variant="default" {...props} data-testid="clearing-import-file">
                  {t('clearing.chooseFile')}
                </Button>
              )}
            </FileButton>
            <Text size="sm" c="dimmed" data-testid="clearing-import-source">
              {source}
            </Text>
          </Group>
          <Button
            leftSection={<IconFileUpload size={16} />}
            disabled={!text.trim()}
            loading={upload.isPending}
            onClick={() => upload.mutate()}
            data-testid="clearing-import-submit"
          >
            {t('clearing.import')}
          </Button>
        </Group>
        {result && summary && (
          <Stack gap="xs" data-testid="clearing-import-result">
            <Alert color={summary.breaks > 0 ? 'orange' : 'teal'}>
              {t('clearing.importResult', {
                imported: summary.imported,
                unchanged: summary.unchanged,
                breaks: summary.breaks,
              })}
            </Alert>
            {summary.firstDate && (
              <Text size="sm" c="dimmed">
                {summary.firstDate === summary.lastDate
                  ? t('clearing.importDay', { date: summary.firstDate })
                  : t('clearing.importDays', { from: summary.firstDate, to: summary.lastDate })}
              </Text>
            )}
            {result.breaks.length > 0 && (
              <Table>
                <Table.Tbody>
                  {result.breaks.map((brk) => (
                    <Table.Tr key={brk.id}>
                      <Table.Td>
                        <Anchor component={Link} to={`/clearing/breaks/${brk.id}`} size="sm">
                          {formatDate(brk.businessDate)}
                        </Anchor>
                      </Table.Td>
                      <Table.Td>{t('clearing.breakDifferences', { count: brk.differences.length })}</Table.Td>
                      <Table.Td>
                        <StatusBadge status={brk.status} />
                      </Table.Td>
                    </Table.Tr>
                  ))}
                </Table.Tbody>
              </Table>
            )}
          </Stack>
        )}
      </Stack>
    </Modal>
  );
}

/** One statement of the member: totals of the day and the lines per contract, with the break it opened if any. */
function StatementDrawer({
  statementId,
  entityId,
  onClose,
}: {
  statementId: string;
  entityId: string;
  onClose: () => void;
}) {
  const { t } = useTranslation();
  const detail = $api.useQuery('get', '/api/v1/clearing/statements/{statementId}', {
    params: { path: { statementId } },
  });
  const breaks = $api.useQuery('get', '/api/v1/clearing/breaks', { params: { query: { legalEntityId: entityId } } });
  const statement = detail.data;
  const brk = (breaks.data ?? []).find((candidate) => candidate.statementId === statementId);
  return (
    <Drawer
      opened
      onClose={onClose}
      position="right"
      size="xl"
      title={
        statement
          ? t('clearing.statementTitle', { date: formatDate(statement.businessDate), version: statement.version })
          : ''
      }
      data-testid="clearing-statement-drawer"
    >
      <QueryState isPending={detail.isPending} error={detail.error}>
        {statement && (
          <Stack>
            <Text size="sm" c="dimmed">
              {t('clearing.statementHint')}
            </Text>
            {statement.supersededAt && (
              <Alert color="gray" data-testid="clearing-statement-superseded">
                {t('clearing.statementSuperseded', { at: formatDateTime(statement.supersededAt) })}
              </Alert>
            )}
            <FactGrid
              cols={3}
              facts={[
                { label: t('clearing.businessDate'), value: formatDate(statement.businessDate) },
                { label: t('clearing.version'), value: statement.version },
                { label: t('clearing.fileName'), value: statement.fileName ?? '—' },
                {
                  label: t('clearing.variationMargin'),
                  value: <Money value={statement.variationMargin} currency={statement.currency} signed />,
                },
                {
                  label: t('clearing.initialMargin'),
                  value: <Money value={statement.initialMargin} currency={statement.currency} />,
                },
                {
                  label: t('clearing.fees'),
                  value: <Money value={statement.fees} currency={statement.currency} />,
                },
                {
                  label: t('clearing.cashAtMember'),
                  value:
                    statement.cashBalance == null ? (
                      '—'
                    ) : (
                      <Money value={statement.cashBalance} currency={statement.currency} />
                    ),
                },
                { label: t('clearing.importedBy'), value: statement.importedBy },
                { label: t('clearing.importedAt'), value: formatDateTime(statement.importedAt) },
                {
                  label: t('clearing.break'),
                  value: brk ? (
                    <Anchor
                      component={Link}
                      to={`/clearing/breaks/${brk.id}`}
                      size="sm"
                      data-testid="clearing-statement-break"
                    >
                      <StatusBadge status={brk.status} />
                    </Anchor>
                  ) : (
                    t('clearing.noBreak')
                  ),
                },
              ]}
            />
            <ScrollTable label={t('clearing.statementLines')} minWidth={560}>
              <Table data-testid="clearing-statement-lines" verticalSpacing="xs">
                <Table.Thead>
                  <Table.Tr>
                    <Table.Th>{t('clearing.contract')}</Table.Th>
                    <Table.Th ta="right">{t('clearing.position')}</Table.Th>
                    <Table.Th ta="right">{t('clearing.settlementPrice')}</Table.Th>
                    <Table.Th ta="right">{t('clearing.variationMargin')}</Table.Th>
                    <Table.Th ta="right">{t('clearing.fees')}</Table.Th>
                  </Table.Tr>
                </Table.Thead>
                <Table.Tbody>
                  {statement.lines.map((line) => (
                    <Table.Tr key={line.contractCode}>
                      <Table.Td>{line.contractCode}</Table.Td>
                      <Table.Td ta="right">
                        <Quantity value={line.position} />
                      </Table.Td>
                      <Table.Td ta="right">
                        <Price value={line.settlementPrice} />
                      </Table.Td>
                      <Table.Td ta="right">
                        <Money value={line.variationMargin} currency={statement.currency} signed />
                      </Table.Td>
                      <Table.Td ta="right">
                        <Money value={line.fees} currency={statement.currency} />
                      </Table.Td>
                    </Table.Tr>
                  ))}
                </Table.Tbody>
              </Table>
            </ScrollTable>
          </Stack>
        )}
      </QueryState>
    </Drawer>
  );
}

/** The member's statements of the account, the current version of each day, newest first. */
export function StatementsTab({ account, entityId }: { account: Account | null; entityId: string }) {
  const { t } = useTranslation();
  const { params, set } = useUrlState();
  const [importing, setImporting] = useState(false);
  const today = dayjs().format('YYYY-MM-DD');
  const range = readRange(params.get('from'), params.get('to'), today);
  const valid = rangeIsValid(range);
  const statementId = params.get('statement');
  const statements = $api.useQuery(
    'get',
    '/api/v1/clearing/accounts/{id}/statements',
    { params: { path: { id: account?.id ?? '' }, query: range } },
    { enabled: Boolean(account) && valid },
  );
  const rows = useMemo(() => newestFirst(statements.data ?? []), [statements.data]);
  const columns = useMemo<ColDef<Statement>[]>(
    () => [
      { field: 'businessDate', headerName: t('clearing.businessDate'), width: 140, sort: 'desc' },
      { field: 'version', headerName: t('clearing.version'), width: 100, type: 'rightAligned' },
      {
        field: 'variationMargin',
        headerName: t('clearing.variationMargin'),
        width: 170,
        type: 'rightAligned',
        cellRenderer: (p: ICellRendererParams<Statement>) =>
          p.data && <Money value={p.data.variationMargin} currency={p.data.currency} signed />,
      },
      {
        field: 'initialMargin',
        headerName: t('clearing.initialMargin'),
        width: 170,
        type: 'rightAligned',
        cellRenderer: (p: ICellRendererParams<Statement>) =>
          p.data && <Money value={p.data.initialMargin} currency={p.data.currency} />,
      },
      {
        field: 'fees',
        headerName: t('clearing.fees'),
        width: 130,
        type: 'rightAligned',
        cellRenderer: (p: ICellRendererParams<Statement>) =>
          p.data && <Money value={p.data.fees} currency={p.data.currency} />,
      },
      {
        field: 'cashBalance',
        headerName: t('clearing.cashAtMember'),
        width: 170,
        type: 'rightAligned',
        cellRenderer: (p: ICellRendererParams<Statement>) =>
          p.data && p.data.cashBalance != null ? <Money value={p.data.cashBalance} currency={p.data.currency} /> : '—',
      },
      { field: 'fileName', headerName: t('clearing.fileName'), flex: 1, minWidth: 160 },
      { field: 'importedBy', headerName: t('clearing.importedBy'), width: 140 },
      {
        field: 'importedAt',
        headerName: t('clearing.importedAt'),
        width: 160,
        valueFormatter: (p) => formatDateTime(p.value as string),
      },
    ],
    [t],
  );
  if (!account) {
    return (
      <Paper withBorder radius="lg">
        <EmptyState title={t('clearing.chooseAccount')} description={t('clearing.chooseAccountHint')} />
      </Paper>
    );
  }
  return (
    <Stack gap="sm">
      <Group justify="space-between" align="flex-end">
        <RangeFilter from={range.from} to={range.to} invalid={!valid} onChange={(patch) => set(patch)} />
        <Can op="clearing_importStatements">
          <Button
            leftSection={<IconFileUpload size={16} />}
            onClick={() => setImporting(true)}
            data-testid="clearing-import-statement"
          >
            {t('clearing.importStatement')}
          </Button>
        </Can>
      </Group>
      <Text size="sm" c="dimmed" maw={760}>
        {t('clearing.statementsHint')}
      </Text>
      <QueryState isPending={false} error={statements.error}>
        {statements.isPending && valid ? (
          <Paper withBorder radius="lg">
            <TableSkeleton rows={6} />
          </Paper>
        ) : rows.length === 0 ? (
          <Paper withBorder radius="lg">
            <EmptyState
              title={t('clearing.noStatements')}
              description={t('clearing.noStatementsHint')}
              testId="clearing-statements-empty"
            />
          </Paper>
        ) : (
          <div data-testid="clearing-statements-grid">
            <DataGrid<Statement>
              rowData={rows}
              columnDefs={columns}
              rowId={(row) => row.id}
              height={Math.min(520, 46 + rows.length * 40 + 2)}
              onRowClicked={(event) => event.data && set({ statement: event.data.id })}
              rowStyle={{ cursor: 'pointer' }}
            />
          </div>
        )}
      </QueryState>
      {importing && <ImportStatementModal account={account} onClose={() => setImporting(false)} />}
      {statementId && (
        <StatementDrawer statementId={statementId} entityId={entityId} onClose={() => set({ statement: null })} />
      )}
    </Stack>
  );
}
