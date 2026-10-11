import { Group, Text } from '@mantine/core';

/** The Provena mark: three ledger lines shortening to a seal (certificates as ranges, closed by an attested origin). */
export function ProvenaMark({ size = 28, title }: { size?: number; title?: string }) {
  return (
    <svg
      width={size}
      height={size}
      viewBox="0 0 48 48"
      role={title ? 'img' : undefined}
      aria-label={title}
      aria-hidden={title ? undefined : true}
    >
      <rect width="48" height="48" rx="11" fill="var(--etrm-accent)" />
      <rect x="11" y="13" width="26" height="4.5" rx="2.25" fill="#FFFFFF" />
      <rect x="11" y="21.75" width="18" height="4.5" rx="2.25" fill="#FFFFFF" opacity="0.85" />
      <rect x="11" y="30.5" width="10" height="4.5" rx="2.25" fill="#FFFFFF" opacity="0.7" />
      <circle cx="34" cy="32.75" r="4.5" fill="#FFFFFF" />
    </svg>
  );
}

export function ProvenaLogo({ size = 28, fz = 18 }: { size?: number; fz?: number }) {
  return (
    <Group gap={10} wrap="nowrap" data-testid="brand">
      <ProvenaMark size={size} />
      <Text component="span" fz={fz} fw={600} style={{ letterSpacing: '-0.02em' }}>
        Provena
      </Text>
    </Group>
  );
}
