import { Text } from '@mantine/core';
import { useTranslation } from 'react-i18next';
import { formatQuantity } from '@/shared/format/format';
import { bindingSupplies, type DemandHeadroom } from './model/headroom';

const mwh = (value: number) => formatQuantity(value, 'MWh');

/**
 * What one sale delivery can still get from the certificates held: neutral when it fits, a warning naming the supply
 * that runs out when it does not. Advice only; saving is never blocked.
 */
export function HeadroomHint({ demand, testId }: { demand: DemandHeadroom | undefined; testId: string }) {
  const { t } = useTranslation();
  if (!demand) return null;
  const supplies = bindingSupplies(demand)
    .map((supply) => `${supply.bucketCode} ${supply.productionYear} (${mwh(supply.eligibleMwh)})`)
    .join(', ');
  const text = demand.fits
    ? t('trades.headroom.available', { mwh: mwh(demand.headroomMwh) })
    : t('trades.headroom.short', { mwh: mwh(demand.headroomMwh), requested: mwh(demand.requestedMwh) }) +
      (supplies ? ` ${t('trades.headroom.runsOut', { supplies })}` : '');
  return (
    <Text
      size="xs"
      c={demand.fits ? 'dimmed' : 'orange.8'}
      className="etrm-figure"
      data-testid={testId}
      data-fits={demand.fits}
    >
      {text}
    </Text>
  );
}

/** Under a leg whose deliveries fit one by one but not together: they compete for the same certificates. */
export function JointHeadroomNote({
  requestedMwh,
  jointMwh,
  testId,
}: {
  requestedMwh: number;
  jointMwh: number;
  testId: string;
}) {
  const { t } = useTranslation();
  return (
    <Text size="xs" c="orange.8" data-testid={testId}>
      {t('trades.headroom.joint', { requested: mwh(requestedMwh), mwh: mwh(jointMwh) })}
    </Text>
  );
}
