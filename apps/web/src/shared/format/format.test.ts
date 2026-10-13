import { formatDate, formatDecimal, formatMoney, formatPercent, formatQuantity } from './format';

describe('format', () => {
  it('formats decimals from numbers and strings without arithmetic', () => {
    expect(formatDecimal('1234.5', 2, 2)).toBe('1,234.50');
    expect(formatDecimal(0.125, 0, 2)).toBe('0.13');
    expect(formatDecimal(null)).toBe('');
    expect(formatDecimal('not a number')).toBe('');
  });

  it('adds currency and unit', () => {
    expect(formatMoney(1950, 'EUR')).toBe('1,950.00 EUR');
    expect(formatMoney(undefined, 'EUR')).toBe('');
    expect(formatQuantity(600, 'MWH')).toBe('600 MWH');
  });

  it('shows a fraction as a percentage', () => {
    expect(formatPercent(0.95)).toBe('95 %');
    expect(formatPercent('1.0625', 2)).toBe('106.25 %');
    expect(formatPercent(null)).toBe('');
  });

  it('formats dates', () => {
    expect(formatDate('2026-12-15')).toBe('2026-12-15');
    expect(formatDate(null)).toBe('');
  });
});
