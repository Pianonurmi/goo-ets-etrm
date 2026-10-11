import { newKey } from './ids';

describe('newKey', () => {
  it('makes distinct prefixed keys of 32 hex digits', () => {
    const first = newKey('ui');
    expect(first).toMatch(/^ui-[0-9a-f]{32}$/);
    expect(newKey('ui')).not.toBe(first);
  });
});
