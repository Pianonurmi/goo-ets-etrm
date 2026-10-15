import { type Schema } from '@/shared/api/types';

export type Instrument = Schema<'InstrumentView'>;

export type Quote = Schema<'QuoteView'>;

export type QuoteType = Schema<'RecordQuote'>['quoteType'];

// VOLATILITY is the implied volatility of options on a future, in percent a year.
export const QUOTE_TYPES: QuoteType[] = ['SETTLEMENT', 'MID', 'BID', 'ASK', 'CLOSE', 'FIXING', 'VOLATILITY'];
