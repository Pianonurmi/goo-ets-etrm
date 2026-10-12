import {
  Accordion,
  ActionIcon,
  Alert,
  Badge,
  Button,
  FileButton,
  Group,
  Modal,
  Paper,
  Stack,
  Table,
  Text,
  TextInput,
  Textarea,
  Tooltip,
  UnstyledButton,
} from '@mantine/core';
import { IconPencil, IconUpload } from '@tabler/icons-react';
import { useMutation } from '@tanstack/react-query';
import dayjs from 'dayjs';
import { useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { $api } from '@/shared/api/client';
import { uploadText } from '@/shared/api/upload';
import { decimal, type Schema } from '@/shared/api/types';
import { Can, useCan } from '@/shared/auth/Can';
import { EmptyState } from '@/shared/components/EmptyState';
import { notifySuccess } from '@/shared/components/notify';
import { ProblemAlert } from '@/shared/components/ProblemAlert';
import { QueryState } from '@/shared/components/QueryState';
import { TableSkeleton } from '@/shared/components/Skeletons';
import { formatDateTime, formatDecimal } from '@/shared/format/format';
import {
  forecastProblems,
  forecastWindow,
  isFallback,
  loadFactorProblems,
  loadFactorRows,
  type ExpectedProduction,
  type LoadFactor,
} from './model/forecast';
import { useInvalidateReference } from './PartyDetails';
import { Section } from './Section';

type ImportResult = Schema<'ForecastImportResult'>;

/** Sets the expected and P90 production of one device and month; the result is an entered forecast. */
function RecordForecastModal({
  deviceId,
  row,
  onClose,
}: {
  deviceId: string;
  row: ExpectedProduction;
  onClose: () => void;
}) {
  const { t } = useTranslation();
  const invalidate = useInvalidateReference();
  const [expected, setExpected] = useState(String(row.expectedMwh));
  const [p90, setP90] = useState(String(row.p90Mwh));
  const save = $api.useMutation('put', '/api/v1/reference/devices/{id}/forecasts/{month}', {
    meta: { notify: false },
    onSuccess: () => {
      notifySuccess(t('admin.forecast.saved', { month: row.month }));
      void invalidate();
      onClose();
    },
  });
  const problems = forecastProblems(expected, p90);
  return (
    <Modal opened onClose={onClose} title={t('admin.forecast.editTitle', { month: row.month })} size="sm">
      <Stack>
        <ProblemAlert error={save.error} />
        {isFallback(row) && (
          <Text size="sm" c="dimmed">
            {t('admin.forecast.editFallbackHint')}
          </Text>
        )}
        <TextInput
          label={t('admin.forecast.expected')}
          description={t('admin.forecast.expectedHint')}
          value={expected}
          inputMode="numeric"
          error={problems.includes('expectedMwh') ? t('admin.forecast.wholeMwh') : undefined}
          onChange={(e) => setExpected(e.currentTarget.value.replace(/\D/g, ''))}
          data-testid="forecast-expected"
        />
        <TextInput
          label={t('admin.forecast.p90')}
          description={t('admin.forecast.p90Hint')}
          value={p90}
          inputMode="numeric"
          error={problems.includes('p90Mwh') && p90 !== '' ? t('admin.forecast.p90Above') : undefined}
          onChange={(e) => setP90(e.currentTarget.value.replace(/\D/g, ''))}
          data-testid="forecast-p90"
        />
        <Button
          disabled={problems.length > 0}
          loading={save.isPending}
          onClick={() =>
            save.mutate({
              params: { path: { id: deviceId, month: row.month } },
              body: { expectedMwh: Number(expected), p90Mwh: Number(p90) },
            })
          }
          data-testid="forecast-save"
        >
          {t('common.save')}
        </Button>
      </Stack>
    </Modal>
  );
}

/** Uploads a forecast file (`gsrn,month,expected_mwh,p90_mwh`); lines that fail are skipped and listed. */
function ImportForecastsModal({ onClose }: { onClose: () => void }) {
  const { t } = useTranslation();
  const invalidate = useInvalidateReference();
  const [text, setText] = useState('');
  const [result, setResult] = useState<ImportResult | null>(null);
  const upload = useMutation({
    mutationFn: () => uploadText<ImportResult>('/api/v1/reference/forecasts/import', {}, text),
    meta: { notify: false },
    onSuccess: (done) => {
      setResult(done);
      void invalidate();
    },
  });
  return (
    <Modal opened onClose={onClose} title={t('admin.forecast.importTitle')} size="lg">
      <Stack data-testid="forecast-import">
        <ProblemAlert error={upload.error} />
        <Text size="sm" c="dimmed">
          {t('admin.forecast.importHint')}
        </Text>
        <Textarea
          label={t('admin.forecast.csv')}
          value={text}
          onChange={(e) => {
            setText(e.currentTarget.value);
            setResult(null);
          }}
          placeholder="gsrn,month,expected_mwh,p90_mwh"
          autosize
          minRows={5}
          maxRows={12}
          styles={{ input: { fontFamily: 'monospace' } }}
          data-testid="forecast-csv"
        />
        <Group justify="space-between">
          <FileButton
            accept=".csv,text/csv,text/plain"
            onChange={(file) =>
              void file?.text().then((content) => {
                setText(content);
                setResult(null);
              })
            }
          >
            {(props) => (
              <Button variant="subtle" {...props}>
                {t('settlement.chooseFile')}
              </Button>
            )}
          </FileButton>
          <Button
            leftSection={<IconUpload size={16} />}
            disabled={!text.trim()}
            loading={upload.isPending}
            onClick={() => upload.mutate()}
            data-testid="forecast-import-run"
          >
            {t('admin.forecast.import')}
          </Button>
        </Group>
        {result && (
          <Stack gap="xs" data-testid="forecast-import-result">
            <Group gap="xs">
              <Badge color="teal" variant="light" tt="none">
                {t('admin.forecast.imported', { count: result.imported })}
              </Badge>
              <Badge color={result.rejected > 0 ? 'red' : 'gray'} variant="light" tt="none">
                {t('admin.forecast.rejected', { count: result.rejected })}
              </Badge>
            </Group>
            {result.errors.length > 0 && (
              <Alert color="red" variant="light" data-testid="forecast-import-errors">
                <Stack gap={2}>
                  {result.errors.map((error) => (
                    <Text key={error} size="sm">
                      {error}
                    </Text>
                  ))}
                </Stack>
              </Alert>
            )}
          </Stack>
        )}
      </Stack>
    </Modal>
  );
}

/** The next twelve months of a device: expected and P90 production, and where each figure comes from. */
export function DeviceForecast({ deviceId, issuanceLagDays }: { deviceId: string; issuanceLagDays?: number }) {
  const { t } = useTranslation();
  const canEdit = useCan('productionForecast_recordForecast');
  const [editing, setEditing] = useState<ExpectedProduction | null>(null);
  const [importing, setImporting] = useState(false);
  const range = useMemo(() => forecastWindow(dayjs().format('YYYY-MM-DD')), []);
  const expected = $api.useQuery('get', '/api/v1/reference/devices/{id}/expected', {
    params: { path: { id: deviceId }, query: range },
  });
  // The recorded forecasts say who entered each figure and when; the expected series says what the plans count on.
  const recorded = $api.useQuery('get', '/api/v1/reference/devices/{id}/forecasts', {
    params: { path: { id: deviceId } },
  });
  const recordedByMonth = useMemo(
    () => new Map((recorded.data ?? []).map((forecast) => [forecast.month, forecast])),
    [recorded.data],
  );
  const rows = expected.data ?? [];
  return (
    <div data-testid="device-forecast">
      <Section
        title={t('admin.forecast.title')}
        action={
          <Can op="productionForecast_importForecasts">
            <Button
              size="compact-sm"
              variant="default"
              leftSection={<IconUpload size={14} />}
              onClick={() => setImporting(true)}
              data-testid="forecast-import-open"
            >
              {t('admin.forecast.importTitle')}
            </Button>
          </Can>
        }
      >
        <Stack gap="sm">
          <Text size="sm" c="dimmed">
            {t('admin.forecast.lead')}
            {issuanceLagDays != null && ` ${t('admin.forecast.lag', { days: issuanceLagDays })}`}
          </Text>
          <QueryState isPending={false} error={expected.error}>
            {expected.isPending ? (
              <TableSkeleton rows={4} />
            ) : rows.length === 0 ? (
              <EmptyState title={t('admin.forecast.empty')} description={t('admin.forecast.emptyHint')} />
            ) : (
              <Paper withBorder>
                <Table verticalSpacing="xs" data-testid="forecast-table">
                  <Table.Thead>
                    <Table.Tr>
                      <Table.Th>{t('admin.forecast.month')}</Table.Th>
                      <Table.Th ta="right">{t('admin.forecast.expected')}</Table.Th>
                      <Table.Th ta="right">{t('admin.forecast.p90')}</Table.Th>
                      <Table.Th>{t('admin.forecast.source')}</Table.Th>
                      {canEdit && <Table.Th w={40} />}
                    </Table.Tr>
                  </Table.Thead>
                  <Table.Tbody className="etrm-figure">
                    {rows.map((row) => (
                      <Table.Tr key={row.month} data-testid={`forecast-row-${row.month}`}>
                        <Table.Td>{row.month}</Table.Td>
                        <Table.Td ta="right" c={isFallback(row) ? 'dimmed' : undefined}>
                          {formatDecimal(row.expectedMwh)}
                        </Table.Td>
                        <Table.Td ta="right" c={isFallback(row) ? 'dimmed' : undefined}>
                          {formatDecimal(row.p90Mwh)}
                        </Table.Td>
                        <Table.Td>
                          {isFallback(row) ? (
                            <Tooltip label={t('admin.forecast.fallbackHint')} multiline w={260}>
                              <Badge
                                variant="outline"
                                color="gray"
                                tt="none"
                                radius="xl"
                                style={{ borderStyle: 'dashed' }}
                                data-testid="forecast-source-fallback"
                              >
                                {t('admin.forecast.sources.LOAD_FACTOR')}
                              </Badge>
                            </Tooltip>
                          ) : (
                            <Tooltip
                              label={t('admin.forecast.recordedBy', {
                                by: recordedByMonth.get(row.month)?.recordedBy,
                                at: formatDateTime(recordedByMonth.get(row.month)?.recordedAt),
                                version: recordedByMonth.get(row.month)?.version,
                              })}
                              disabled={!recordedByMonth.has(row.month)}
                            >
                              <Badge variant="light" color="brand" tt="none" radius="xl" data-testid="forecast-source">
                                {t(`admin.forecast.sources.${row.source}`)}
                              </Badge>
                            </Tooltip>
                          )}
                        </Table.Td>
                        {canEdit && (
                          <Table.Td>
                            <ActionIcon
                              variant="subtle"
                              color="gray"
                              aria-label={t('admin.forecast.edit', { month: row.month })}
                              onClick={() => setEditing(row)}
                              data-testid={`forecast-edit-${row.month}`}
                            >
                              <IconPencil size={16} />
                            </ActionIcon>
                          </Table.Td>
                        )}
                      </Table.Tr>
                    ))}
                  </Table.Tbody>
                </Table>
              </Paper>
            )}
          </QueryState>
        </Stack>
      </Section>
      {editing && <RecordForecastModal deviceId={deviceId} row={editing} onClose={() => setEditing(null)} />}
      {importing && <ImportForecastsModal onClose={() => setImporting(false)} />}
    </div>
  );
}

const MONTH_KEYS = ['01', '02', '03', '04', '05', '06', '07', '08', '09', '10', '11', '12'] as const;

/** Changes the load factor of a technology and month of the year (middle office). */
function LoadFactorModal({
  technologyCode,
  factor,
  onClose,
}: {
  technologyCode: string;
  factor: LoadFactor;
  onClose: () => void;
}) {
  const { t } = useTranslation();
  const invalidate = useInvalidateReference();
  const [value, setValue] = useState(String(factor.factor));
  const [p90, setP90] = useState(String(factor.p90Factor));
  const save = $api.useMutation('put', '/api/v1/reference/load-factors/{technologyCode}/{monthOfYear}', {
    meta: { notify: false },
    onSuccess: () => {
      notifySuccess(t('admin.loadFactors.saved'));
      void invalidate();
      onClose();
    },
  });
  const problems = loadFactorProblems(value, p90);
  return (
    <Modal
      opened
      onClose={onClose}
      title={t('admin.loadFactors.editTitle', {
        technology: technologyCode,
        month: t(`admin.loadFactors.months.${MONTH_KEYS[factor.monthOfYear - 1] ?? '01'}`),
      })}
      size="sm"
    >
      <Stack>
        <ProblemAlert error={save.error} />
        <TextInput
          label={t('admin.loadFactors.factor')}
          description={t('admin.loadFactors.factorHint')}
          value={value}
          inputMode="decimal"
          error={problems.includes('factor') ? t('admin.loadFactors.invalidFactor') : undefined}
          onChange={(e) => setValue(e.currentTarget.value)}
          data-testid="load-factor-value"
        />
        <TextInput
          label={t('admin.loadFactors.p90Factor')}
          value={p90}
          inputMode="decimal"
          error={problems.includes('p90Factor') ? t('admin.loadFactors.invalidP90') : undefined}
          onChange={(e) => setP90(e.currentTarget.value)}
          data-testid="load-factor-p90"
        />
        <Button
          disabled={problems.length > 0}
          loading={save.isPending}
          onClick={() =>
            save.mutate({
              params: { path: { technologyCode, monthOfYear: factor.monthOfYear } },
              body: { factor: decimal(value), p90Factor: decimal(p90) },
            })
          }
          data-testid="load-factor-save"
        >
          {t('common.save')}
        </Button>
      </Stack>
    </Modal>
  );
}

function LoadFactorsTable() {
  const { t } = useTranslation();
  const canEdit = useCan('productionForecast_updateLoadFactor');
  const [editing, setEditing] = useState<{ technologyCode: string; factor: LoadFactor } | null>(null);
  const factors = $api.useQuery('get', '/api/v1/reference/load-factors', {});
  const rows = useMemo(() => loadFactorRows(factors.data ?? []), [factors.data]);
  return (
    <QueryState isPending={false} error={factors.error}>
      {factors.isPending ? (
        <TableSkeleton rows={3} />
      ) : rows.length === 0 ? (
        <EmptyState title={t('admin.loadFactors.empty')} />
      ) : (
        <Table.ScrollContainer minWidth={900}>
          <Table verticalSpacing="xs" data-testid="load-factors-table">
            <Table.Thead>
              <Table.Tr>
                <Table.Th>{t('admin.technology')}</Table.Th>
                {MONTH_KEYS.map((key) => (
                  <Table.Th key={key} ta="right">
                    {t(`admin.loadFactors.months.${key}`)}
                  </Table.Th>
                ))}
              </Table.Tr>
            </Table.Thead>
            <Table.Tbody className="etrm-figure">
              {rows.map((row) => (
                <Table.Tr key={row.technologyCode}>
                  <Table.Td>{row.technologyCode}</Table.Td>
                  {row.months.map((factor, index) => {
                    const cell = factor ? (
                      <Stack gap={0} align="flex-end">
                        <Text size="sm">{formatDecimal(factor.factor, 2, 4)}</Text>
                        <Text size="xs" c="dimmed">
                          {formatDecimal(factor.p90Factor, 2, 4)}
                        </Text>
                      </Stack>
                    ) : (
                      '–'
                    );
                    return (
                      <Table.Td key={MONTH_KEYS[index]} ta="right">
                        {factor && canEdit ? (
                          <UnstyledButton
                            onClick={() => setEditing({ technologyCode: row.technologyCode, factor })}
                            aria-label={t('admin.loadFactors.edit', {
                              technology: row.technologyCode,
                              month: t(`admin.loadFactors.months.${MONTH_KEYS[index] ?? '01'}`),
                            })}
                            data-testid={`load-factor-${row.technologyCode}-${index + 1}`}
                          >
                            {cell}
                          </UnstyledButton>
                        ) : (
                          cell
                        )}
                      </Table.Td>
                    );
                  })}
                </Table.Tr>
              ))}
            </Table.Tbody>
          </Table>
        </Table.ScrollContainer>
      )}
      {editing && (
        <LoadFactorModal
          technologyCode={editing.technologyCode}
          factor={editing.factor}
          onClose={() => setEditing(null)}
        />
      )}
    </QueryState>
  );
}

/**
 * The share of capacity a technology produces in each month, which stands in for a forecast. These are illustrative
 * defaults, not measurements; each cell shows the expected factor with the P90 factor beneath it.
 */
export function LoadFactorsSection() {
  const { t } = useTranslation();
  const [open, setOpen] = useState<string | null>(null);
  return (
    <Accordion variant="separated" value={open} onChange={setOpen} data-testid="load-factors">
      <Accordion.Item value="load-factors">
        <Accordion.Control>{t('admin.loadFactors.title')}</Accordion.Control>
        <Accordion.Panel>
          <Stack>
            <Text size="sm" c="dimmed">
              {t('admin.loadFactors.lead')}
            </Text>
            {open && <LoadFactorsTable />}
          </Stack>
        </Accordion.Panel>
      </Accordion.Item>
    </Accordion>
  );
}
