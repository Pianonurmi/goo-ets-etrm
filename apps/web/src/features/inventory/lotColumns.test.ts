import dayjs from 'dayjs';
import { daysToExpiry, totals, type Lot } from './lotColumns';

const lot = (status: string, expiryDate: string, quantityMwh: number) => ({ status, expiryDate, quantityMwh }) as Lot;

describe('lot helpers', () => {
  it('counts days to expiry for live lots only', () => {
    const today = dayjs('2026-10-10');
    expect(daysToExpiry(lot('HELD', '2026-11-09', 1), today)).toBe(30);
    expect(daysToExpiry(lot('RESERVED', '2026-10-10', 1), today)).toBe(0);
    expect(daysToExpiry(lot('CANCELLED', '2026-11-09', 1), today)).toBeNull();
  });

  it('totals whole certificates', () => {
    expect(totals([lot('HELD', '2027-01-01', 250), lot('HELD', '2027-01-01', 375)])).toEqual({ count: 2, mwh: 625 });
    expect(totals([])).toEqual({ count: 0, mwh: 0 });
  });
});
