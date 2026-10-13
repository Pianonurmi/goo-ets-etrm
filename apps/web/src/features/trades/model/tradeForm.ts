import { decimal, type Body, type Schema } from '@/shared/api/types';
import type { Criterion } from '@/features/criteria/model/tree';
import { EXAMPLE } from '@/features/criteria/model/tree';

export type TradeView = Schema<'TradeView'>;
export type CaptureTrade = Body<'CaptureTrade'>;
export type AmendTrade = Body<'AmendTrade'>;
type CaptureLeg = Body<'CaptureLeg'>;
type Counterparty = Schema<'CounterpartyView'>;
export type FuturesContract = Schema<'FuturesContractView'>;
export type PriceType = 'FIXED' | 'INDEX';
export type ProductFamily = CaptureTrade['productFamily'];
export type InstrumentType = CaptureTrade['instrumentType'];
export type Side = CaptureLeg['side'];
export type OptionType = NonNullable<CaptureLeg['option']>['optionType'];

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
  /** INDEX: the price is a spread added to the index fixing. */
  priceType: PriceType;
  priceIndex: string;
  futuresContractCode: string;
  /** OPTION only: call or put, strike (price per tonne), expiry and the futures contract the option is on. */
  optionType: OptionType;
  strike: string;
  expiryDate: string;
  underlyingContractCode: string;
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
  brokerId: string;
  masterAgreementRef: string;
  /** Intercompany only: the book of the other own entity that receives the mirror trade. */
  mirrorBookId: string;
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
    priceType: 'FIXED',
    priceIndex: '',
    futuresContractCode: '',
    optionType: 'CALL',
    strike: '',
    expiryDate: '',
    underlyingContractCode: '',
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
    brokerId: '',
    masterAgreementRef: '',
    mirrorBookId: '',
    reason: '',
    legs: [emptyLeg('GUARANTEE_OF_ORIGIN', today)],
  };
}

const blank = (value: string) => (value.trim() === '' ? null : value.trim());

/** A future or an option trades with an exchange or a clearing house. */
export function tradesFutures(counterparty: Counterparty | undefined): boolean {
  return counterparty?.counterpartyType === 'EXCHANGE' || counterparty?.counterpartyType === 'CLEARING_HOUSE';
}

/** A trade with one of our own entities: the mirror trade is booked in [mirrorBookId] of that entity. */
export function isIntercompany(counterparty: Counterparty | undefined): boolean {
  return counterparty?.counterpartyType === 'INTERCOMPANY' && Boolean(counterparty.linkedLegalEntityId);
}

/** Counterparties offered for the instrument: futures and options only with exchanges and clearing houses, never a broker. */
export function counterpartiesFor(list: Counterparty[], instrument: InstrumentType): Counterparty[] {
  return list.filter((counterparty) =>
    instrument === 'FUTURE' || instrument === 'OPTION'
      ? tradesFutures(counterparty)
      : counterparty.counterpartyType !== 'BROKER',
  );
}

export function brokersOf(list: Counterparty[]): Counterparty[] {
  return list.filter((counterparty) => counterparty.counterpartyType === 'BROKER');
}

function dayAfter(date: string): string {
  const next = new Date(`${date}T00:00:00Z`);
  next.setUTCDate(next.getUTCDate() + 1);
  return next.toISOString().slice(0, 10);
}

/**
 * Leg terms set by a futures contract: its allowance product and currency, and one delivery on the contract's
 * delivery date for the whole quantity (the server checks whole lots and the last trading day).
 */
export function withFuture(leg: LegValues, contract: FuturesContract): LegValues {
  return {
    ...leg,
    futuresContractCode: contract.code,
    allowanceProductCode: contract.allowanceProductCode,
    priceCurrency: contract.currency,
    priceType: 'FIXED',
    priceIndex: '',
    deliveries: [
      {
        start: contract.deliveryDate,
        endExclusive: dayAfter(contract.deliveryDate),
        deliveryDate: contract.deliveryDate,
        quantity: leg.quantity,
      },
    ],
  };
}

/**
 * Leg terms set by the futures contract an option is on: its allowance product and currency. The expiry stays as
 * the user typed it (the server checks it against the contract's last trading day).
 */
export function withUnderlying(leg: LegValues, contract: FuturesContract): LegValues {
  return {
    ...leg,
    underlyingContractCode: contract.code,
    allowanceProductCode: contract.allowanceProductCode,
    priceCurrency: contract.currency,
    priceType: 'FIXED',
    priceIndex: '',
  };
}

function toLeg(leg: LegValues, family: ProductFamily, instrument: InstrumentType): CaptureLeg {
  const goo = family === 'GUARANTEE_OF_ORIGIN';
  const future = instrument === 'FUTURE';
  const option = instrument === 'OPTION';
  const index = !future && !option && leg.priceType === 'INDEX';
  return {
    side: leg.side,
    criterion: goo ? leg.criterion : undefined,
    allowanceProductCode: goo ? null : blank(leg.allowanceProductCode),
    quantity: decimal(leg.quantity),
    unit: unitOf(family),
    price: decimal(leg.price),
    priceCurrency: leg.priceCurrency,
    priceType: index ? 'INDEX' : 'FIXED',
    priceIndex: index ? blank(leg.priceIndex) : null,
    futuresContractCode: future ? blank(leg.futuresContractCode) : null,
    // An option names its terms and delivers nothing: exercise creates the future. Other instruments carry none.
    option: option
      ? {
          optionType: leg.optionType,
          strike: decimal(leg.strike),
          expiryDate: leg.expiryDate,
          underlyingContractCode: leg.underlyingContractCode.trim(),
        }
      : undefined,
    deliveryAccountId: blank(leg.deliveryAccountId),
    counterpartyAccountRef: blank(leg.counterpartyAccountRef),
    deliveries: (option ? [] : leg.deliveries).map((delivery) => ({
      deliveryWindow: { start: delivery.start, endExclusive: delivery.endExclusive },
      deliveryDate: delivery.deliveryDate,
      // A future delivers its whole quantity once.
      quantity: decimal(future ? leg.quantity : delivery.quantity),
    })),
  };
}

const legsOf = (values: TradeFormValues) =>
  values.legs.map((leg) => toLeg(leg, values.productFamily, values.instrumentType));

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
    brokerId: blank(values.brokerId),
    masterAgreementRef: blank(values.masterAgreementRef),
    mirrorBookId: blank(values.mirrorBookId),
    regulatory: regulatory(values),
    legs: legsOf(values),
  };
}

export function toAmendTrade(values: TradeFormValues, expectedVersion: number): AmendTrade {
  return {
    expectedVersion,
    reason: values.reason.trim(),
    tradeDate: values.tradeDate,
    externalRef: blank(values.externalRef),
    brokerId: blank(values.brokerId),
    masterAgreementRef: blank(values.masterAgreementRef),
    regulatory: regulatory(values),
    legs: legsOf(values),
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
    brokerId: trade.brokerId ?? '',
    masterAgreementRef: trade.masterAgreementRef ?? '',
    mirrorBookId: '',
    reason: '',
    legs: trade.legs.map((leg) => ({
      side: leg.side,
      criterion: leg.criterion ?? EXAMPLE,
      allowanceProductCode: leg.allowanceProductCode ?? '',
      quantity: text(leg.quantity),
      price: text(leg.price),
      priceCurrency: leg.priceCurrency,
      priceType: leg.priceType === 'INDEX' ? 'INDEX' : 'FIXED',
      priceIndex: leg.priceIndex ?? '',
      futuresContractCode: leg.futuresContractCode ?? '',
      optionType: leg.option?.optionType ?? 'CALL',
      strike: text(leg.option?.strike),
      expiryDate: leg.option?.expiryDate ?? '',
      underlyingContractCode: leg.option?.underlyingContractCode ?? '',
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
  'brokerId',
  'masterAgreementRef',
  'mirrorBookId',
]);

/** What the server needs to know about the chosen counterparty to check the form. */
export interface TradeContext {
  amend: boolean;
  intercompany: boolean;
  /** The active futures contracts, to check an option's expiry and lots against its underlying. */
  contracts?: FuturesContract[];
}

/** True when [quantity] (a decimal string) is a whole number of lots of [size] tonnes; no floating point. */
export function wholeLots(quantity: string, size: number): boolean {
  const match = /^(\d+)(?:\.0*)?$/.exec(quantity.trim());
  if (!match || !Number.isInteger(size) || size <= 0) return false;
  return BigInt(match[1] ?? '0') % BigInt(size) === 0n;
}

function validateOption(
  leg: LegValues,
  index: number,
  tradeDate: string,
  contracts: FuturesContract[] | undefined,
  errors: Record<string, string>,
) {
  const required = 'Required';
  const at = (field: string) => `legs.${index}.${field}`;
  if (!leg.underlyingContractCode) errors[at('underlyingContractCode')] = required;
  if (!/^\d+(\.\d+)?$/.test(leg.strike.trim()) || !/[1-9]/.test(leg.strike)) errors[at('strike')] = 'A positive number';
  if (!leg.expiryDate) {
    errors[at('expiryDate')] = required;
  } else if (tradeDate && leg.expiryDate < tradeDate) {
    errors[at('expiryDate')] = 'The option must not expire before the trade date';
  }
  const contract = contracts?.find((candidate) => candidate.code === leg.underlyingContractCode);
  if (!contract) return;
  if (leg.expiryDate && leg.expiryDate > contract.lastTradingDay && !errors[at('expiryDate')]) {
    errors[at('expiryDate')] = `The option expires by the last trading day of the contract, ${contract.lastTradingDay}`;
  }
  if (/^\d+(\.\d+)?$/.test(leg.quantity.trim()) && !wholeLots(leg.quantity, contract.contractSize)) {
    errors[at('quantity')] = `Whole lots of ${contract.contractSize} t`;
  }
}

/** Checks the browser can make before sending; the server validates everything again. */
export function validateTrade(
  values: TradeFormValues,
  { amend, intercompany, contracts }: TradeContext,
): Record<string, string> {
  const errors: Record<string, string> = {};
  const required = 'Required';
  const positive = /^\d+(\.\d+)?$/;
  if (!values.bookId) errors.bookId = required;
  if (!values.counterpartyId) errors.counterpartyId = required;
  if (!values.tradeDate) errors.tradeDate = required;
  if (amend && !values.reason.trim()) errors.reason = required;
  if (!amend && intercompany && !values.mirrorBookId) errors.mirrorBookId = required;
  const future = values.instrumentType === 'FUTURE';
  const option = values.instrumentType === 'OPTION';
  values.legs.forEach((leg, index) => {
    if (future && !leg.futuresContractCode) errors[`legs.${index}.futuresContractCode`] = required;
    if (!future && !option && leg.priceType === 'INDEX' && !leg.priceIndex)
      errors[`legs.${index}.priceIndex`] = required;
    if (!positive.test(leg.quantity.trim()) || Number(leg.quantity) <= 0)
      errors[`legs.${index}.quantity`] = 'A positive number';
    if (!positive.test(leg.price.trim())) errors[`legs.${index}.price`] = 'A number';
    if (values.productFamily === 'EMISSION_ALLOWANCE' && !leg.allowanceProductCode)
      errors[`legs.${index}.allowanceProductCode`] = required;
    if (option) validateOption(leg, index, values.tradeDate, contracts, errors);
    // A future delivers once on its contract's date and an option delivers nothing: no schedule to check.
    if (future || option) return;
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
