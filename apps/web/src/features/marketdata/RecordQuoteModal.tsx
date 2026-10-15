import { Button, Group, Modal, Select, Stack, TextInput } from '@mantine/core';
import dayjs from 'dayjs';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { $api } from '@/shared/api/client';
import { decimal } from '@/shared/api/types';
import { notifySuccess } from '@/shared/components/notify';
import { ProblemAlert } from '@/shared/components/ProblemAlert';
import { useRefreshMarketData } from './hooks';
import { QUOTE_TYPES, type Instrument, type QuoteType } from './shared';

export function RecordQuoteModal({
  opened,
  onClose,
  instruments,
}: {
  opened: boolean;
  onClose: () => void;
  instruments: Instrument[];
}) {
  const { t } = useTranslation();
  const refresh = useRefreshMarketData();
  const [values, setValues] = useState({
    instrumentCode: '',
    quoteDate: dayjs().format('YYYY-MM-DD'),
    quoteType: 'CLOSE' as QuoteType,
    value: '',
  });
  const record = $api.useMutation('put', '/api/v1/market-data/quotes', {
    meta: { notify: false },
    onSuccess: (quote) => {
      notifySuccess(quote.quality === 'OK' ? t('marketData.quoteRecorded') : t('marketData.quoteSuspect'));
      void refresh();
      onClose();
    },
  });
  return (
    <Modal opened={opened} onClose={onClose} title={t('marketData.recordQuote')}>
      <Stack>
        <ProblemAlert error={record.error} />
        <Select
          label={t('marketData.instrument')}
          data={instruments.map((i) => i.code)}
          searchable
          value={values.instrumentCode || null}
          onChange={(code) => setValues({ ...values, instrumentCode: code ?? '' })}
        />
        <Group grow>
          <TextInput
            type="date"
            label={t('marketData.date')}
            value={values.quoteDate}
            onChange={(e) => setValues({ ...values, quoteDate: e.currentTarget.value })}
          />
          <Select
            label={t('marketData.quoteType')}
            data={QUOTE_TYPES}
            value={values.quoteType}
            allowDeselect={false}
            onChange={(type) => setValues({ ...values, quoteType: type ?? 'CLOSE' })}
          />
          <TextInput
            label={t('marketData.value')}
            inputMode="decimal"
            value={values.value}
            onChange={(e) => setValues({ ...values, value: e.currentTarget.value })}
          />
        </Group>
        <Button
          disabled={!values.instrumentCode || !values.value.trim()}
          loading={record.isPending}
          onClick={() => record.mutate({ body: { ...values, value: decimal(values.value), source: 'MANUAL' } })}
        >
          {t('marketData.recordQuote')}
        </Button>
      </Stack>
    </Modal>
  );
}
