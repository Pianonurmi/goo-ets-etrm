/**
 * A random key for idempotent commands. `crypto.randomUUID` exists only in secure contexts, and the showcase
 * runs on plain HTTP until it has a domain; `getRandomValues` works in both.
 */
export function newKey(prefix: string): string {
  const bytes = crypto.getRandomValues(new Uint8Array(16));
  return `${prefix}-${Array.from(bytes, (byte) => byte.toString(16).padStart(2, '0')).join('')}`;
}
