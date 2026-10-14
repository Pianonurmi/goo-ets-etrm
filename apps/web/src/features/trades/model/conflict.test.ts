import en from '@/locales/en.json';
import { tradeFieldLabel } from './conflict';

function lookup(key: string, options?: Record<string, unknown>): string {
  const text = key
    .split('.')
    .reduce<unknown>((node, part) => (node as Record<string, unknown> | undefined)?.[part], en);
  if (typeof text !== 'string') throw new Error(`missing key ${key}`);
  return text.replace(/\{\{(\w+)\}\}/g, (_, name: string) =>
    String((options?.[name] as string | number | undefined) ?? ''),
  );
}

describe('tradeFieldLabel', () => {
  it("uses the form's own wording and numbers legs and deliveries from one", () => {
    expect(tradeFieldLabel('tradeDate', lookup)).toBe('Trade date');
    expect(tradeFieldLabel('legs.0.quantity', lookup)).toBe('Leg 1 · Quantity');
    expect(tradeFieldLabel('legs.1.deliveries.2.deliveryDate', lookup)).toBe('Leg 2 · Deliveries #3 · Delivery date');
  });

  it('names a whole list and spells out fields it has no wording for', () => {
    expect(tradeFieldLabel('legs', lookup)).toBe('Leg');
    expect(tradeFieldLabel('somethingNew', lookup)).toBe('Something new');
  });

  it('only refers to keys the catalogue has', () => {
    const paths = ['bookId', 'productFamily', 'criterion', 'optionType', 'start', 'endExclusive', 'mirrorBookId'];
    paths.forEach((path) => expect(() => tradeFieldLabel(path, lookup)).not.toThrow());
  });
});
