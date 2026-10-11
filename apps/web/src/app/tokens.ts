import type { MantineColorsTuple } from '@mantine/core';

/**
 * Design tokens of Provena (ADR 0034). Colours are checked for WCAG AA in `contrast.test.ts`; components read the
 * CSS variables (`--etrm-*`) the theme defines for both schemes rather than these constants.
 */

/** Deep teal. Shade 7 is the light-scheme accent (white text 5.9:1), shade 6 the filled colour in the dark scheme. */
export const brand: MantineColorsTuple = [
  '#E6F4F5',
  '#CDE9EC',
  '#9DD3DA',
  '#6BBDC8',
  '#3FA8B7',
  '#2093A5',
  '#127F91',
  '#0E6E7E',
  '#0B5866',
  '#08434E',
];

/** Slate neutrals: canvas, lines, muted and body text. */
export const slate: MantineColorsTuple = [
  '#F8FAFC',
  '#F1F5F9',
  '#E2E8F0',
  '#CBD5E1',
  '#94A3B8',
  '#64748B',
  '#475569',
  '#334155',
  '#1E293B',
  '#0F172A',
];

/** Dark scheme, from text (0) to the deepest background (9); body is 7, the canvas 8. */
export const night: MantineColorsTuple = [
  '#E2E8F0',
  '#CBD5E1',
  '#94A3B8',
  '#64748B',
  '#2A3954',
  '#1A2538',
  '#152036',
  '#111A2B',
  '#0B1220',
  '#070C16',
];

interface SchemeTokens {
  canvas: string;
  surface: string;
  line: string;
  ink: string;
  muted: string;
  accent: string;
  accentText: string;
  tint: string;
  gain: string;
  loss: string;
  warning: string;
  info: string;
  buy: string;
  sell: string;
  focus: string;
}

export const tokens: { light: SchemeTokens; dark: SchemeTokens } = {
  light: {
    canvas: '#F8FAFC',
    surface: '#FFFFFF',
    line: '#E2E8F0',
    ink: '#0F172A',
    muted: '#475569',
    accent: '#0E6E7E',
    accentText: '#0B5866',
    tint: '#E6F4F5',
    gain: '#15803D',
    loss: '#B91C1C',
    warning: '#B45309',
    info: '#1D4ED8',
    buy: '#1D4ED8',
    sell: '#C2410C',
    focus: '#0E6E7E',
  },
  dark: {
    canvas: '#0B1220',
    surface: '#111A2B',
    line: '#22304A',
    ink: '#E2E8F0',
    muted: '#94A3B8',
    accent: '#127F91',
    accentText: '#3BB6C9',
    tint: 'rgba(59, 182, 201, 0.14)',
    gain: '#4ADE80',
    loss: '#F87171',
    warning: '#FBBF24',
    info: '#60A5FA',
    buy: '#60A5FA',
    sell: '#FB923C',
    focus: '#3BB6C9',
  },
};

/** The CSS variable of a token, e.g. `token('gain')` → `var(--etrm-gain)`. */
export function token(name: keyof SchemeTokens): string {
  return `var(--etrm-${kebab(name)})`;
}

export function cssVariables(scheme: SchemeTokens): Record<string, string> {
  return Object.fromEntries(Object.entries(scheme).map(([name, value]) => [`--etrm-${kebab(name)}`, value]));
}

function kebab(name: string): string {
  return name.replace(/[A-Z]/g, (letter) => `-${letter.toLowerCase()}`);
}

/** Status tones: one colour per meaning for every lifecycle (design board, "Status badges"). */
export type Tone = 'neutral' | 'waiting' | 'progress' | 'done' | 'problem';

export const TONE_COLOR: Record<Tone, string> = {
  neutral: 'gray',
  waiting: 'yellow',
  progress: 'blue',
  done: 'green',
  problem: 'red',
};
