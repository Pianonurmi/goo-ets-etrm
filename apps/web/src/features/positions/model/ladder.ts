/** The expiry ladder (month → MWh, keys `YYYY-MM-DD` of the month start) as chart rows in month order. */
export function ladderSeries(ladder: Record<string, number>): { month: string; mwh: number }[] {
  return Object.entries(ladder)
    .sort(([a], [b]) => a.localeCompare(b))
    .map(([month, mwh]) => ({ month: month.slice(0, 7), mwh }));
}
