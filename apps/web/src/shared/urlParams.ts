/** URL parameters arrive as free text; these narrow them to the values a screen knows (W10). */

/** The parameter when it is one of `allowed`, otherwise `fallback`. */
export function oneOf<T extends string>(value: string | null | undefined, allowed: readonly T[], fallback: T): T {
  return allowed.find((candidate) => candidate === value) ?? fallback;
}

/** The parameter when it is one of `allowed`, otherwise null. */
export function oneOfOrNull<T extends string>(value: string | null | undefined, allowed: readonly T[]): T | null {
  return allowed.find((candidate) => candidate === value) ?? null;
}

/** The repeated parameters that are in `allowed`, in the URL's order and without repeats. */
export function allOf<T extends string>(values: readonly string[], allowed: readonly T[]): T[] {
  return [...new Set(values)].filter((value): value is T => allowed.some((candidate) => candidate === value));
}

/** A four-digit year from the URL, otherwise `fallback`. */
export function yearOr(value: string | null | undefined, fallback: number): number {
  const year = value && /^\d{4}$/.test(value) ? Number(value) : Number.NaN;
  return Number.isInteger(year) && year >= 1990 && year <= 2200 ? year : fallback;
}
