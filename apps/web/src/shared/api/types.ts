import type { components, operations } from './schema';

/** A schema of the API document by name, e.g. `Schema<'TradeView'>`. */
export type Schema<Name extends keyof components['schemas']> = components['schemas'][Name];

/** Every operation id of the API (`<controller>_<method>`, ADR 0027). */
export type OperationId = keyof operations;

export type Role = Schema<'MeView'>['roles'][number];

/**
 * Decimal input travels as a JSON string so no precision is lost on the way to a BigDecimal; the generated types
 * say `number`, so this keeps the string and tells the compiler it is fine. No arithmetic happens in the UI.
 */
export function decimal(value: string): number {
  return value.trim() as unknown as number;
}
