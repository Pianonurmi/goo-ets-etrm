import { describe, expect, it } from 'vitest';
import { emptyLine, lineState, manualLines, readyToPost } from './manual';

const line = (accountCode: string, debit = '', credit = '', memo = '') => ({ accountCode, debit, credit, memo });

describe('manual journal lines', () => {
  it('classifies lines', () => {
    expect(lineState(emptyLine())).toBe('empty');
    expect(lineState(line('1000', '10.50'))).toBe('complete');
    expect(lineState(line('1000', '', '3'))).toBe('complete');
    expect(lineState(line('', '10'))).toBe('invalid');
    expect(lineState(line('1000', '10', '10'))).toBe('invalid');
    expect(lineState(line('1000'))).toBe('invalid');
    expect(lineState(line('1000', '1,5'))).toBe('invalid');
  });

  it('needs two complete lines and nothing half-typed', () => {
    expect(readyToPost([line('1000', '5'), line('2000', '', '5'), emptyLine()])).toBe(true);
    expect(readyToPost([line('1000', '5'), emptyLine()])).toBe(false);
    expect(readyToPost([line('1000', '5'), line('2000', '', '5'), line('', '1')])).toBe(false);
  });

  it('sends complete lines with one side each', () => {
    expect(manualLines([line('1000', ' 5.00 ', '', ' fee '), emptyLine(), line('2000', '', '5')])).toEqual([
      { accountCode: '1000', debit: '5.00', credit: undefined, memo: 'fee' },
      { accountCode: '2000', debit: undefined, credit: '5', memo: undefined },
    ]);
  });
});
