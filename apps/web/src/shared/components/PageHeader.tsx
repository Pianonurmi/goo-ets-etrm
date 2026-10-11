import { Anchor, Breadcrumbs, Group, Stack, Text, Title } from '@mantine/core';
import type { ReactNode } from 'react';
import { Link } from 'react-router';

export interface Crumb {
  label: string;
  to?: string;
}

/**
 * The top of every screen (ADR 0034): optional breadcrumbs, the title with a status badge, a one-line description,
 * and the actions (one primary at most) on the right.
 */
export function PageHeader({
  title,
  description,
  actions,
  breadcrumbs,
  badge,
  eyebrow,
}: {
  title: ReactNode;
  description?: ReactNode;
  actions?: ReactNode;
  breadcrumbs?: Crumb[];
  badge?: ReactNode;
  /** A short line above the title, e.g. today's date on the home page. */
  eyebrow?: ReactNode;
}) {
  return (
    <Stack gap={6} mb="lg">
      {breadcrumbs && breadcrumbs.length > 0 && (
        <Breadcrumbs separatorMargin={6} fz="sm">
          {breadcrumbs.map((crumb) =>
            crumb.to ? (
              <Anchor key={crumb.label} component={Link} to={crumb.to} size="sm">
                {crumb.label}
              </Anchor>
            ) : (
              <Text key={crumb.label} size="sm" c="dimmed">
                {crumb.label}
              </Text>
            ),
          )}
        </Breadcrumbs>
      )}
      <Group justify="space-between" align="flex-end" wrap="wrap" gap="md">
        <Stack gap={4} miw={0}>
          {eyebrow && (
            <Text size="sm" c="dimmed">
              {eyebrow}
            </Text>
          )}
          <Group gap="sm" wrap="nowrap" align="center">
            <Title order={2} className="etrm-page-title">
              {title}
            </Title>
            {badge}
          </Group>
          {description && (
            <Text c="dimmed" size="sm" maw={760}>
              {description}
            </Text>
          )}
        </Stack>
        {actions && (
          <Group gap="xs" wrap="wrap">
            {actions}
          </Group>
        )}
      </Group>
    </Stack>
  );
}
