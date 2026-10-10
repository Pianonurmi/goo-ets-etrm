import { describe, expect, it } from 'vitest';
import { parseGlBalances, previousPeriod } from './glBalances';

describe('GL balances', () => {
  it('reads separated lines, skips a header and reports bad lines', () => {
    const parsed = parseGlBalances(
      'account;balance;currency\n1610;110000.00;eur\n\n2200\t-5.5\n3000,1 000,EUR\n;5;EUR',
    );
    expect(parsed.lines).toEqual([
      { glAccount: '1610', balance: '110000.00', currency: 'EUR' },
      { glAccount: '2200', balance: '-5.5', currency: 'EUR' },
    ]);
    expect(parsed.errors).toEqual([5, 6]);
  });

  it('treats a first line with an amount as data', () => {
    expect(parseGlBalances('1000,1,SEK').lines).toEqual([{ glAccount: '1000', balance: '1', currency: 'SEK' }]);
  });

  it('gives the previous period', () => {
    expect(previousPeriod(new Date(Date.UTC(2026, 9, 10)))).toBe('2026-09');
    expect(previousPeriod(new Date(Date.UTC(2027, 0, 5)))).toBe('2026-12');
  });
});
