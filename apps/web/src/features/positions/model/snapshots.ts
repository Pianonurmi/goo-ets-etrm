import type { Schema } from '@/shared/api/types';

export type PositionSnapshot = Schema<'PositionSnapshotView'>;
export type SnapshotKind = PositionSnapshot['kind'];

export interface SnapshotFigure {
  /** i18n key of the label. */
  label: string;
  value: number | null;
  unit: string;
}

/** A number in the stored report, read by path; null when absent or not a number. */
function numberAt(data: Record<string, unknown>, path: readonly string[]): number | null {
  let current: unknown = data;
  for (const key of path) {
    if (current === null || typeof current !== 'object') return null;
    current = (current as Record<string, unknown>)[key];
  }
  if (typeof current === 'number') return current;
  if (typeof current === 'string' && current.trim() !== '' && Number.isFinite(Number(current))) return Number(current);
  return null;
}

const GOO: { label: string; path: string[] }[] = [
  { label: 'positions.liveTotal', path: ['totalLiveMwh'] },
  { label: 'positions.openObligations', path: ['totalOpenObligationMwh'] },
  { label: 'allocation.covered', path: ['coverage', 'totalCoveredMwh'] },
  { label: 'positions.shortfall', path: ['coverage', 'totalShortfallMwh'] },
];

const ETS: { label: string; path: string[] }[] = [
  { label: 'positions.netPosition', path: ['netPosition'] },
  { label: 'positions.heldAllowances', path: ['heldAllowances'] },
  { label: 'positions.emissions', path: ['emissionsBasis'] },
  { label: 'positions.surrendered', path: ['surrendered'] },
];

/**
 * The headline figures of a stored snapshot, as the position screens show them live: the GoO report's live and
 * obligated MWh with coverage, or the ETS ladder's net position. The first figure is the one charted over time.
 */
export function snapshotFigures(snapshot: Pick<PositionSnapshot, 'kind' | 'data'>): SnapshotFigure[] {
  const unit = snapshot.kind === 'GOO' ? 'MWh' : 't';
  return (snapshot.kind === 'GOO' ? GOO : ETS).map(({ label, path }) => ({
    label,
    value: numberAt(snapshot.data, path),
    unit,
  }));
}

/** Snapshots oldest first with their headline figure, for the chart; the latest snapshot of a date wins. */
export function snapshotSeries(snapshots: readonly PositionSnapshot[]): { date: string; value: number }[] {
  const byDate = new Map<string, PositionSnapshot>();
  for (const snapshot of snapshots) {
    const known = byDate.get(snapshot.asOfDate);
    if (!known || known.takenAt < snapshot.takenAt) byDate.set(snapshot.asOfDate, snapshot);
  }
  return [...byDate.values()]
    .sort((a, b) => a.asOfDate.localeCompare(b.asOfDate))
    .flatMap((snapshot) => {
      const value = snapshotFigures(snapshot)[0]?.value;
      return value == null ? [] : [{ date: snapshot.asOfDate, value }];
    });
}
