import { SegmentedControl, Stack, Text } from '@mantine/core';
import { useTranslation } from 'react-i18next';
import { asTier, TIERS, type HeadroomTier } from './model/futureSupply';

/** Which supply the figures count: what is held, plus purchases agreed but not yet delivered, plus expected issuance. */
export function TierSwitch({ value, onChange }: { value: HeadroomTier; onChange: (tier: HeadroomTier) => void }) {
  const { t } = useTranslation();
  return (
    <Stack gap={6}>
      <SegmentedControl
        value={value}
        onChange={(next) => onChange(asTier(next))}
        data={TIERS.map((tier) => ({ value: tier, label: t(`positions.tier.${tier}`) }))}
        aria-label={t('positions.tier.label')}
        w="fit-content"
        data-testid="headroom-tier"
      />
      {value !== 'HELD' && (
        <Text size="xs" c="dimmed" data-testid="headroom-tier-hint">
          {t(`positions.tier.hint.${value}`)}
        </Text>
      )}
    </Stack>
  );
}
