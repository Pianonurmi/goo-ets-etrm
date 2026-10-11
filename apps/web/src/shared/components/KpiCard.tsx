import { Group, Paper, Skeleton, Stack, Text, UnstyledButton } from '@mantine/core';
import type { ReactNode } from 'react';
import { Link } from 'react-router';
import { token } from '@/app/tokens';

export type Trend = 'up' | 'down' | 'warning' | 'neutral';

const TREND_COLOR: Record<Trend, string> = {
  up: token('gain'),
  down: token('loss'),
  warning: token('warning'),
  neutral: token('muted'),
};

/** A tiny line of the recent values (no axes); decorative, the figure carries the meaning. */
export function Sparkline({ values, width = 96, height = 28 }: { values: number[]; width?: number; height?: number }) {
  if (values.length < 2) return null;
  const min = Math.min(...values);
  const span = Math.max(...values) - min || 1;
  const step = width / (values.length - 1);
  const points = values.map(
    (value, index) => `${(index * step).toFixed(1)},${(height - 2 - ((value - min) / span) * (height - 4)).toFixed(1)}`,
  );
  return (
    <svg width={width} height={height} viewBox={`0 0 ${width} ${height}`} aria-hidden="true">
      <polyline
        points={points.join(' ')}
        fill="none"
        stroke={token('accent')}
        strokeWidth={2}
        strokeLinejoin="round"
        strokeLinecap="round"
      />
    </svg>
  );
}

/** A headline figure: label, value with unit, a trend line and an optional sparkline; links to its screen. */
export function KpiCard({
  label,
  value,
  unit,
  trend,
  trendTone = 'neutral',
  spark,
  to,
  loading,
  testId,
}: {
  label: string;
  value: ReactNode;
  unit?: string;
  trend?: ReactNode;
  trendTone?: Trend;
  spark?: number[];
  to?: string;
  loading?: boolean;
  testId?: string;
}) {
  const body = (
    <Paper withBorder radius="lg" p="lg" h="100%" className={to ? 'etrm-card-link' : undefined} data-testid={testId}>
      <Stack gap={8}>
        <Text size="sm" c="dimmed">
          {label}
        </Text>
        {loading ? (
          <Skeleton height={30} width="60%" />
        ) : (
          <Group gap={6} align="baseline" wrap="nowrap" className="etrm-figure">
            <Text component="span" fz={26} fw={600} lh={1.1} style={{ letterSpacing: '-0.02em' }}>
              {value}
            </Text>
            {unit && (
              <Text component="span" size="sm" c="dimmed">
                {unit}
              </Text>
            )}
          </Group>
        )}
        <Group justify="space-between" align="flex-end" wrap="nowrap" mih={28}>
          <Text size="xs" fw={500} style={{ color: TREND_COLOR[trendTone] }}>
            {trend}
          </Text>
          {spark && <Sparkline values={spark} />}
        </Group>
      </Stack>
    </Paper>
  );
  return to ? (
    <UnstyledButton component={Link} to={to} display="block" h="100%">
      {body}
    </UnstyledButton>
  ) : (
    body
  );
}
