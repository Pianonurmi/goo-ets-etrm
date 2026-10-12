import type { Schema } from '@/shared/api/types';

export type ExpectedProduction = Schema<'ExpectedProduction'>;
export type LoadFactor = Schema<'LoadFactorView'>;

/** Months shown in a device's forecast table, counting from the current one. */
export const FORECAST_MONTHS = 12;

const WHOLE = /^\d{1,12}$/;
const FACTOR = /^(0(\.\d{1,4})?|1(\.0{1,4})?|\.\d{1,4})$/;

/** The first and last month (`YYYY-MM`) of the forecast window that starts with the month of [today] (`YYYY-MM-DD`). */
export function forecastWindow(today: string, months = FORECAST_MONTHS): { from: string; to: string } {
  const year = Number(today.slice(0, 4));
  const month = Number(today.slice(5, 7)) - 1;
  const last = year * 12 + month + months - 1;
  const pad = (value: number) => String(value).padStart(2, '0');
  return { from: `${pad(year)}-${pad(month + 1)}`, to: `${Math.floor(last / 12)}-${pad((last % 12) + 1)}` };
}

/** A month without a recorded forecast shows the technology's load factor times the capacity; it is an estimate. */
export const isFallback = (row: Pick<ExpectedProduction, 'source'>): boolean => row.source === 'LOAD_FACTOR';

/** The problems of a forecast as typed: both figures whole MWh, and the P90 not above the expected figure. */
export function forecastProblems(expected: string, p90: string): ('expectedMwh' | 'p90Mwh')[] {
  const problems: ('expectedMwh' | 'p90Mwh')[] = [];
  if (!WHOLE.test(expected.trim())) problems.push('expectedMwh');
  if (!WHOLE.test(p90.trim()) || (WHOLE.test(expected.trim()) && Number(p90) > Number(expected))) {
    problems.push('p90Mwh');
  }
  return problems;
}

/** The problems of a load factor as typed: between 0 and 1, and the P90 factor not above the factor. */
export function loadFactorProblems(factor: string, p90Factor: string): ('factor' | 'p90Factor')[] {
  const problems: ('factor' | 'p90Factor')[] = [];
  if (!FACTOR.test(factor.trim())) problems.push('factor');
  if (!FACTOR.test(p90Factor.trim()) || (FACTOR.test(factor.trim()) && Number(p90Factor) > Number(factor))) {
    problems.push('p90Factor');
  }
  return problems;
}

export interface LoadFactorRow {
  technologyCode: string;
  /** Index 0 is January; a month without a factor is undefined. */
  months: (LoadFactor | undefined)[];
}

/** Load factors as one row per technology with the twelve months in calendar order. */
export function loadFactorRows(factors: readonly LoadFactor[]): LoadFactorRow[] {
  const rows = new Map<string, LoadFactorRow>();
  for (const factor of factors) {
    const row = rows.get(factor.technologyCode) ?? {
      technologyCode: factor.technologyCode,
      months: Array.from({ length: 12 }, (): LoadFactor | undefined => undefined),
    };
    if (factor.monthOfYear >= 1 && factor.monthOfYear <= 12) row.months[factor.monthOfYear - 1] = factor;
    rows.set(factor.technologyCode, row);
  }
  return [...rows.values()].sort((a, b) => a.technologyCode.localeCompare(b.technologyCode));
}
