import { Grid, Paper, SimpleGrid, Stack, Text } from '@mantine/core';
import type { ReactNode } from 'react';

export interface Fact {
  label: string;
  value: ReactNode;
  testId?: string;
}

/** Key facts of a record as a compact grid of label and value. */
export function FactGrid({ facts, cols = 4 }: { facts: Fact[]; cols?: number }) {
  return (
    <SimpleGrid cols={{ base: 1, xs: 2, md: cols }} spacing="lg" verticalSpacing="md">
      {facts.map((fact) => (
        <Stack key={fact.label} gap={2} miw={0}>
          <Text size="xs" c="dimmed" fw={500}>
            {fact.label}
          </Text>
          <Text size="sm" className="etrm-figure" truncate="end" data-testid={fact.testId} component="div">
            {fact.value}
          </Text>
        </Stack>
      ))}
    </SimpleGrid>
  );
}

/**
 * A record's page below its header: a summary card of key facts, the main content (usually tabs), and an optional
 * side panel for the lifecycle timeline or related tasks.
 */
export function DetailLayout({
  summary,
  children,
  aside,
}: {
  summary?: Fact[];
  children: ReactNode;
  aside?: ReactNode;
}) {
  const main = (
    <Stack gap="lg">
      {summary && summary.length > 0 && (
        <Paper withBorder radius="lg" p="lg" data-testid="detail-summary">
          <FactGrid facts={summary} />
        </Paper>
      )}
      {children}
    </Stack>
  );
  if (!aside) return main;
  return (
    <Grid gap="lg">
      <Grid.Col span={{ base: 12, lg: 8 }}>{main}</Grid.Col>
      <Grid.Col span={{ base: 12, lg: 4 }}>
        <Stack gap="lg">{aside}</Stack>
      </Grid.Col>
    </Grid>
  );
}
