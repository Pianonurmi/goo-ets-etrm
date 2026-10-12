import { MantineProvider } from '@mantine/core';
import { render, screen, within } from '@testing-library/react';
import type { Schema } from '@/shared/api/types';
import { VirtualLinesPanel } from './VirtualLinesPanel';

type Line = Schema<'VirtualLineView'>;
type Coverage = Schema<'CoverageReport'>;

const breakdown = {
  opportunityCost: 1,
  transferCost: 0,
  scarcity: 0,
  urgencyBonus: 0,
  total: 1.01,
  unit: 'EUR_PER_MWH',
};

const line = (overrides: Partial<Line>): Line => ({
  id: 'v-1',
  lineNo: 1,
  obligationId: 'o-1',
  kind: 'CONTRACTED',
  ref: 'purchase:s1',
  description: 'Forward purchase T-7',
  quantityMwh: 300,
  availableFrom: '2026-12-15',
  score: 1.01,
  breakdown: { ...breakdown, reliabilityPremium: 0.01 },
  status: 'PLANNED',
  ...overrides,
});

const coverage = {
  totalDemandMwh: 500,
  totalCoveredMwh: 100,
  totalShortfallMwh: 400,
  obligations: [
    {
      obligationId: 'o-1',
      demandMwh: 500,
      coveredMwh: 100,
      shortfallMwh: 400,
      eligibleClassCount: 1,
      eligibleSupplyMwh: 100,
      coveredWithContractedMwh: 400,
      coveredWithExpectedMwh: 500,
    },
  ],
} as unknown as Coverage;

function renderPanel(lines: Line[], cover: Coverage | undefined = coverage) {
  return render(
    <MantineProvider>
      <VirtualLinesPanel lines={lines} coverage={cover} obligations={new Map()} testId="virtual" />
    </MantineProvider>,
  );
}

describe('VirtualLinesPanel', () => {
  it('says the lines are not reserved and shows each line with its status', () => {
    renderPanel([
      line({}),
      line({
        id: 'v-2',
        lineNo: 2,
        kind: 'EXPECTED',
        ref: 'issuance:643002406000000017:2026-11',
        description: 'Koski hydro, production 2026-11',
        quantityMwh: 200,
        status: 'CHANGED',
      }),
    ]);
    expect(screen.getByTestId('virtual-note').textContent).toMatch(/not reserved or instructed/);
    expect(screen.getByText('Forward purchase T-7')).toBeTruthy();
    expect(screen.getByText('Koski hydro, production 2026-11')).toBeTruthy();
    expect(screen.getAllByTestId('virtual-line-kind').map((kind) => kind.textContent)).toEqual([
      'Purchase',
      'Expected issuance',
    ]);
    expect(screen.getAllByTestId('virtual-line-status').map((badge) => badge.textContent)).toEqual([
      'Planned',
      'Changed',
    ]);
  });

  it('adds the purchase and expected-issuance columns of the coverage table when they are reported', () => {
    renderPanel([line({})]);
    const table = within(screen.getByTestId('virtual-coverage'));
    expect(table.getByText('With purchases')).toBeTruthy();
    expect(table.getByText('With expected issuance')).toBeTruthy();
  });

  it('leaves the coverage table out when no obligation reports future cover', () => {
    renderPanel([line({})], {
      ...coverage,
      obligations: [{ ...coverage.obligations[0], coveredWithContractedMwh: null, coveredWithExpectedMwh: null }],
    } as unknown as Coverage);
    expect(screen.queryByTestId('virtual-coverage')).toBeNull();
  });
});
