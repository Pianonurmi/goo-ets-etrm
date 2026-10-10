import {
  addChild,
  EXAMPLE,
  incomplete,
  newCondition,
  nodeAt,
  removeAt,
  replaceAt,
  toggleGroup,
  wrapAt,
  type Criterion,
  type Field,
} from './tree';

const country: Field = {
  attribute: 'DEVICE_COUNTRY',
  kind: 'STRING',
  label: 'Device country',
  operators: ['EQUALS', 'IN'],
  valueSource: 'COUNTRY',
  values: [],
};

describe('criteria tree', () => {
  it('adds, replaces and reads nodes by path', () => {
    const added = addChild(EXAMPLE, [], newCondition(country, 'IN'));
    expect(nodeAt(added, [2])).toEqual({ type: 'in', attribute: 'DEVICE_COUNTRY', values: [] });
    const replaced = replaceAt(added, [2], { type: 'in', attribute: 'DEVICE_COUNTRY', values: ['FI', 'SE'] });
    expect(nodeAt(replaced, [2])).toMatchObject({ values: ['FI', 'SE'] });
    expect(EXAMPLE.type === 'all' && EXAMPLE.of).toHaveLength(2);
  });

  it('wraps, negates and toggles groups', () => {
    const negated = wrapAt(EXAMPLE, [1], 'not');
    expect(nodeAt(negated, [1])).toMatchObject({ type: 'not', of: { type: 'descendantOf' } });
    expect(nodeAt(negated, [1, 0])).toMatchObject({ type: 'descendantOf' });
    expect(toggleGroup(EXAMPLE, [])).toMatchObject({ type: 'any' });
    expect(wrapAt(EXAMPLE, [], 'any')).toMatchObject({ type: 'any', of: [{ type: 'all' }] });
  });

  it('removes operands, a negation with its operand, and falls back to match all', () => {
    expect(removeAt(EXAMPLE, [0])).toEqual({
      type: 'all',
      of: [{ type: 'descendantOf', attribute: 'ENERGY_SOURCE', code: 'HYDRO' }],
    });
    const negated = wrapAt(EXAMPLE, [1], 'not');
    expect(removeAt(negated, [1, 0])).toEqual(removeAt(EXAMPLE, [1]));
    const single: Criterion = { type: 'all', of: [{ type: 'matchAll' }] };
    expect(removeAt(single, [0])).toEqual({ type: 'matchAll' });
    expect(removeAt(EXAMPLE, [])).toEqual({ type: 'matchAll' });
  });

  it('creates empty conditions per operator and reports what is missing', () => {
    expect(
      newCondition({ ...country, valueSource: 'BOOLEAN', attribute: 'SUPPORT_PRODUCTION' }, 'EQUALS'),
    ).toMatchObject({
      value: 'false',
    });
    const draft = addChild(EXAMPLE, [], newCondition(country, 'EQUALS'));
    expect(incomplete(EXAMPLE)).toEqual([]);
    expect(incomplete(draft)).toEqual(['2']);
    expect(incomplete({ type: 'not', of: { type: 'labelsAny', labels: [] } })).toEqual(['0']);
  });

  it('round-trips through JSON unchanged', () => {
    const tree = wrapAt(
      addChild(EXAMPLE, [], { type: 'vintage', rule: { type: 'productionYear', year: 2026 } }),
      [0],
      'not',
    );
    expect(JSON.parse(JSON.stringify(tree))).toEqual(tree);
  });
});
