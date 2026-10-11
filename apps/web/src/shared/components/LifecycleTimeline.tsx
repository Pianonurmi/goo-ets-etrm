import { Paper, Text, Timeline, Title } from '@mantine/core';
import { IconCheck, IconCircleDashed, IconPoint, IconX } from '@tabler/icons-react';
import type { ReactNode } from 'react';
import { formatDateTime } from '@/shared/format/format';

export type StepState = 'done' | 'current' | 'pending' | 'failed';

export interface LifecycleStep {
  label: string;
  state: StepState;
  /** When the step happened (ISO instant); pending steps have none. */
  at?: string | null;
  by?: string | null;
  detail?: ReactNode;
}

const BULLET: Record<StepState, ReactNode> = {
  done: <IconCheck size={12} />,
  current: <IconPoint size={14} />,
  pending: <IconCircleDashed size={12} />,
  failed: <IconX size={12} />,
};

/** Index of the last step reached, for the timeline's active line. */
export function reachedIndex(steps: LifecycleStep[]): number {
  return steps.reduce((last, step, index) => (step.state === 'pending' ? last : index), -1);
}

/**
 * A record's lifecycle as a vertical timeline (trade, instruction, invoice, plan): what happened, when and by whom,
 * and what is still to come. Build the steps with a pure function in the feature's model, so they can be tested.
 */
export function LifecycleTimeline({
  title,
  steps,
  testId,
}: {
  title: string;
  steps: LifecycleStep[];
  testId?: string;
}) {
  return (
    <Paper withBorder p="md" data-testid={testId}>
      <Title order={6} mb="sm">
        {title}
      </Title>
      <Timeline active={reachedIndex(steps)} bulletSize={20} lineWidth={2}>
        {steps.map((step, index) => (
          <Timeline.Item
            key={index}
            bullet={BULLET[step.state]}
            color={step.state === 'failed' ? 'red' : undefined}
            title={
              <Text
                size="sm"
                fw={step.state === 'current' ? 600 : 500}
                c={step.state === 'pending' ? 'dimmed' : undefined}
              >
                {step.label}
              </Text>
            }
          >
            {(step.at || step.by) && (
              <Text size="xs" c="dimmed">
                {[step.at ? formatDateTime(step.at) : null, step.by].filter(Boolean).join(' · ')}
              </Text>
            )}
            {step.detail && (
              <Text size="xs" c="dimmed" component="div">
                {step.detail}
              </Text>
            )}
          </Timeline.Item>
        ))}
      </Timeline>
    </Paper>
  );
}
