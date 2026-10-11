import { snapshotFigures, snapshotSeries, type PositionSnapshot } from './snapshots';

function snapshot(
  asOfDate: string,
  data: Record<string, unknown>,
  kind: PositionSnapshot['kind'] = 'GOO',
  takenAt = `${asOfDate}T21:50:00Z`,
): PositionSnapshot {
  return { id: `${kind}-${asOfDate}-${takenAt}`, legalEntityId: 'e', kind, asOfDate, takenAt, data };
}

describe('snapshotFigures', () => {
  it('reads the GoO report figures', () => {
    const figures = snapshotFigures(
      snapshot('2026-09-30', {
        totalLiveMwh: 1200,
        totalOpenObligationMwh: '800',
        coverage: { totalCoveredMwh: 700, totalShortfallMwh: 100 },
      }),
    );
    expect(figures.map((f) => [f.label, f.value, f.unit])).toEqual([
      ['positions.liveTotal', 1200, 'MWh'],
      ['positions.openObligations', 800, 'MWh'],
      ['allocation.covered', 700, 'MWh'],
      ['positions.shortfall', 100, 'MWh'],
    ]);
  });

  it('reads the ETS ladder figures and leaves missing ones empty', () => {
    const figures = snapshotFigures(snapshot('2026-09-30', { netPosition: -250, heldAllowances: 'n/a' }, 'ETS'));
    expect(figures[0]).toEqual({ label: 'positions.netPosition', value: -250, unit: 't' });
    expect(figures[1]?.value).toBeNull();
    expect(figures[2]?.value).toBeNull();
  });
});

describe('snapshotSeries', () => {
  it('orders by date, keeps the latest snapshot of a date and skips ones without the figure', () => {
    const series = snapshotSeries([
      snapshot('2026-09-30', { totalLiveMwh: 900 }, 'GOO', '2026-09-30T10:00:00Z'),
      snapshot('2026-08-31', { totalLiveMwh: 1000 }),
      snapshot('2026-09-30', { totalLiveMwh: 950 }, 'GOO', '2026-09-30T21:50:00Z'),
      snapshot('2026-07-31', {}),
    ]);
    expect(series).toEqual([
      { date: '2026-08-31', value: 1000 },
      { date: '2026-09-30', value: 950 },
    ]);
  });
});
