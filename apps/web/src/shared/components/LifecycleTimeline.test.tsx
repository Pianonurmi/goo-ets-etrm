import { render, screen } from '@testing-library/react';
import { MantineProvider } from '@mantine/core';
import { LifecycleTimeline, reachedIndex, type LifecycleStep } from './LifecycleTimeline';

const steps: LifecycleStep[] = [
  { label: 'Captured', state: 'done', at: '2026-10-01T09:00:00Z', by: 'trader' },
  { label: 'Approved', state: 'current', by: 'middle' },
  { label: 'Delivered', state: 'pending' },
];

describe('LifecycleTimeline', () => {
  it('marks the last step reached', () => {
    expect(reachedIndex(steps)).toBe(1);
    expect(reachedIndex([{ label: 'x', state: 'pending' }])).toBe(-1);
  });

  it('lists every step with who did it', () => {
    render(
      <MantineProvider>
        <LifecycleTimeline title="Lifecycle" steps={steps} testId="lifecycle" />
      </MantineProvider>,
    );
    expect(screen.getByTestId('lifecycle')).toHaveTextContent('Captured');
    expect(screen.getByText('Delivered')).toBeInTheDocument();
    expect(screen.getByText('middle')).toBeInTheDocument();
  });
});
