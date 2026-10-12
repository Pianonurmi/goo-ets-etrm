import type { Schema } from '@/shared/api/types';
import { containment, containmentText } from './containment';

type BucketNode = Schema<'BucketNode'>;

const node = (code: string, parents: string[], aliases: string[] = []): BucketNode => ({
  code,
  name: code,
  aliases,
  parents,
  depth: 0,
  sortOrder: 0,
});

const nodes = [
  node('ANY_GOO', []),
  node('EU_HYDRO', ['ANY_GOO']),
  node('NORDIC_RENEWABLE', ['ANY_GOO']),
  node('NORDIC_HYDRO', ['EU_HYDRO', 'NORDIC_RENEWABLE'], ['NORDIC_HYDRO_TWIN']),
];
const wording = { topLevel: 'Top level', sameAs: (codes: string) => `same as ${codes}` };

describe('containment', () => {
  it('lists every parent of a bucket with several', () => {
    expect(containment('NORDIC_HYDRO', nodes).parents).toEqual(['EU_HYDRO', 'NORDIC_RENEWABLE']);
  });

  it('reads an alias through the node it is the same as', () => {
    const twin = containment('NORDIC_HYDRO_TWIN', nodes);
    expect(twin).toEqual({ known: true, parents: ['EU_HYDRO', 'NORDIC_RENEWABLE'], sameAs: ['NORDIC_HYDRO'] });
  });

  it('does not know a bucket outside the hierarchy', () => {
    expect(containment('RETIRED', nodes).known).toBe(false);
    expect(containmentText(containment('RETIRED', nodes), wording)).toBe('');
  });

  it('words the cell: parents, top level, and same as', () => {
    expect(containmentText(containment('EU_HYDRO', nodes), wording)).toBe('ANY_GOO');
    expect(containmentText(containment('ANY_GOO', nodes), wording)).toBe('Top level');
    expect(containmentText(containment('NORDIC_HYDRO', nodes), wording)).toBe(
      'EU_HYDRO, NORDIC_RENEWABLE · same as NORDIC_HYDRO_TWIN',
    );
    expect(containmentText(containment('NORDIC_HYDRO_TWIN', nodes), wording)).toBe(
      'EU_HYDRO, NORDIC_RENEWABLE · same as NORDIC_HYDRO',
    );
  });
});
