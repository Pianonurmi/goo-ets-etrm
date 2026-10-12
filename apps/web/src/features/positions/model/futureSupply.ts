import type { Schema } from '@/shared/api/types';

export type FutureSupplyView = Schema<'FutureSupplyView'>;
export type FutureSupplyItem = Schema<'FutureSupplyItem'>;
export type FutureCoverageMonth = Schema<'FutureCoverageMonth'>;
export type HeadroomTier = Schema<'HeadroomTable'>['tier'];

export const TIERS: readonly HeadroomTier[] = ['HELD', 'CONTRACTED', 'EXPECTED'];

/** A narrowing for a SegmentedControl value, so an unknown string falls back to what is held. */
export function asTier(value: string): HeadroomTier {
  return TIERS.find((tier) => tier === value) ?? 'HELD';
}

/** One bar of the coverage chart: the demand of a delivery month split by where its cover comes from. */
export interface CoverageBar {
  /** `YYYY-MM`. */
  month: string;
  held: number;
  purchases: number;
  expected: number;
  short: number;
}

const wholeMwh = (value: number) => Math.max(0, value);

/**
 * The coverage months as stacked bars. The server states coverage cumulatively (held, then with purchases, then with
 * expected issuance); the layers are the steps between them, in whole MWh, so nothing is rounded. A step that would
 * come out negative reads as 0.
 */
export function coverageBars(months: readonly FutureCoverageMonth[]): CoverageBar[] {
  return [...months]
    .sort((a, b) => a.month.localeCompare(b.month))
    .map((row) => ({
      month: row.month.slice(0, 7),
      held: wholeMwh(row.coveredHeldMwh),
      purchases: wholeMwh(row.coveredWithContractedMwh - row.coveredHeldMwh),
      expected: wholeMwh(row.coveredWithExpectedMwh - row.coveredWithContractedMwh),
      short: wholeMwh(row.demandMwh - row.coveredWithExpectedMwh),
    }));
}

/** Items in the order they become available, contracted before expected on the same day, then by reference. */
export function sortedItems(items: readonly FutureSupplyItem[]): FutureSupplyItem[] {
  return [...items].sort(
    (a, b) =>
      a.availableFrom.localeCompare(b.availableFrom) ||
      (a.kind === b.kind ? 0 : a.kind === 'CONTRACTED' ? -1 : 1) ||
      a.ref.localeCompare(b.ref),
  );
}

/** Whether there is nothing to show: no future supply and no demand to cover. */
export function isEmptyView(view: FutureSupplyView): boolean {
  return view.items.length === 0 && view.months.length === 0;
}
