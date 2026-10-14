import { allOf, oneOf, oneOfOrNull, yearOr } from './urlParams';

const SORTS = ['DUE', 'REQUESTED', 'PRIORITY'] as const;

describe('urlParams', () => {
  it('keeps a known value and falls back for anything else', () => {
    expect(oneOf('REQUESTED', SORTS, 'DUE')).toBe('REQUESTED');
    expect(oneOf('requested', SORTS, 'DUE')).toBe('DUE');
    expect(oneOf('<script>', SORTS, 'DUE')).toBe('DUE');
    expect(oneOf(null, SORTS, 'DUE')).toBe('DUE');
    expect(oneOf(undefined, SORTS, 'PRIORITY')).toBe('PRIORITY');
  });

  it('returns null when there is no default', () => {
    expect(oneOfOrNull('PRIORITY', SORTS)).toBe('PRIORITY');
    expect(oneOfOrNull('nonsense', SORTS)).toBeNull();
    expect(oneOfOrNull(null, SORTS)).toBeNull();
  });

  it('drops unknown and repeated values from a repeated parameter', () => {
    expect(allOf(['DUE', 'x', 'PRIORITY', 'DUE'], SORTS)).toEqual(['DUE', 'PRIORITY']);
    expect(allOf(['x'], SORTS)).toEqual([]);
    expect(allOf([], SORTS)).toEqual([]);
  });

  it('reads a year or falls back', () => {
    expect(yearOr('2027', 2026)).toBe(2027);
    expect(yearOr('20x7', 2026)).toBe(2026);
    expect(yearOr('99999', 2026)).toBe(2026);
    expect(yearOr('1000', 2026)).toBe(2026);
    expect(yearOr(null, 2026)).toBe(2026);
  });
});
