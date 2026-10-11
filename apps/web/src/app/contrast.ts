/** WCAG 2 relative luminance of a `#rrggbb` colour. */
export function luminance(hex: string): number {
  const channels = [1, 3, 5].map((i) => parseInt(hex.slice(i, i + 2), 16) / 255);
  const [r = 0, g = 0, b = 0] = channels.map((v) => (v <= 0.03928 ? v / 12.92 : ((v + 0.055) / 1.055) ** 2.4));
  return 0.2126 * r + 0.7152 * g + 0.0722 * b;
}

/** WCAG 2 contrast ratio between two `#rrggbb` colours (1 to 21). */
export function contrast(a: string, b: string): number {
  const [light, dark] = [luminance(a), luminance(b)].sort((x, y) => y - x) as [number, number];
  return (light + 0.05) / (dark + 0.05);
}

function darker(hex: string, step: number): string {
  const channel = (i: number) =>
    Math.round(parseInt(hex.slice(i, i + 2), 16) * (1 - step))
      .toString(16)
      .padStart(2, '0');
  return `#${channel(1)}${channel(3)}${channel(5)}`;
}

/** [hex] darkened just enough to reach [ratio] against every background (WCAG AA for normal text is 4.5). */
export function readable(hex: string, backgrounds: string[], ratio = 4.5): string {
  let colour = hex;
  for (let step = 0.05; backgrounds.some((bg) => contrast(colour, bg) < ratio) && step < 1; step += 0.05) {
    colour = darker(hex, step);
  }
  return colour;
}
