import { Text, type TextProps } from '@mantine/core';
import { token } from '@/app/tokens';
import { formatMoney, formatQuantity } from '@/shared/format/format';

type Numeric = number | string | null | undefined;

/** An amount with its currency, tabular, coloured by sign when `signed` (gain green, loss red). */
export function Money({
  value,
  currency,
  signed,
  ...props
}: { value: Numeric; currency?: string | null; signed?: boolean } & TextProps) {
  const number = Number(value);
  const color = signed && Number.isFinite(number) && number !== 0 ? token(number > 0 ? 'gain' : 'loss') : undefined;
  return (
    <Text component="span" className="etrm-figure" style={color ? { color } : undefined} {...props}>
      {formatMoney(value, currency)}
    </Text>
  );
}

export function Quantity({ value, unit, ...props }: { value: Numeric; unit?: string | null } & TextProps) {
  return (
    <Text component="span" className="etrm-figure" {...props}>
      {formatQuantity(value, unit)}
    </Text>
  );
}

/** BUY in blue, SELL in orange, as everywhere in the product. */
export function Side({ side }: { side: string }) {
  return (
    <Text component="span" fw={600} size="xs" style={{ color: token(side === 'BUY' ? 'buy' : 'sell') }}>
      {side}
    </Text>
  );
}
