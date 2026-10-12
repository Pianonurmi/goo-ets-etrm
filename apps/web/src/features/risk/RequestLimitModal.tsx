import { Button, Group, Modal, NumberInput, Select, Stack, Text, TextInput, Textarea } from '@mantine/core';
import { useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { $api } from '@/shared/api/client';
import { decimal } from '@/shared/api/types';
import { notifySuccess } from '@/shared/components/notify';
import { ProblemAlert } from '@/shared/components/ProblemAlert';
import { useBucketCodes } from '@/features/marketdata/InstrumentActions';
import { useBooks, useCounterparties } from '@/features/trades/hooks';
import { HORIZON_DAYS, LIMIT_FIELDS, LIMIT_TYPES, type Limit, type LimitType } from './model/limits';

type Family = NonNullable<Limit['scope']['productFamily']>;

function validHorizon(value: number | string): boolean {
  const days = Number(value);
  return value !== '' && Number.isInteger(days) && days >= HORIZON_DAYS.min && days <= HORIZON_DAYS.max;
}

/** The currency to send: always for money limits, only when EUR is chosen for GoO limits, never otherwise. */
function requestCurrency(kind: 'required' | 'optional' | 'hidden', currency: string, gooUnit: 'MWH' | 'EUR') {
  if (kind === 'required') return currency;
  if (kind === 'optional' && gooUnit === 'EUR') return 'EUR';
  return undefined;
}

export function useInvalidateRisk() {
  const queryClient = useQueryClient();
  return () =>
    queryClient.invalidateQueries({
      predicate: (q) =>
        typeof q.queryKey[1] === 'string' &&
        (q.queryKey[1].startsWith('/api/v1/risk') || q.queryKey[1].startsWith('/api/v1/workflow')),
    });
}

/** A new limit, or a change to an active one ([replaces]); either waits for a second risk analyst. */
export function RequestLimitModal({
  opened,
  onClose,
  entityId,
  replaces,
}: {
  opened: boolean;
  onClose: () => void;
  entityId: string;
  replaces?: Limit;
}) {
  const { t } = useTranslation();
  const counterparties = useCounterparties();
  const books = useBooks(entityId);
  const buckets = useBucketCodes();
  const invalidate = useInvalidateRisk();
  const [type, setType] = useState<LimitType>(replaces?.type ?? 'CREDIT_EXPOSURE');
  const [counterpartyId, setCounterpartyId] = useState<string | null>(replaces?.scope.counterpartyId ?? null);
  const [bookId, setBookId] = useState<string | null>(replaces?.scope.bookId ?? null);
  const [family, setFamily] = useState<Family | null>(replaces?.scope.productFamily ?? null);
  const [allowance, setAllowance] = useState(replaces?.scope.allowanceProductCode ?? 'EUA');
  const [year, setYear] = useState<number>(replaces?.scope.complianceYear ?? new Date().getFullYear());
  const [amount, setAmount] = useState(replaces ? String(replaces.amount) : '');
  const [currency, setCurrency] = useState(replaces?.currency ?? 'EUR');
  const [bucketCode, setBucketCode] = useState<string | null>(replaces?.scope.bucketCode ?? null);
  const [horizonDays, setHorizonDays] = useState<number | string>(replaces?.scope.horizonDays ?? HORIZON_DAYS.initial);
  const [gooUnit, setGooUnit] = useState<'MWH' | 'EUR'>(replaces?.currency === 'EUR' ? 'EUR' : 'MWH');
  const [warningPct, setWarningPct] = useState<number>(replaces?.warningPct ?? 80);
  const [validFrom, setValidFrom] = useState('');
  const [reason, setReason] = useState('');
  const fields = LIMIT_FIELDS[type];
  const request = $api.useMutation('post', '/api/v1/risk/limits', {
    meta: { notify: false },
    onSuccess: () => {
      notifySuccess(t('risk.limitRequested'));
      void invalidate();
      onClose();
    },
  });
  const ready =
    amount.trim() !== '' &&
    reason.trim() !== '' &&
    (fields.counterparty !== 'required' || counterpartyId !== null) &&
    (fields.productFamily !== 'required' || family !== null) &&
    (fields.bucket !== 'required' || bucketCode !== null) &&
    (fields.horizon !== 'required' || validHorizon(horizonDays));
  const submit = () =>
    request.mutate({
      body: {
        legalEntityId: entityId,
        type,
        scope: {
          counterpartyId: fields.counterparty === 'hidden' ? undefined : (counterpartyId ?? undefined),
          bookId: fields.book ? (bookId ?? undefined) : undefined,
          productFamily: fields.productFamily === 'hidden' ? undefined : (family ?? undefined),
          allowanceProductCode: type === 'COMPLIANCE_SHORT' ? allowance : undefined,
          complianceYear: type === 'COMPLIANCE_SHORT' ? year : undefined,
          bucketCode: fields.bucket === 'hidden' ? undefined : (bucketCode ?? undefined),
          horizonDays: fields.horizon === 'hidden' ? undefined : Number(horizonDays),
        },
        amount: decimal(amount),
        currency: requestCurrency(fields.currency, currency, gooUnit),
        warningPct,
        validFrom: validFrom || undefined,
        replaces: replaces?.id,
        reason,
      },
    });
  return (
    <Modal
      opened={opened}
      onClose={onClose}
      title={replaces ? t('risk.changeLimit') : t('risk.requestLimit')}
      size="lg"
    >
      <Stack>
        <ProblemAlert error={request.error} />
        <Text size="sm" c="dimmed">
          {t('risk.fourEyesHint')}
        </Text>
        <Select
          label={t('risk.limitType')}
          data={LIMIT_TYPES.map((value) => ({ value, label: t(`risk.types.${value}`) }))}
          value={type}
          allowDeselect={false}
          disabled={Boolean(replaces)}
          data-testid="limit-type"
          onChange={(value) => value && setType(value)}
          description={t(`risk.typeHints.${type}`)}
        />
        {fields.counterparty === 'required' && (
          <Select
            label={t('trades.columns.counterparty')}
            data={counterparties.list.map((c) => ({ value: c.id, label: `${c.code} · ${c.name}` }))}
            value={counterpartyId}
            onChange={setCounterpartyId}
            searchable
            required
            data-testid="limit-counterparty"
          />
        )}
        {fields.productFamily !== 'hidden' && (
          <Select
            label={t('risk.productFamily')}
            data={[
              { value: 'GUARANTEE_OF_ORIGIN', label: t('trades.families.GUARANTEE_OF_ORIGIN') },
              { value: 'EMISSION_ALLOWANCE', label: t('trades.families.EMISSION_ALLOWANCE') },
            ]}
            value={family}
            onChange={(value) => setFamily(value)}
            clearable={fields.productFamily === 'optional'}
            required={fields.productFamily === 'required'}
          />
        )}
        {fields.bucket !== 'hidden' && (
          <Select
            label={t('risk.bucket')}
            data={buckets}
            value={bucketCode}
            onChange={setBucketCode}
            searchable
            clearable={fields.bucket === 'optional'}
            required={fields.bucket === 'required'}
            placeholder={fields.bucket === 'optional' ? t('risk.allBuckets') : undefined}
            data-testid="limit-bucket"
          />
        )}
        {fields.horizon !== 'hidden' && (
          <NumberInput
            label={t('risk.horizonDays')}
            value={horizonDays}
            onChange={setHorizonDays}
            min={HORIZON_DAYS.min}
            max={HORIZON_DAYS.max}
            allowDecimal={false}
            allowNegative={false}
            required
            data-testid="limit-horizon"
          />
        )}
        {fields.book && (
          <Select
            label={t('trades.columns.book')}
            data={books.list.map((book) => ({ value: book.id, label: `${book.code} · ${book.name}` }))}
            value={bookId}
            onChange={setBookId}
            clearable
            placeholder={t('risk.allBooks')}
          />
        )}
        {type === 'COMPLIANCE_SHORT' && (
          <Group grow>
            <TextInput
              label={t('trades.form.allowanceProduct')}
              value={allowance}
              onChange={(e) => setAllowance(e.currentTarget.value)}
            />
            <NumberInput
              label={t('positions.complianceYear')}
              value={year}
              onChange={(value) => setYear(Number(value))}
              min={2005}
            />
          </Group>
        )}
        <Group grow>
          <TextInput
            label={t('risk.amount')}
            inputMode="decimal"
            value={amount}
            onChange={(e) => setAmount(e.currentTarget.value)}
            required
            data-testid="limit-amount"
          />
          {fields.currency === 'required' && (
            <TextInput
              label={t('trades.form.currency')}
              value={currency}
              onChange={(e) => setCurrency(e.currentTarget.value.toUpperCase())}
              maxLength={3}
            />
          )}
          {fields.currency === 'optional' && (
            <Select
              label={t('risk.unit')}
              data={[
                { value: 'MWH', label: t('risk.unitMwh') },
                { value: 'EUR', label: t(type === 'GOO_CONCENTRATION' ? 'risk.unitEur' : 'risk.unitEurBasis') },
              ]}
              value={gooUnit}
              allowDeselect={false}
              onChange={(value) => value && setGooUnit(value === 'EUR' ? 'EUR' : 'MWH')}
              data-testid="limit-unit"
            />
          )}
          <NumberInput
            label={t('risk.warningPct')}
            value={warningPct}
            onChange={(value) => setWarningPct(Number(value))}
            min={1}
            max={100}
            suffix=" %"
          />
        </Group>
        <TextInput
          type="date"
          label={t('risk.validFrom')}
          description={t('risk.validFromHint')}
          value={validFrom}
          onChange={(e) => setValidFrom(e.currentTarget.value)}
          w={220}
        />
        <Textarea
          label={t('risk.reason')}
          value={reason}
          onChange={(e) => setReason(e.currentTarget.value)}
          required
          autosize
          minRows={2}
          data-testid="limit-reason"
        />
        <Button disabled={!ready} loading={request.isPending} onClick={submit} data-testid="request-limit-submit">
          {t('risk.sendForApproval')}
        </Button>
      </Stack>
    </Modal>
  );
}
