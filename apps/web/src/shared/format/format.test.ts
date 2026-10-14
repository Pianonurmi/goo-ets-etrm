import { afterEach } from 'vitest';
import {
  displayLocale,
  formatDate,
  formatDateTime,
  formatDecimal,
  formatMoney,
  formatPercent,
  formatQuantity,
  setDisplayTimeZone,
} from './format';

function browserLanguage(language: string | undefined) {
  Object.defineProperty(window.navigator, 'language', { configurable: true, get: () => language });
}

afterEach(() => {
  browserLanguage('en-GB');
  setDisplayTimeZone(undefined);
});

describe('format', () => {
  it('formats decimals from numbers and strings without arithmetic', () => {
    expect(formatDecimal('1234.5', 2, 2)).toBe('1,234.50');
    expect(formatDecimal(0.125, 0, 2)).toBe('0.13');
    expect(formatDecimal(null)).toBe('');
    expect(formatDecimal('not a number')).toBe('');
    expect(formatDecimal(Number.NaN)).toBe('');
  });

  it('formats decimal strings exactly, beyond what a double holds', () => {
    expect(formatDecimal('12345678901234567.25', 2, 2)).toBe('12,345,678,901,234,567.25');
    expect(formatDecimal('0.1234567890123456789', 0, 19)).toBe('0.1234567890123456789');
    expect(formatMoney('9007199254740993.10', 'EUR')).toBe('9,007,199,254,740,993.10 EUR');
    expect(formatDecimal('-1234.5678', 0, 2)).toBe('-1,234.57');
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

  it('formats calendar dates without shifting the day', () => {
    expect(formatDate('2026-12-15')).toBe('15/12/2026');
    expect(formatDate('2026-01-01')).toBe('01/01/2026');
    expect(formatDate(null)).toBe('');
    expect(formatDate('garbage')).toBe('');
  });

  it("uses the browser's locale and falls back to en-GB", () => {
    browserLanguage('fi-FI');
    expect(displayLocale()).toBe('fi-FI');
    expect(formatDecimal('1234.5', 2, 2).replace(/\s/g, ' ')).toBe('1 234,50');
    expect(formatDate('2026-12-15')).toBe('15.12.2026');
    expect(formatPercent(0.95).replace(/\s/g, ' ')).toBe('95 %');
    browserLanguage('de-DE');
    expect(formatDecimal('1234.5', 2, 2)).toBe('1.234,50');
    browserLanguage('not a locale');
    expect(displayLocale()).toBe('en-GB');
    browserLanguage(undefined);
    expect(displayLocale()).toBe('en-GB');
    expect(formatDecimal('1234.5', 2, 2)).toBe('1,234.50');
  });

  it("shows instants in the entity's time zone with a zone suffix", () => {
    setDisplayTimeZone('Europe/Helsinki');
    expect(formatDateTime('2026-07-01T10:30:00Z')).toMatch(/^01\/07\/2026, 13:30 (EEST|GMT\+3)$/);
    expect(formatDateTime('2026-01-15T10:30:00Z')).toMatch(/^15\/01\/2026, 12:30 (EET|GMT\+2)$/);
    setDisplayTimeZone('Europe/Stockholm');
    expect(formatDateTime('2026-07-01T22:30:00Z')).toMatch(/^02\/07\/2026, 00:30 (CEST|GMT\+2)$/);
    expect(formatDateTime('2026-07-01T10:30:00Z', 'UTC')).toMatch(/^01\/07\/2026, 10:30 (UTC|GMT)$/);
    expect(formatDateTime(null)).toBe('');
    expect(formatDateTime('garbage')).toBe('');
  });

  it('ignores an unknown time zone', () => {
    setDisplayTimeZone('Mars/Olympus');
    expect(formatDateTime('2026-07-01T10:30:00Z')).not.toBe('');
  });
});
