import { fieldPathLabel, rebaseEdits, sameValue, staleChanges } from './staleVersion';

interface Legal {
  [key: string]: unknown;
  name: string;
  lei: string;
  active: boolean;
  validTo: string;
}

const loaded: Legal = { name: 'Demo Energia Oy', lei: 'ABC', active: true, validTo: '' };

describe('staleChanges', () => {
  it('lists what the server changed, against what the form was opened with', () => {
    const mine = { ...loaded, lei: 'XYZ' };
    const current = { ...loaded, name: 'Demo Energia Ab', active: false };
    expect(staleChanges(loaded, mine, current)).toEqual([
      { path: 'name', loaded: 'Demo Energia Oy', current: 'Demo Energia Ab', mine: 'Demo Energia Oy', edited: false },
      { path: 'active', loaded: true, current: false, mine: true, edited: false },
    ]);
  });

  it('marks a field the user edited as well', () => {
    const mine = { ...loaded, name: 'Mine' };
    const current = { ...loaded, name: 'Theirs' };
    expect(staleChanges(loaded, mine, current)).toEqual([
      { path: 'name', loaded: 'Demo Energia Oy', current: 'Theirs', mine: 'Mine', edited: true },
    ]);
  });

  it('is empty when only fields outside the form changed', () => {
    expect(staleChanges(loaded, { ...loaded, lei: 'XYZ' }, { ...loaded })).toEqual([]);
  });

  it('descends into lists of the same length and reports whole lists otherwise', () => {
    const base = {
      legs: [
        { quantity: '10', price: '5' },
        { quantity: '20', price: '6' },
      ],
      reason: '',
    };
    const current = {
      legs: [
        { quantity: '10', price: '5' },
        { quantity: '20', price: '7' },
      ],
      reason: '',
    };
    expect(staleChanges(base, base, current).map((c) => c.path)).toEqual(['legs.1.price']);
    const longer = { ...base, legs: [...base.legs, { quantity: '1', price: '1' }] };
    expect(staleChanges(base, base, longer).map((c) => c.path)).toEqual(['legs']);
  });
});

describe('rebaseEdits', () => {
  it("takes the server's values and keeps the user's edits on top", () => {
    const mine = { ...loaded, lei: 'XYZ' };
    const current = { ...loaded, name: 'Demo Energia Ab', validTo: '2027-01-01' };
    expect(rebaseEdits(loaded, mine, current)).toEqual({
      name: 'Demo Energia Ab',
      lei: 'XYZ',
      active: true,
      validTo: '2027-01-01',
    });
  });

  it("keeps the user's value where both changed the same field", () => {
    expect(rebaseEdits(loaded, { ...loaded, name: 'Mine' }, { ...loaded, name: 'Theirs' }).name).toBe('Mine');
  });

  it('merges edits to different legs of a trade', () => {
    const base = { legs: [{ quantity: '10' }, { quantity: '20' }], reason: '' };
    const mine = { legs: [{ quantity: '11' }, { quantity: '20' }], reason: 'fix' };
    const current = { legs: [{ quantity: '10' }, { quantity: '25' }], reason: '' };
    expect(rebaseEdits(base, mine, current)).toEqual({ legs: [{ quantity: '11' }, { quantity: '25' }], reason: 'fix' });
  });

  it("keeps the user's list when the lists no longer line up", () => {
    const base = { legs: [{ quantity: '10' }] };
    const mine = { legs: [{ quantity: '11' }] };
    const current = { legs: [{ quantity: '10' }, { quantity: '5' }] };
    expect(rebaseEdits(base, mine, current)).toEqual({ legs: [{ quantity: '11' }] });
  });

  it('returns the server version when nothing was edited, and does not mutate its inputs', () => {
    const current = { ...loaded, name: 'Theirs' };
    const copy = structuredClone({ loaded, current });
    expect(rebaseEdits(loaded, { ...loaded }, current)).toEqual(current);
    expect({ loaded, current }).toEqual(copy);
  });
});

describe('helpers', () => {
  it('compares nested values by content', () => {
    expect(sameValue({ a: [1, { b: 2 }] }, { a: [1, { b: 2 }] })).toBe(true);
    expect(sameValue({ a: [1] }, { a: [1, 2] })).toBe(false);
    expect(sameValue(null, undefined)).toBe(false);
  });

  it('labels a path', () => {
    expect(fieldPathLabel('validTo')).toBe('Valid to');
    expect(fieldPathLabel('legs.0.quantity')).toBe('Legs · #1 · Quantity');
  });
});
