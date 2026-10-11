import { parseJsonObject } from './JsonEditorModal';

describe('parseJsonObject', () => {
  it('accepts a JSON object only', () => {
    expect(parseJsonObject('{"kind":"QUOTES_LONG_CSV"}')).toEqual({ kind: 'QUOTES_LONG_CSV' });
    expect(parseJsonObject('[1]')).toBeNull();
    expect(parseJsonObject('null')).toBeNull();
    expect(parseJsonObject('{"kind":')).toBeNull();
  });
});
