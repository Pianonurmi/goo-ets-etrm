import { describe, expect, it } from 'vitest';
import type { Schema } from '@/shared/api/types';
import {
  correctionBody,
  correctionValues,
  journalResult,
  ladderRows,
  lotActions,
  newReservationRef,
  reservationBody,
  validReservation,
} from './lotOps';

type Lot = Schema<'LotView'>;

const lot = {
  id: 'l1',
  status: 'HELD',
  reservationRef: null,
  quantityMwh: 100,
  expiryDate: '2027-06-30',
  supportProduction: false,
  supportInvestment: null,
  supportSchemeCode: null,
  labels: ['EKOENERGY'],
  costBasisPerMwh: 1.1,
  costCurrency: 'EUR',
} as unknown as Lot;

describe('lot actions', () => {
  it('offers the operations each status allows', () => {
    expect(lotActions({ status: 'HELD', reservationRef: null })).toEqual([
      'transfer',
      'registryCancel',
      'reserve',
      'split',
      'correct',
      'cancel',
      'withdraw',
    ]);
    expect(lotActions({ status: 'RESERVED', reservationRef: 'r1' })).toEqual(['release', 'correct', 'withdraw']);
    expect(lotActions({ status: 'RESERVED', reservationRef: null })).toEqual(['correct', 'withdraw']);
    expect(lotActions({ status: 'IN_TRANSFER', reservationRef: null })).toEqual(['correct', 'withdraw']);
    expect(lotActions({ status: 'CANCELLED', reservationRef: null })).toEqual([]);
    expect(lotActions({ status: 'SPLIT', reservationRef: null })).toEqual([]);
  });

  it('makes version 4 reservation references', () => {
    const ref = newReservationRef();
    expect(ref).toMatch(/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/);
    expect(newReservationRef()).not.toBe(ref);
  });

  it('reserves the whole lot or its first certificates', () => {
    expect(reservationBody('', 'r1')).toEqual({ reservationRef: 'r1', quantity: null });
    expect(reservationBody(40, 'r1')).toEqual({ reservationRef: 'r1', quantity: 40 });
    expect(validReservation('', 100)).toBe(true);
    expect(validReservation(100, 100)).toBe(true);
    expect(validReservation(0, 100)).toBe(false);
    expect(validReservation(101, 100)).toBe(false);
    expect(validReservation(1.5, 100)).toBe(false);
  });
});

describe('lot correction', () => {
  it('sends only what changed, with the reason', () => {
    const values = { ...correctionValues(lot), reason: ' Registry correction ' };
    expect(correctionBody(lot, values)).toBeNull();
    expect(correctionBody(lot, { ...values, expiryDate: '2027-12-31' })).toEqual({
      reason: 'Registry correction',
      expiryDate: '2027-12-31',
    });
    expect(correctionBody(lot, { ...values, supportInvestment: true, labels: ['EKOENERGY'] })).toEqual({
      reason: 'Registry correction',
      supportInvestment: true,
    });
    expect(correctionBody(lot, { ...values, labels: [] })).toEqual({ reason: 'Registry correction', labels: [] });
  });

  it('passes the cost on as the typed decimal', () => {
    const values = { ...correctionValues(lot), reason: 'Price fixed' };
    expect(values.costBasis).toBe('1.1');
    expect(correctionBody(lot, { ...values, costBasis: '1.2500' })).toEqual({
      reason: 'Price fixed',
      costBasisPerMwh: '1.2500',
      costCurrency: 'EUR',
    });
    expect(correctionBody(lot, { ...values, costCurrency: 'sek' })).toMatchObject({ costCurrency: 'SEK' });
  });
});

describe('expiry ladder and journal', () => {
  it('fills the months nothing expires in', () => {
    const rows = ladderRows({ '2026-11-01': 250, '2027-01-01': 400 }, '2026-10-11', 4);
    expect(rows.map((row) => [row.month, row.mwh])).toEqual([
      ['2026-10', 0],
      ['2026-11', 250],
      ['2026-12', 0],
      ['2027-01', 400],
    ]);
    expect(ladderRows(undefined, '2026-10-11', 2).every((row) => row.mwh === 0)).toBe(true);
  });

  it('reads the journal check', () => {
    expect(journalResult(undefined)).toBeNull();
    expect(journalResult({ ok: true, brokenLots: [] })).toEqual({ ok: true, brokenLots: [] });
    expect(journalResult({ ok: false, brokenLots: ['l1', 2] })).toEqual({ ok: false, brokenLots: ['l1'] });
  });
});
