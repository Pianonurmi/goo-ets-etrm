import { fieldPathLabel } from '@/shared/forms/staleVersion';

/** The form's own label (a catalogue key) for each field, by the last part of its path. */
const FIELD_KEYS: Record<string, string> = {
  bookId: 'trades.columns.book',
  counterpartyId: 'trades.columns.counterparty',
  productFamily: 'trades.family',
  instrumentType: 'trades.columns.instrument',
  tradeDate: 'trades.columns.date',
  trader: 'trades.columns.trader',
  externalRef: 'trades.columns.externalRef',
  executionTimestamp: 'trades.form.executionTimestamp',
  venueMic: 'trades.form.venueMic',
  brokerId: 'trades.form.broker',
  masterAgreementRef: 'trades.form.masterAgreement',
  mirrorBookId: 'trades.form.mirrorBook',
  side: 'trades.columns.side',
  criterion: 'trades.form.criteria',
  allowanceProductCode: 'trades.form.allowanceProduct',
  quantity: 'trades.columns.quantity',
  price: 'trades.columns.price',
  priceCurrency: 'trades.form.currency',
  priceType: 'trades.form.priceType',
  priceIndex: 'trades.form.priceIndex',
  futuresContractCode: 'trades.form.futuresContract',
  optionType: 'trades.form.optionType',
  strike: 'trades.form.strike',
  expiryDate: 'trades.form.expiry',
  underlyingContractCode: 'trades.form.underlying',
  deliveryAccountId: 'trades.form.deliveryAccount',
  counterpartyAccountRef: 'trades.form.counterpartyAccount',
  start: 'trades.form.windowStart',
  endExclusive: 'trades.form.windowEnd',
  deliveryDate: 'trades.form.deliveryDate',
};

type Translate = (key: string, options?: Record<string, unknown>) => string;

/** `legs.1.deliveries.0.quantity` → `Leg 2 · Deliveries #1 · Quantity`, with the form's own wording. */
export function tradeFieldLabel(path: string, t: Translate): string {
  const parts = path.split('.');
  const out: string[] = [];
  for (let i = 0; i < parts.length; i += 1) {
    const part = parts[i] ?? '';
    const next = parts[i + 1];
    if (part === 'legs' && next && /^\d+$/.test(next)) {
      out.push(t('trades.form.leg', { number: Number(next) + 1 }));
      i += 1;
    } else if (part === 'deliveries' && next && /^\d+$/.test(next)) {
      out.push(`${t('trades.form.deliveries')} #${Number(next) + 1}`);
      i += 1;
    } else if (part === 'legs') {
      out.push(t('trades.form.leg', { number: '' }).trim());
    } else if (part === 'deliveries') {
      out.push(t('trades.form.deliveries'));
    } else {
      const key = FIELD_KEYS[part];
      out.push(key ? t(key) : fieldPathLabel(part));
    }
  }
  return out.join(' · ');
}
