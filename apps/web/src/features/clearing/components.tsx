import { Group, Text, TextInput } from '@mantine/core';
import { useTranslation } from 'react-i18next';
import { Money, Quantity } from '@/shared/components/Figures';
import { formatDecimal } from '@/shared/format/format';
import { figureKind, type DifferenceKind } from './model/clearing';

/** A price as the clearing member quotes it. */
export function Price({ value }: { value: number | null | undefined }) {
  return (
    <Text component="span" className="etrm-figure">
      {value == null ? '—' : formatDecimal(value, 2, 4)}
    </Text>
  );
}

/** One side of a difference, formatted by what the difference is about (position, margin or settlement price). */
export function DifferenceFigure({
  kind,
  value,
  currency,
}: {
  kind: DifferenceKind;
  value: number | null | undefined;
  currency?: string;
}) {
  if (value == null) return <>—</>;
  switch (figureKind(kind)) {
    case 'money':
      return <Money value={value} currency={currency} />;
    case 'quantity':
      return <Quantity value={value} />;
    case 'price':
      return <Price value={value} />;
    default:
      return <>—</>;
  }
}

/** The plain-language name of a kind of difference. */
export function useDifferenceLabel() {
  const { t } = useTranslation();
  return (kind: DifferenceKind) => t(`clearing.differenceKinds.${kind}`);
}

/** From and to dates (inclusive) of a list, kept in the URL by the caller. */
export function RangeFilter({
  from,
  to,
  onChange,
  invalid,
}: {
  from: string;
  to: string;
  onChange: (patch: { from?: string; to?: string }) => void;
  invalid: boolean;
}) {
  const { t } = useTranslation();
  return (
    <Group gap="sm" align="flex-end">
      <TextInput
        type="date"
        label={t('clearing.from')}
        value={from}
        max={to}
        error={invalid ? t('clearing.rangeInvalid') : undefined}
        onChange={(e) => e.currentTarget.value && onChange({ from: e.currentTarget.value })}
        w={170}
        data-testid="clearing-from"
      />
      <TextInput
        type="date"
        label={t('clearing.to')}
        value={to}
        min={from}
        onChange={(e) => e.currentTarget.value && onChange({ to: e.currentTarget.value })}
        w={170}
        data-testid="clearing-to"
      />
    </Group>
  );
}
