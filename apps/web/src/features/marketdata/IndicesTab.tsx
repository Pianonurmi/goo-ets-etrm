import {
  Badge,
  Button,
  Card,
  Group,
  Modal,
  Select,
  SimpleGrid,
  Stack,
  Table,
  Text,
  TextInput,
  Title,
} from '@mantine/core';
import { useForm } from '@mantine/form';
import { IconPlus, IconSearch } from '@tabler/icons-react';
import dayjs from 'dayjs';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { $api } from '@/shared/api/client';
import { asProblemError } from '@/shared/api/problem';
import type { Body } from '@/shared/api/types';
import { Can } from '@/shared/auth/Can';
import { EmptyState } from '@/shared/components/EmptyState';
import { notifySuccess } from '@/shared/components/notify';
import { ProblemAlert } from '@/shared/components/ProblemAlert';
import { QueryState } from '@/shared/components/QueryState';
import { StatusBadge } from '@/shared/components/StatusBadge';
import { formatDate, formatDecimal } from '@/shared/format/format';
import { CODE_PATTERN, FIXING_RULES, QUOTE_TYPES } from './model/marketData';
import { translated, useRefreshMarketData } from './InstrumentActions';

type IndexForm = Body<'CreatePriceIndex'>;

function CreateIndexModal({ instruments, onClose }: { instruments: string[]; onClose: () => void }) {
  const { t } = useTranslation();
  const refresh = useRefreshMarketData();
  const form = useForm<IndexForm>({
    initialValues: { code: '', description: '', instrumentCode: '', quoteType: 'MID', fixingRule: 'MONTH_AVERAGE' },
    validate: (values) =>
      translated(
        {
          ...(CODE_PATTERN.test(values.code.trim()) ? {} : { code: 'marketData.codeHint' }),
          ...(values.description.trim() ? {} : { description: 'marketData.errors.required' }),
          ...(values.instrumentCode ? {} : { instrumentCode: 'marketData.errors.required' }),
        },
        t,
      ),
  });
  const create = $api.useMutation('post', '/api/v1/market-data/indices', {
    meta: { notify: false },
    onSuccess: (index) => {
      notifySuccess(t('marketData.indexCreated', { code: index.code }));
      void refresh();
      onClose();
    },
  });
  const submit = form.onSubmit((values) =>
    create.mutate({
      body: { ...values, code: values.code.trim().toUpperCase(), description: values.description.trim() },
    }),
  );
  return (
    <Modal opened onClose={onClose} title={t('marketData.newIndex')}>
      <form onSubmit={submit}>
        <Stack>
          <ProblemAlert error={create.error} />
          <TextInput
            label={t('marketData.code')}
            description={t('marketData.codeHint')}
            required
            data-testid="index-code"
            {...form.getInputProps('code')}
          />
          <TextInput
            label={t('marketData.descriptionColumn')}
            required
            data-testid="index-description"
            {...form.getInputProps('description')}
          />
          <Select
            label={t('marketData.instrument')}
            data={instruments}
            searchable
            required
            data-testid="index-instrument"
            {...form.getInputProps('instrumentCode')}
          />
          <Group grow>
            <Select
              label={t('marketData.quoteType')}
              data={QUOTE_TYPES}
              allowDeselect={false}
              data-testid="index-quote-type"
              {...form.getInputProps('quoteType')}
            />
            <Select
              label={t('marketData.fixingRule')}
              data={FIXING_RULES.map((value) => ({ value, label: t(`marketData.fixingRules.${value}`) }))}
              allowDeselect={false}
              data-testid="index-fixing-rule"
              {...form.getInputProps('fixingRule')}
            />
          </Group>
          <Group justify="flex-end">
            <Button variant="default" onClick={onClose}>
              {t('common.cancel')}
            </Button>
            <Button type="submit" loading={create.isPending} data-testid="index-submit">
              {t('marketData.newIndex')}
            </Button>
          </Group>
        </Stack>
      </form>
    </Modal>
  );
}

/** The fixing an indexed trade would get for a date: one quote or a month's average, with its source quotes. */
function FixingLookup({ indices }: { indices: string[] }) {
  const { t } = useTranslation();
  const [index, setIndex] = useState<string | null>(indices[0] ?? null);
  const [date, setDate] = useState(dayjs().subtract(1, 'day').format('YYYY-MM-DD'));
  const [lookup, setLookup] = useState<{ code: string; date: string } | null>(null);
  const fixing = $api.useQuery(
    'get',
    '/api/v1/market-data/indices/{code}/fixings/{date}',
    { params: { path: { code: lookup?.code ?? '', date: lookup?.date ?? '' } } },
    { enabled: lookup !== null, retry: false },
  );
  const missing = fixing.error && asProblemError(fixing.error).status === 404;
  return (
    <Card withBorder>
      <Title order={5} mb="sm">
        {t('marketData.fixingTitle')}
      </Title>
      <Group align="flex-end">
        <Select
          label={t('marketData.index')}
          data={indices}
          value={index}
          onChange={setIndex}
          searchable
          w={280}
          data-testid="fixing-index"
        />
        <TextInput
          type="date"
          label={t('marketData.fixingDate')}
          value={date}
          onChange={(e) => setDate(e.currentTarget.value)}
          data-testid="fixing-date"
        />
        <Button
          variant="light"
          leftSection={<IconSearch size={16} />}
          disabled={!index || !date}
          onClick={() => index && setLookup({ code: index, date })}
          data-testid="fixing-show"
        >
          {t('marketData.showFixing')}
        </Button>
      </Group>
      {lookup && (
        <Stack mt="md">
          {missing ? (
            <EmptyState
              title={t('marketData.noFixing')}
              description={t('marketData.noFixingHint')}
              testId="fixing-none"
            />
          ) : (
            <QueryState isPending={fixing.isPending} error={fixing.error}>
              {fixing.data && (
                <Stack gap={4} data-testid="fixing-result">
                  <Text size="sm" c="dimmed">
                    {t('marketData.fixingOf', {
                      index: fixing.data.indexCode,
                      date: formatDate(fixing.data.fixingDate),
                    })}
                  </Text>
                  <Text size="xl" fw={700} className="etrm-figure" data-testid="fixing-value">
                    {formatDecimal(fixing.data.value, 2, 6)} {fixing.data.currency}
                  </Text>
                  <Text size="xs" c="dimmed">
                    {t('marketData.fixingQuotes', { count: fixing.data.quoteIds.length })}
                  </Text>
                </Stack>
              )}
            </QueryState>
          )}
        </Stack>
      )}
    </Card>
  );
}

/** Price indices for indexed trades, a new index, and the fixing of an index on a date. */
export function IndicesTab() {
  const { t } = useTranslation();
  const indices = $api.useQuery('get', '/api/v1/market-data/indices', {});
  const instruments = $api.useQuery('get', '/api/v1/market-data/instruments', {});
  const [creating, setCreating] = useState(false);
  const list = indices.data ?? [];
  return (
    <Stack>
      <Group justify="flex-end">
        <Can op="marketData_createIndex">
          <Button leftSection={<IconPlus size={16} />} onClick={() => setCreating(true)} data-testid="new-index">
            {t('marketData.newIndex')}
          </Button>
        </Can>
      </Group>
      <QueryState isPending={indices.isPending} error={indices.error}>
        {list.length === 0 ? (
          <EmptyState title={t('marketData.noIndices')} description={t('marketData.noIndicesHint')} />
        ) : (
          <Table withTableBorder striped data-testid="indices-table">
            <Table.Thead>
              <Table.Tr>
                <Table.Th>{t('marketData.code')}</Table.Th>
                <Table.Th>{t('marketData.descriptionColumn')}</Table.Th>
                <Table.Th>{t('marketData.instrument')}</Table.Th>
                <Table.Th>{t('marketData.quoteType')}</Table.Th>
                <Table.Th>{t('marketData.fixingRule')}</Table.Th>
                <Table.Th>{t('marketData.active')}</Table.Th>
              </Table.Tr>
            </Table.Thead>
            <Table.Tbody>
              {list.map((index) => (
                <Table.Tr key={index.code} data-testid={`index-row-${index.code}`}>
                  <Table.Td>
                    <Text size="sm" ff="monospace">
                      {index.code}
                    </Text>
                  </Table.Td>
                  <Table.Td>{index.description}</Table.Td>
                  <Table.Td>{index.instrumentCode}</Table.Td>
                  <Table.Td>
                    <Badge variant="outline" color="gray">
                      {index.quoteType}
                    </Badge>
                  </Table.Td>
                  <Table.Td>{t(`marketData.fixingRules.${index.fixingRule}`)}</Table.Td>
                  <Table.Td>
                    <StatusBadge status={index.active ? 'ACTIVE' : 'INACTIVE'} />
                  </Table.Td>
                </Table.Tr>
              ))}
            </Table.Tbody>
          </Table>
        )}
      </QueryState>
      {list.length > 0 && (
        <SimpleGrid cols={{ base: 1, lg: 2 }}>
          <FixingLookup indices={list.filter((index) => index.active).map((index) => index.code)} />
        </SimpleGrid>
      )}
      {creating && (
        <CreateIndexModal
          instruments={(instruments.data ?? []).map((i) => i.code)}
          onClose={() => setCreating(false)}
        />
      )}
    </Stack>
  );
}
