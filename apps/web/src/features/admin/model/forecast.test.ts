import {
  forecastProblems,
  forecastWindow,
  isFallback,
  loadFactorProblems,
  loadFactorRows,
  type LoadFactor,
} from './forecast';

describe('forecastWindow', () => {
  it('runs twelve months from the current one', () => {
    expect(forecastWindow('2026-10-12')).toEqual({ from: '2026-10', to: '2027-09' });
    expect(forecastWindow('2026-01-31')).toEqual({ from: '2026-01', to: '2026-12' });
    expect(forecastWindow('2026-12-01', 1)).toEqual({ from: '2026-12', to: '2026-12' });
  });
});

describe('forecast input', () => {
  it('marks the load-factor fallback', () => {
    expect(isFallback({ source: 'LOAD_FACTOR' })).toBe(true);
    expect(isFallback({ source: 'ENTERED' })).toBe(false);
    expect(isFallback({ source: 'IMPORTED' })).toBe(false);
  });

  it('wants whole MWh with the P90 not above the expected figure', () => {
    expect(forecastProblems('1200', '900')).toEqual([]);
    expect(forecastProblems('1200', '1300')).toEqual(['p90Mwh']);
    expect(forecastProblems('12.5', '10')).toEqual(['expectedMwh']);
    expect(forecastProblems('', '')).toEqual(['expectedMwh', 'p90Mwh']);
  });

  it('wants factors between 0 and 1 with the P90 factor not above the factor', () => {
    expect(loadFactorProblems('0.3', '0.2')).toEqual([]);
    expect(loadFactorProblems('1', '0.5')).toEqual([]);
    expect(loadFactorProblems('1.2', '0.5')).toEqual(['factor']);
    expect(loadFactorProblems('0.2', '0.3')).toEqual(['p90Factor']);
    expect(loadFactorProblems('abc', '')).toEqual(['factor', 'p90Factor']);
  });
});

describe('loadFactorRows', () => {
  const factor = (technologyCode: string, monthOfYear: number): LoadFactor => ({
    technologyCode,
    monthOfYear,
    factor: 0.3,
    p90Factor: 0.2,
  });

  it('gives each technology twelve months in calendar order', () => {
    const rows = loadFactorRows([factor('WIND_ONSHORE', 12), factor('HYDRO', 3), factor('WIND_ONSHORE', 1)]);
    expect(rows.map((row) => row.technologyCode)).toEqual(['HYDRO', 'WIND_ONSHORE']);
    expect(rows[1]?.months).toHaveLength(12);
    expect(rows[1]?.months[0]?.monthOfYear).toBe(1);
    expect(rows[1]?.months[11]?.monthOfYear).toBe(12);
    expect(rows[1]?.months[5]).toBeUndefined();
    expect(loadFactorRows([])).toEqual([]);
  });
});
