import { createTheme, type CSSVariablesResolver } from '@mantine/core';
import { readable } from './contrast';
import { brand, cssVariables, night, slate, tokens } from './tokens';

const SANS = '"Geist Variable", Geist, system-ui, -apple-system, "Segoe UI", Roboto, sans-serif';
const MONO = '"Geist Mono Variable", "Geist Mono", ui-monospace, SFMono-Regular, Menlo, monospace';

/** Provena (ADR 0034): calm and dense, one accent, slate neutrals, Geist with tabular figures. */
export const theme = createTheme({
  colors: { brand, gray: slate, dark: night },
  primaryColor: 'brand',
  // Light: #0E6E7E carries white text at 5.9:1. Dark: #127F91 at 4.7:1.
  primaryShade: { light: 7, dark: 6 },
  black: tokens.light.ink,
  white: '#FFFFFF',
  fontFamily: SANS,
  fontFamilyMonospace: MONO,
  defaultRadius: 'md',
  radius: { xs: '4px', sm: '6px', md: '8px', lg: '12px', xl: '16px' },
  headings: {
    fontFamily: SANS,
    fontWeight: '600',
    sizes: {
      h1: { fontSize: '1.75rem', lineHeight: '1.25' },
      h2: { fontSize: '1.5rem', lineHeight: '1.3' },
      h3: { fontSize: '1.125rem', lineHeight: '1.4' },
      h4: { fontSize: '1rem', lineHeight: '1.45' },
    },
  },
  shadows: {
    xs: '0 1px 2px rgba(15, 23, 42, 0.05)',
    sm: '0 1px 3px rgba(15, 23, 42, 0.08), 0 1px 2px rgba(15, 23, 42, 0.04)',
    md: '0 4px 12px rgba(15, 23, 42, 0.08)',
    lg: '0 12px 32px rgba(15, 23, 42, 0.12)',
    xl: '0 24px 48px rgba(15, 23, 42, 0.16)',
  },
  focusRing: 'auto',
  cursorType: 'pointer',
  respectReducedMotion: true,
  components: {
    Paper: { defaultProps: { radius: 'lg' } },
    Card: { defaultProps: { radius: 'lg', withBorder: true } },
    Badge: { defaultProps: { radius: 'xl' } },
    Tooltip: { defaultProps: { openDelay: 300 } },
    Modal: { defaultProps: { radius: 'lg', overlayProps: { backgroundOpacity: 0.45, blur: 2 } } },
    Drawer: { defaultProps: { overlayProps: { backgroundOpacity: 0.35, blur: 1 } } },
  },
});

const SURFACES = ['#ffffff', tokens.light.canvas];

/**
 * Light-scheme colours that meet WCAG AA (4.5:1) for normal text: dimmed text is slate 600, light variants sit on
 * the palest shade with text darkened as needed (orange, yellow, lime and green fall short by default), and filled
 * and text colours are readable on white. The dark scheme uses its own tokens. Both define the `--etrm-*` tokens.
 */
export const cssVariablesResolver: CSSVariablesResolver = (current) => {
  const light: Record<string, string> = {
    ...cssVariables(tokens.light),
    '--mantine-color-dimmed': tokens.light.muted,
    '--mantine-color-default-border': tokens.light.line,
    '--mantine-color-body': tokens.light.surface,
    '--mantine-color-anchor': tokens.light.accentText,
  };
  for (const [name, shades] of Object.entries(current.colors)) {
    if (name === 'dark' || name === 'gray') continue;
    const lightBackground = shades[0];
    const strong = readable(shades[9], [...SURFACES, lightBackground, shades[1]]);
    light[`--mantine-color-${name}-light`] = lightBackground;
    light[`--mantine-color-${name}-light-hover`] = shades[1];
    light[`--mantine-color-${name}-light-color`] = strong;
    light[`--mantine-color-${name}-text`] = strong;
    light[`--mantine-color-${name}-filled`] = readable(shades[name === 'brand' ? 7 : 9], ['#ffffff']);
    light[`--mantine-color-${name}-filled-hover`] = readable(shades[name === 'brand' ? 8 : 9], ['#ffffff'], 5.5);
  }
  light['--mantine-color-gray-light-color'] = tokens.light.muted;
  light['--mantine-color-gray-text'] = tokens.light.muted;
  const dark: Record<string, string> = {
    ...cssVariables(tokens.dark),
    '--mantine-color-dimmed': tokens.dark.muted,
    '--mantine-color-default-border': tokens.dark.line,
    '--mantine-color-body': tokens.dark.surface,
    '--mantine-color-text': tokens.dark.ink,
    '--mantine-color-anchor': tokens.dark.accentText,
    '--mantine-color-brand-text': tokens.dark.accentText,
    '--mantine-color-brand-light-color': tokens.dark.accentText,
    '--mantine-color-brand-light': tokens.dark.tint,
  };
  return { variables: {}, light, dark };
};
