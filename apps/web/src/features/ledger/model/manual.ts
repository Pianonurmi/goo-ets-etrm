/** A manual journal line as typed: decimals stay strings, the server checks the balance. */
export interface LineDraft {
  accountCode: string;
  debit: string;
  credit: string;
  memo: string;
}

export const emptyLine = (): LineDraft => ({ accountCode: '', debit: '', credit: '', memo: '' });

const DECIMAL = /^\d+(\.\d+)?$/;

/** A line counts once it names an account and exactly one side carries a well-formed amount. */
export function lineState(line: LineDraft): 'empty' | 'complete' | 'invalid' {
  const debit = line.debit.trim();
  const credit = line.credit.trim();
  if (!line.accountCode && !debit && !credit && !line.memo.trim()) return 'empty';
  if (!line.accountCode) return 'invalid';
  if (Boolean(debit) === Boolean(credit)) return 'invalid';
  return DECIMAL.test(debit || credit) ? 'complete' : 'invalid';
}

/** At least two complete lines and nothing half-typed. */
export function readyToPost(lines: LineDraft[]): boolean {
  const states = lines.map(lineState);
  return !states.includes('invalid') && states.filter((state) => state === 'complete').length >= 2;
}

export interface ManualLineInput {
  accountCode: string;
  debit?: string;
  credit?: string;
  memo?: string;
}

/** The complete lines as the API takes them (amounts as strings, empty sides left out). */
export function manualLines(lines: LineDraft[]): ManualLineInput[] {
  return lines
    .filter((line) => lineState(line) === 'complete')
    .map((line) => ({
      accountCode: line.accountCode,
      debit: line.debit.trim() || undefined,
      credit: line.credit.trim() || undefined,
      memo: line.memo.trim() || undefined,
    }));
}
