import { flattenPortfolio, isExpanded, toggleExpanded, type PortfolioNode } from './portfolio';

const node = (bucketCode: string, parents: string[], extra: Partial<PortfolioNode> = {}): PortfolioNode => ({
  bucketCode,
  name: `${bucketCode} name`,
  aliases: [],
  parents,
  depth: 0,
  longMwh: 0,
  shortMwh: 0,
  coveredWithinMwh: 0,
  shortAfterNettingMwh: 0,
  naiveNetMwh: 0,
  freeMwh: 0,
  availableToSellMwh: 0,
  ...extra,
});

// ANY_GOO > EU_RENEWABLE > { EU_HYDRO, NORDIC_RENEWABLE }, and NORDIC_HYDRO sits under both of those, with FI_HYDRO below.
const nodes: PortfolioNode[] = [
  node('ANY_GOO', []),
  node('EU_RENEWABLE', ['ANY_GOO']),
  node('EU_HYDRO', ['EU_RENEWABLE']),
  node('NORDIC_RENEWABLE', ['EU_RENEWABLE']),
  node('NORDIC_HYDRO', ['EU_HYDRO', 'NORDIC_RENEWABLE'], { longMwh: 500 }),
  node('FI_HYDRO', ['NORDIC_HYDRO']),
];

const keys = (rows: { key: string }[]) => rows.map((row) => row.key);

describe('flattenPortfolio', () => {
  it('opens the first two levels and indents by level, parents before children', () => {
    const rows = flattenPortfolio(nodes);
    expect(keys(rows)).toEqual([
      'ANY_GOO',
      'ANY_GOO>EU_RENEWABLE',
      'ANY_GOO>EU_RENEWABLE>EU_HYDRO',
      'ANY_GOO>EU_RENEWABLE>NORDIC_RENEWABLE',
    ]);
    expect(rows.map((row) => row.level)).toEqual([0, 1, 2, 2]);
    expect(rows.map((row) => [row.hasChildren, row.expanded])).toEqual([
      [true, true],
      [true, true],
      [true, false],
      [true, false],
    ]);
  });

  it('lists a node under each of its parents, with the same figures, and marks the repeats', () => {
    let state = toggleExpanded({}, 'ANY_GOO>EU_RENEWABLE>EU_HYDRO', 2);
    state = toggleExpanded(state, 'ANY_GOO>EU_RENEWABLE>NORDIC_RENEWABLE', 2);
    const rows = flattenPortfolio(nodes, state);
    const nordicHydro = rows.filter((row) => row.node.bucketCode === 'NORDIC_HYDRO');
    expect(keys(nordicHydro)).toEqual([
      'ANY_GOO>EU_RENEWABLE>EU_HYDRO>NORDIC_HYDRO',
      'ANY_GOO>EU_RENEWABLE>NORDIC_RENEWABLE>NORDIC_HYDRO',
    ]);
    expect(nordicHydro[0]?.alsoUnder).toBeUndefined();
    expect(nordicHydro[1]?.alsoUnder).toEqual({ code: 'EU_HYDRO', name: 'EU_HYDRO name' });
    expect(nordicHydro.map((row) => row.node.longMwh)).toEqual([500, 500]);
  });

  it('does not mark the children of a repeat, which sit under the same parent as the first time', () => {
    let state = toggleExpanded({}, 'ANY_GOO>EU_RENEWABLE>EU_HYDRO', 2);
    state = toggleExpanded(state, 'ANY_GOO>EU_RENEWABLE>NORDIC_RENEWABLE', 2);
    state = toggleExpanded(state, 'ANY_GOO>EU_RENEWABLE>EU_HYDRO>NORDIC_HYDRO', 3);
    state = toggleExpanded(state, 'ANY_GOO>EU_RENEWABLE>NORDIC_RENEWABLE>NORDIC_HYDRO', 3);
    const finland = flattenPortfolio(nodes, state).filter((row) => row.node.bucketCode === 'FI_HYDRO');
    expect(finland).toHaveLength(2);
    expect(finland.every((row) => row.alsoUnder === undefined)).toBe(true);
    expect(finland.map((row) => row.level)).toEqual([4, 4]);
  });

  it('marks a repeat even when the first listing is hidden in a closed branch', () => {
    // EU_HYDRO stays closed, so NORDIC_HYDRO shows only under NORDIC_RENEWABLE, and is still the second place.
    const state = toggleExpanded({}, 'ANY_GOO>EU_RENEWABLE>NORDIC_RENEWABLE', 2);
    const rows = flattenPortfolio(nodes, state);
    const shown = rows.filter((row) => row.node.bucketCode === 'NORDIC_HYDRO');
    expect(keys(shown)).toEqual(['ANY_GOO>EU_RENEWABLE>NORDIC_RENEWABLE>NORDIC_HYDRO']);
    expect(shown[0]?.alsoUnder?.code).toBe('EU_HYDRO');
  });

  it('closes a branch when the user toggles an open row', () => {
    const state = toggleExpanded({}, 'ANY_GOO>EU_RENEWABLE', 1);
    expect(keys(flattenPortfolio(nodes, state))).toEqual(['ANY_GOO', 'ANY_GOO>EU_RENEWABLE']);
    expect(isExpanded(state, 'ANY_GOO>EU_RENEWABLE', 1)).toBe(false);
    expect(isExpanded(toggleExpanded(state, 'ANY_GOO>EU_RENEWABLE', 1), 'ANY_GOO>EU_RENEWABLE', 1)).toBe(true);
  });

  it('handles several roots, unknown parents and an empty portfolio', () => {
    expect(flattenPortfolio([])).toEqual([]);
    const rows = flattenPortfolio([node('A', []), node('B', ['MISSING']), node('C', ['A'])]);
    expect(keys(rows)).toEqual(['A', 'A>C', 'B']);
  });

  it('cuts a loop in the data instead of running forever, and keeps every node', () => {
    const rows = flattenPortfolio([node('A', ['B']), node('B', ['A'])], { A: true, B: true });
    expect(new Set(rows.map((row) => row.node.bucketCode))).toEqual(new Set(['A', 'B']));
    expect(rows.length).toBeLessThan(10);
  });
});
