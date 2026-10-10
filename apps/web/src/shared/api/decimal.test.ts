import { decimal } from './types';

describe('decimal', () => {
  it('keeps the exact digits for the server', () => {
    expect(JSON.stringify({ price: decimal(' 1.950000000000000001 ') })).toBe('{"price":"1.950000000000000001"}');
  });
});
