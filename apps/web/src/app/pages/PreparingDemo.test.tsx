import { MantineProvider } from '@mantine/core';
import { render, screen } from '@testing-library/react';
import { PreparingDemo } from './PreparingDemo';

describe('PreparingDemo', () => {
  it('shows which step of the demo data is being created', () => {
    render(
      <MantineProvider>
        <PreparingDemo
          config={{
            authMode: 'SHOWCASE',
            environment: 'DEMO',
            version: 'dev',
            demo: true,
            demoSeed: { state: 'RUNNING', step: 'Trades', completedSteps: 6, stepCount: 10 },
          }}
        />
      </MantineProvider>,
    );
    expect(screen.getByText('Preparing the demo data')).toBeInTheDocument();
    expect(screen.getByText('Step 6 of 10 · Trades')).toBeInTheDocument();
  });
});
