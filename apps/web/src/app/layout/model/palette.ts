export interface PaletteScreen {
  path: string;
  label: string;
  section?: string;
}

function normalise(text: string): string {
  return text
    .normalize('NFD')
    .replace(/[\u0300-\u036f]/g, '')
    .toLowerCase();
}

/**
 * Screens whose label or section contain every word of the query, labels starting with the first word first. An
 * empty query lists every screen in navigation order.
 */
export function matchScreens(screens: PaletteScreen[], query: string, limit = 8): PaletteScreen[] {
  const words = normalise(query).split(/\s+/).filter(Boolean);
  if (words.length === 0) return screens.slice(0, limit);
  const scored = screens
    .map((screen, index) => {
      const label = normalise(screen.label);
      const haystack = `${label} ${normalise(screen.section ?? '')}`;
      if (!words.every((word) => haystack.includes(word))) return null;
      const first = words[0] ?? '';
      const rank = label.startsWith(first) ? 0 : label.includes(first) ? 1 : 2;
      return { screen, rank, index };
    })
    .filter((entry): entry is { screen: PaletteScreen; rank: number; index: number } => entry !== null);
  return scored
    .sort((a, b) => a.rank - b.rank || a.index - b.index)
    .slice(0, limit)
    .map((entry) => entry.screen);
}

/** Trade numbers, external references and UTIs are searched from two characters on. */
export function searchesTrades(query: string): boolean {
  return query.trim().length >= 2;
}
