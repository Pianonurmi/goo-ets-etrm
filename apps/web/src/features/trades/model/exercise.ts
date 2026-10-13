const PREFIX = 'exercise:';

/** The option trade number in the external reference of a future created by exercise (`exercise:<number>`), if any. */
export function exercisedOptionNumber(externalRef: string | null | undefined): string | null {
  if (!externalRef?.startsWith(PREFIX)) return null;
  const number = externalRef.slice(PREFIX.length).trim();
  return number === '' ? null : number;
}
