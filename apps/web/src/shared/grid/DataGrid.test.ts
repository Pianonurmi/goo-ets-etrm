import { opensRow } from './DataGrid';

describe('grid keyboard', () => {
  it('opens the focused row with Enter or Space, but not with a modifier or another key', () => {
    expect(opensRow(new KeyboardEvent('keydown', { key: 'Enter' }))).toBe(true);
    expect(opensRow(new KeyboardEvent('keydown', { key: ' ' }))).toBe(true);
    expect(opensRow(new KeyboardEvent('keydown', { key: 'Enter', ctrlKey: true }))).toBe(false);
    expect(opensRow(new KeyboardEvent('keydown', { key: 'ArrowDown' }))).toBe(false);
    expect(opensRow(new MouseEvent('click'))).toBe(false);
    expect(opensRow(null)).toBe(false);
  });
});
