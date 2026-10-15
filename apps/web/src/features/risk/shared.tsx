import { Badge, Group, Progress, Text, Tooltip } from '@mantine/core';
import { useTranslation } from 'react-i18next';
import { type Schema } from '@/shared/api/types';
import { StatusBadge } from '@/shared/components/StatusBadge';
import { formatDecimal } from '@/shared/format/format';
import { utilisationBar, utilisationColor, type Limit } from './model/limits';

export type Utilisation = Schema<'LimitUtilisationView'>;

export type Exposure = Schema<'CounterpartyExposureView'>;

export type Breach = Schema<'LimitBreachView'>;

export type CreditSupport = Schema<'CreditSupportView'>;

export type CreditSupportKind = CreditSupport['kind'];

export function LimitStatusBadge({ status, testId }: { status: Limit['status']; testId?: string }) {
  return <StatusBadge status={status} data-testid={testId} />;
}

/** A limit that could not be measured counts as a breach; a measurement that left something out says what. */
export function MeasurementFlags({
  value,
}: {
  value: Pick<Utilisation, 'status' | 'incomplete' | 'leftOutCurrencies' | 'incompleteReasons'>;
}) {
  const { t } = useTranslation();
  return (
    <>
      {value.status === 'NOT_MEASURED' && (
        <StatusBadge
          status="NOT_MEASURED"
          label={t('risk.statusNotMeasured')}
          size="xs"
          data-testid="limit-not-measured"
        />
      )}
      {value.incomplete && (
        <Tooltip label={t('risk.incompleteHint', { reasons: value.incompleteReasons.join('; ') })} multiline maw={320}>
          <Badge color="orange" variant="light" size="xs" tt="none" data-testid="measurement-incomplete">
            {t('risk.incomplete', { currencies: value.leftOutCurrencies.join(', ') })}
          </Badge>
        </Tooltip>
      )}
    </>
  );
}

export function UtilisationCell({ value }: { value: Utilisation | undefined }) {
  const { t } = useTranslation();
  if (!value) {
    return (
      <Text size="xs" c="dimmed">
        {t('risk.notMeasured')}
      </Text>
    );
  }
  return (
    <Group gap="xs" wrap="nowrap">
      <Progress value={utilisationBar(value.utilisation)} color={utilisationColor(value.status)} w={90} />
      <Text size="xs" c={utilisationColor(value.status)}>
        {formatDecimal(value.utilisation, 0, 1)} %
      </Text>
      <MeasurementFlags value={value} />
    </Group>
  );
}
