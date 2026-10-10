/** One balance line pasted from the general ledger: account, balance, currency. Amounts stay strings. */
export interface PastedBalance {
  glAccount: string;
  balance: string;
  currency: string;
}

export interface ParsedBalances {
  lines: PastedBalance[];
  /** 1-based line numbers that could not be read. */
  errors: number[];
}

const AMOUNT = /^-?\d+(\.\d+)?$/;

/**
 * Reads `account;balance;currency` lines (comma, semicolon or tab separated, a header line allowed) as copied from
 * a GL trial balance. Thousands separators are not guessed: the amount must be a plain decimal.
 */
export function parseGlBalances(text: string, defaultCurrency = 'EUR'): ParsedBalances {
  const lines: PastedBalance[] = [];
  const errors: number[] = [];
  text.split(/\r?\n/).forEach((raw, index) => {
    const line = raw.trim();
    if (!line) return;
    const [account = '', balance = '', currency = ''] = line.split(/[;,\t]/).map((cell) => cell.trim());
    if (index === 0 && !AMOUNT.test(balance)) return; // header
    if (!account || !AMOUNT.test(balance)) {
      errors.push(index + 1);
      return;
    }
    lines.push({ glAccount: account, balance, currency: (currency || defaultCurrency).toUpperCase() });
  });
  return { lines, errors };
}

/** The previous calendar month as `YYYY-MM`. */
export function previousPeriod(today: Date): string {
  const year = today.getUTCMonth() === 0 ? today.getUTCFullYear() - 1 : today.getUTCFullYear();
  const month = today.getUTCMonth() === 0 ? 12 : today.getUTCMonth();
  return `${year}-${String(month).padStart(2, '0')}`;
}
