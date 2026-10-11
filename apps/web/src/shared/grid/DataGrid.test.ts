import { describe, expect, it } from 'vitest';
import { fittedHeight } from './DataGrid';

describe('fittedHeight', () => {
  it('grows with the rows and leaves room for the header and a horizontal scrollbar', () => {
    expect(fittedHeight(5, 12, 40)).toBe(44 + 5 * 40 + 18);
  });

  it('keeps room for the empty-state overlay', () => {
    expect(fittedHeight(0, 12, 40)).toBe(fittedHeight(3, 12, 40));
  });

  it('stops growing at the maximum and scrolls inside', () => {
    expect(fittedHeight(500, 12, 32)).toBe(36 + 12 * 32 + 18);
  });
});
