import { describe, expect, it } from 'vitest';
import { contrast, readable } from './contrast';

describe('contrast', () => {
  it('computes WCAG ratios', () => {
    expect(contrast('#000000', '#ffffff')).toBeCloseTo(21, 1);
    expect(contrast('#ffffff', '#ffffff')).toBeCloseTo(1, 5);
    expect(contrast('#868e96', '#ffffff')).toBeCloseTo(3.32, 1);
  });

  it('keeps readable colours and darkens the others just enough', () => {
    expect(readable('#000000', ['#ffffff'])).toBe('#000000');
    const orange = readable('#d9480f', ['#ffffff', '#fff4e6']);
    expect(orange).not.toBe('#d9480f');
    expect(contrast(orange, '#ffffff')).toBeGreaterThanOrEqual(4.5);
    expect(contrast(orange, '#fff4e6')).toBeGreaterThanOrEqual(4.5);
  });
});
