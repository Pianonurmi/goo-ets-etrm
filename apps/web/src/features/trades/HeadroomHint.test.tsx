import { MantineProvider } from '@mantine/core';
import { render, screen } from '@testing-library/react';
import { HeadroomHint, JointHeadroomNote } from './HeadroomHint';
import type { DemandHeadroom } from './model/headroom';

const demand = (overrides: Partial<DemandHeadroom>): DemandHeadroom => ({
  ref: '0-0',
  requestedMwh: 1000,
  headroomMwh: 1500,
  freeMwh: 1500,
  fits: true,
  binding: [],
  ...overrides,
});

function show(node: React.ReactNode) {
  return render(<MantineProvider>{node}</MantineProvider>);
}

describe('HeadroomHint', () => {
  it('shows the headroom when the delivery fits', () => {
    show(<HeadroomHint demand={demand({})} testId="hint" />);
    expect(screen.getByTestId('hint')).toHaveTextContent('Available to sell: 1,500 MWh');
    expect(screen.getByTestId('hint')).toHaveAttribute('data-fits', 'true');
  });

  it('warns with the supply that runs out when it does not fit', () => {
    show(
      <HeadroomHint
        demand={demand({
          fits: false,
          headroomMwh: 400,
          binding: [{ bucketCode: 'FI_HYDRO', productionYear: 2025, eligibleMwh: 400 }],
        })}
        testId="hint"
      />,
    );
    const hint = screen.getByTestId('hint');
    expect(hint).toHaveTextContent('Available to sell: 400 MWh (asking 1,000 MWh)');
    expect(hint).toHaveTextContent('Runs out: FI_HYDRO 2025 (400 MWh).');
    expect(hint).toHaveAttribute('data-fits', 'false');
  });

  it('shows nothing without an answer', () => {
    show(<HeadroomHint demand={undefined} testId="hint" />);
    expect(screen.queryByTestId('hint')).toBeNull();
  });
});

describe('JointHeadroomNote', () => {
  it('says the deliveries compete for the same certificates', () => {
    show(<JointHeadroomNote requestedMwh={2000} jointMwh={1700} testId="joint" />);
    expect(screen.getByTestId('joint')).toHaveTextContent('together they ask for 2,000 MWh');
    expect(screen.getByTestId('joint')).toHaveTextContent('can serve 1,700 MWh');
  });
});
