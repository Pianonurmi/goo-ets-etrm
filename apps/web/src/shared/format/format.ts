import dayjs from 'dayjs';

const LOCALE = 'en-GB';
const cache = new Map<string, Intl.NumberFormat>();

function numberFormat(minimum: number, maximum: number): Intl.NumberFormat {
  const key = `${minimum}:${maximum}`;
  let format = cache.get(key);
  if (!format) {
    format = new Intl.NumberFormat(LOCALE, { minimumFractionDigits: minimum, maximumFractionDigits: maximum });
    cache.set(key, format);
  }
  return format;
}

type Numeric = number | string | null | undefined;

function toNumber(value: Numeric): number | null {
  if (value === null || value === undefined || value === '') return null;
  const parsed = typeof value === 'number' ? value : Number(value);
  return Number.isFinite(parsed) ? parsed : null;
}

/** Display only; amounts are computed by the server, never in the UI (ADR 0025). */
export function formatDecimal(value: Numeric, minimumFractionDigits = 0, maximumFractionDigits = 4): string {
  const parsed = toNumber(value);
  return parsed === null ? '' : numberFormat(minimumFractionDigits, maximumFractionDigits).format(parsed);
}

export function formatMoney(value: Numeric, currency?: string | null): string {
  const amount = formatDecimal(value, 2, 2);
  return amount && currency ? `${amount} ${currency}` : amount;
}

export function formatQuantity(value: Numeric, unit?: string | null): string {
  const quantity = formatDecimal(value, 0, 3);
  return quantity && unit ? `${quantity} ${unit}` : quantity;
}

export function formatDate(value: string | null | undefined): string {
  return value ? dayjs(value).format('YYYY-MM-DD') : '';
}

/** Instants are UTC on the wire; shown in the browser's time zone. */
export function formatDateTime(value: string | null | undefined): string {
  return value ? dayjs(value).format('YYYY-MM-DD HH:mm') : '';
}
