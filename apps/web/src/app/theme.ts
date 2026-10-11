import { createTheme, type CSSVariablesResolver } from '@mantine/core';
import { readable } from './contrast';

export const theme = createTheme({
  primaryColor: 'teal',
  // Shade 9 keeps white text on filled buttons and teal links above WCAG AA in the light scheme.
  primaryShade: { light: 9, dark: 8 },
  defaultRadius: 'sm',
  fontFamily: 'Inter, system-ui, -apple-system, "Segoe UI", Roboto, sans-serif',
  headings: { fontWeight: '600' },
});

const SURFACES = ['#ffffff', '#f8f9fa'];

/**
 * Light-scheme colours that meet WCAG AA (4.5:1) for normal text: dimmed text one shade darker, light variants on
 * the palest shade with text darkened as needed (orange, yellow, lime and green fall short by default), and filled
 * and text colours readable on white.
 */
export const cssVariablesResolver: CSSVariablesResolver = (current) => {
  const light: Record<string, string> = { '--mantine-color-dimmed': current.colors.gray[7] };
  for (const [name, shades] of Object.entries(current.colors)) {
    if (name === 'dark' || name === 'gray') continue;
    const lightBackground = shades[0];
    const strong = readable(shades[9], [...SURFACES, lightBackground, shades[1]]);
    light[`--mantine-color-${name}-light`] = lightBackground;
    light[`--mantine-color-${name}-light-hover`] = shades[1];
    light[`--mantine-color-${name}-light-color`] = strong;
    light[`--mantine-color-${name}-text`] = strong;
    light[`--mantine-color-${name}-filled`] = readable(shades[9], ['#ffffff']);
    light[`--mantine-color-${name}-filled-hover`] = readable(shades[9], ['#ffffff'], 5.5);
  }
  return { variables: {}, light, dark: {} };
};
