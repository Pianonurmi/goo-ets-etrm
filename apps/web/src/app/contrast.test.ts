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

describe('Provena tokens (ADR 0034)', () => {
  it('keeps every text colour at WCAG AA on the canvas and surface of its scheme', async () => {
    const { tokens } = await import('./tokens');
    for (const scheme of [tokens.light, tokens.dark]) {
      for (const text of [
        scheme.ink,
        scheme.muted,
        scheme.accentText,
        scheme.gain,
        scheme.loss,
        scheme.warning,
        scheme.info,
        scheme.buy,
        scheme.sell,
      ]) {
        expect(contrast(text, scheme.canvas)).toBeGreaterThanOrEqual(4.5);
        expect(contrast(text, scheme.surface)).toBeGreaterThanOrEqual(4.5);
      }
    }
  });

  it('carries white text on the filled accent in both schemes', async () => {
    const { tokens } = await import('./tokens');
    expect(contrast('#FFFFFF', tokens.light.accent)).toBeGreaterThanOrEqual(4.5);
    expect(contrast('#FFFFFF', tokens.dark.accent)).toBeGreaterThanOrEqual(4.5);
  });
});
