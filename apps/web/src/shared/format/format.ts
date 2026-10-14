/** Where display text comes from: the browser's locale and the selected entity's time zone (W7). */
const FALLBACK_LOCALE = 'en-GB';

/** The browser's language when the runtime knows it, otherwise `en-GB`. */
export function displayLocale(): string {
  const candidate = typeof navigator === 'undefined' ? undefined : navigator.language;
  if (!candidate) return FALLBACK_LOCALE;
  try {
    return Intl.NumberFormat.supportedLocalesOf(candidate).length > 0 ? candidate : FALLBACK_LOCALE;
  } catch {
    return FALLBACK_LOCALE;
  }
}

const numberFormats = new Map<string, Intl.NumberFormat>();

function numberFormat(options: Intl.NumberFormatOptions): Intl.NumberFormat {
  const locale = displayLocale();
  const key = `${locale}:${JSON.stringify(options)}`;
  let format = numberFormats.get(key);
  if (!format) {
    format = new Intl.NumberFormat(locale, options);
    numberFormats.set(key, format);
  }
  return format;
}

type Numeric = number | string | null | undefined;

const DECIMAL = /^[+-]?(\d+\.?\d*|\.\d+)([eE][+-]?\d+)?$/;

/**
 * What to hand to `Intl`: a decimal string stays a string, so digits beyond what a double holds are formatted
 * exactly; a number must be finite; anything else is not displayed.
 */
function numericInput(value: Numeric): `${number}` | number | null {
  if (value === null || value === undefined || value === '') return null;
  if (typeof value === 'number') return Number.isFinite(value) ? value : null;
  const text = value.trim();
  return DECIMAL.test(text) ? (text as `${number}`) : null;
}

/** Display only; amounts are computed by the server, never in the UI (ADR 0025). */
export function formatDecimal(value: Numeric, minimumFractionDigits = 0, maximumFractionDigits = 4): string {
  const input = numericInput(value);
  return input === null ? '' : numberFormat({ minimumFractionDigits, maximumFractionDigits }).format(input);
}

/** A fraction as a percentage (`0.95` → `95 %`); display only, the server computes the ratio. */
export function formatPercent(fraction: Numeric, maximumFractionDigits = 1): string {
  const input = numericInput(fraction);
  if (input === null) return '';
  const text = numberFormat({ style: 'percent', maximumFractionDigits }).format(input);
  return text.replace(/\s?%/, ' %');
}

export function formatMoney(value: Numeric, currency?: string | null): string {
  const amount = formatDecimal(value, 2, 2);
  return amount && currency ? `${amount} ${currency}` : amount;
}

export function formatQuantity(value: Numeric, unit?: string | null): string {
  const quantity = formatDecimal(value, 0, 3);
  return quantity && unit ? `${quantity} ${unit}` : quantity;
}

const dateFormats = new Map<string, Intl.DateTimeFormat>();

function dateFormat(options: Intl.DateTimeFormatOptions): Intl.DateTimeFormat {
  const locale = displayLocale();
  const key = `${locale}:${JSON.stringify(options)}`;
  let format = dateFormats.get(key);
  if (!format) {
    format = new Intl.DateTimeFormat(locale, options);
    dateFormats.set(key, format);
  }
  return format;
}

const CALENDAR_DAY = /^(\d{4})-(\d{2})-(\d{2})$/;

/** A calendar date (`2026-12-15`) in the user's locale; it has no zone, so it never shifts a day. */
export function formatDate(value: string | null | undefined): string {
  if (!value) return '';
  const parts = CALENDAR_DAY.exec(value);
  const day = parts ? new Date(Date.UTC(Number(parts[1]), Number(parts[2]) - 1, Number(parts[3]))) : new Date(value);
  if (Number.isNaN(day.getTime())) return '';
  return dateFormat({
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    ...(parts ? { timeZone: 'UTC' } : {}),
  }).format(day);
}

let displayZone: string | undefined;

/**
 * The time zone instants are shown in: the selected legal entity's (`EntityProvider` sets it), or the browser's
 * when no entity is selected or the zone is unknown.
 */
export function setDisplayTimeZone(zone: string | null | undefined): void {
  displayZone = zone && isTimeZone(zone) ? zone : undefined;
}

export function displayTimeZone(): string | undefined {
  return displayZone;
}

function isTimeZone(zone: string): boolean {
  try {
    new Intl.DateTimeFormat('en-GB', { timeZone: zone });
    return true;
  } catch {
    return false;
  }
}

/** Instants are UTC on the wire; shown in the entity's time zone with a short zone name (`14:30 EEST`). */
export function formatDateTime(value: string | null | undefined, timeZone: string | undefined = displayZone): string {
  if (!value) return '';
  const instant = new Date(value);
  if (Number.isNaN(instant.getTime())) return '';
  return dateFormat({
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    timeZoneName: 'short',
    ...(timeZone ? { timeZone } : {}),
  }).format(instant);
}
