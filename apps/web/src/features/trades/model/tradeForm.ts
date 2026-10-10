import { decimal, type Schema } from '@/shared/api/types';
import type { Criterion } from '@/features/criteria/model/tree';
import { EXAMPLE } from '@/features/criteria/model/tree';

export type TradeView = Schema<'TradeView'>;
export type CaptureTrade = Schema<'CaptureTrade'>;
export type AmendTrade = Schema<'AmendTrade'>;
type CaptureLeg = Schema<'CaptureLeg'>;
export type ProductFamily = CaptureTrade['productFamily'];
export type InstrumentType = CaptureTrade['instrumentType'];
export type Side = CaptureLeg['side'];

/** Form state: decimals stay strings so nothing is rounded before the server sees it. */
export interface DeliveryValues {
  start: string;
  endExclusive: string;
  deliveryDate: string;
  quantity: string;
}

export interface LegValues {
  side: Side;
  criterion: Criterion;
  allowanceProductCode: string;
  quantity: string;
  price: string;
  priceCurrency: string;
  deliveryAccountId: string;
  counterpartyAccountRef: string;
  deliveries: DeliveryValues[];
}

export interface TradeFormValues {
  bookId: string;
  counterpartyId: string;
  productFamily: ProductFamily;
  instrumentType: InstrumentType;
  tradeDate: string;
  trader: string;
  externalRef: string;
  executionTimestamp: string;
  venueMic: string;
  reason: string;
  legs: LegValues[];
}

export function unitOf(family: ProductFamily): 'MWH' | 'TCO2E' {
  return family === 'EMISSION_ALLOWANCE' ? 'TCO2E' : 'MWH';
}

export function emptyDelivery(today: string): DeliveryValues {
  const year = Number(today.slice(0, 4));
  return { start: today, endExclusive: `${year + 1}-01-01`, deliveryDate: `${year}-12-15`, quantity: '' };
}

export function emptyLeg(family: ProductFamily, today: string): LegValues {
  return {
    side: 'SELL',
    criterion: EXAMPLE,
    allowanceProductCode: family === 'EMISSION_ALLOWANCE' ? 'EUA' : '',
    quantity: '',
    price: '',
    priceCurrency: 'EUR',
    deliveryAccountId: '',
    counterpartyAccountRef: '',
    deliveries: [emptyDelivery(today)],
  };
}

export function emptyTrade(today: string): TradeFormValues {
  return {
    bookId: '',
    counterpartyId: '',
    productFamily: 'GUARANTEE_OF_ORIGIN',
    instrumentType: 'FORWARD',
    tradeDate: today,
    trader: '',
    externalRef: '',
    executionTimestamp: '',
    venueMic: '',
    reason: '',
    legs: [emptyLeg('GUARANTEE_OF_ORIGIN', today)],
  };
}

const blank = (value: string) => (value.trim() === '' ? null : value.trim());

function toLeg(leg: LegValues, family: ProductFamily): CaptureLeg {
  const goo = family === 'GUARANTEE_OF_ORIGIN';
  return {
    side: leg.side,
    criterion: goo ? leg.criterion : undefined,
    allowanceProductCode: goo ? null : blank(leg.allowanceProductCode),
    quantity: decimal(leg.quantity),
    unit: unitOf(family),
    price: decimal(leg.price),
    priceCurrency: leg.priceCurrency,
    priceType: 'FIXED',
    deliveryAccountId: blank(leg.deliveryAccountId),
    counterpartyAccountRef: blank(leg.counterpartyAccountRef),
    deliveries: leg.deliveries.map((delivery) => ({
      deliveryWindow: { start: delivery.start, endExclusive: delivery.endExclusive },
      deliveryDate: delivery.deliveryDate,
      quantity: decimal(delivery.quantity),
    })),
  };
}

function regulatory(values: TradeFormValues): CaptureTrade['regulatory'] {
  if (!values.executionTimestamp && !values.venueMic) return null;
  return {
    executionTimestamp: values.executionTimestamp ? new Date(values.executionTimestamp).toISOString() : null,
    venueMic: blank(values.venueMic),
  };
}

export function toCaptureTrade(values: TradeFormValues, legalEntityId: string): CaptureTrade {
  return {
    legalEntityId,
    bookId: values.bookId,
    counterpartyId: values.counterpartyId,
    productFamily: values.productFamily,
    instrumentType: values.instrumentType,
    tradeDate: values.tradeDate,
    trader: blank(values.trader),
    externalRef: blank(values.externalRef),
    regulatory: regulatory(values),
    legs: values.legs.map((leg) => toLeg(leg, values.productFamily)),
  };
}

export function toAmendTrade(values: TradeFormValues, expectedVersion: number): AmendTrade {
  return {
    expectedVersion,
    reason: values.reason.trim(),
    tradeDate: values.tradeDate,
    externalRef: blank(values.externalRef),
    regulatory: regulatory(values),
    legs: values.legs.map((leg) => toLeg(leg, values.productFamily)),
  };
}

const text = (value: number | string | null | undefined) =>
  value === null || value === undefined ? '' : String(value);

/** The form for amending an existing trade: the current version's terms. */
export function fromTrade(trade: TradeView): TradeFormValues {
  return {
    bookId: trade.bookId,
    counterpartyId: trade.counterpartyId,
    productFamily: trade.productFamily,
    instrumentType: trade.instrumentType,
    tradeDate: trade.tradeDate,
    trader: trade.trader,
    externalRef: trade.externalRef ?? '',
    executionTimestamp: trade.regulatory?.executionTimestamp?.slice(0, 16) ?? '',
    venueMic: trade.regulatory?.venueMic ?? '',
    reason: '',
    legs: trade.legs.map((leg) => ({
      side: leg.side,
      criterion: leg.criterion ?? EXAMPLE,
      allowanceProductCode: leg.allowanceProductCode ?? '',
      quantity: text(leg.quantity),
      price: text(leg.price),
      priceCurrency: leg.priceCurrency,
      deliveryAccountId: leg.deliveryAccountId ?? '',
      counterpartyAccountRef: leg.counterpartyAccountRef ?? '',
      deliveries: leg.deliveries.map((delivery) => ({
        start: delivery.deliveryWindow.start,
        endExclusive: delivery.deliveryWindow.endExclusive,
        deliveryDate: delivery.deliveryDate,
        quantity: text(delivery.quantity),
      })),
    })),
  };
}

/** Field names the form shows, for placing server violations (see applyProblemToForm). */
export const FORM_FIELDS = new Set([
  'bookId',
  'counterpartyId',
  'productFamily',
  'instrumentType',
  'tradeDate',
  'trader',
  'externalRef',
  'reason',
  'legs',
  'regulatory',
]);

/** Checks the browser can make before sending; the server validates everything again. */
export function validateTrade(values: TradeFormValues, amend: boolean): Record<string, string> {
  const errors: Record<string, string> = {};
  const required = 'Required';
  const positive = /^\d+(\.\d+)?$/;
  if (!values.bookId) errors.bookId = required;
  if (!values.counterpartyId) errors.counterpartyId = required;
  if (!values.tradeDate) errors.tradeDate = required;
  if (amend && !values.reason.trim()) errors.reason = required;
  values.legs.forEach((leg, index) => {
    if (!positive.test(leg.quantity.trim()) || Number(leg.quantity) <= 0)
      errors[`legs.${index}.quantity`] = 'A positive number';
    if (!positive.test(leg.price.trim())) errors[`legs.${index}.price`] = 'A number';
    if (values.productFamily === 'EMISSION_ALLOWANCE' && !leg.allowanceProductCode)
      errors[`legs.${index}.allowanceProductCode`] = required;
    leg.deliveries.forEach((delivery, d) => {
      if (!positive.test(delivery.quantity.trim()))
        errors[`legs.${index}.deliveries.${d}.quantity`] = 'A positive number';
      if (!delivery.deliveryDate) errors[`legs.${index}.deliveries.${d}.deliveryDate`] = required;
      if (!delivery.start || !delivery.endExclusive || delivery.start >= delivery.endExclusive) {
        errors[`legs.${index}.deliveries.${d}.endExclusive`] = 'Window end must follow its start';
      }
    });
  });
  return errors;
}
