import { describe, expect, it } from 'vitest';
import {
  LIMIT_FIELDS,
  LIMIT_TYPES,
  limitSteps,
  limitUnit,
  scopeLabel,
  utilisationBar,
  utilisationColor,
} from './limits';

const names = {
  counterparty: (id: string) => (id === 'cp-1' ? 'Nordvind' : undefined),
  book: (id: string) => (id === 'b-1' ? 'DEMO-FI-TRD' : undefined),
  horizon: (days: number) => `expiring within ${days} days`,
  vintage: (year: number) => `vintage ${year}`,
};

describe('limits', () => {
  it('describes a scope with names and falls back to ids', () => {
    expect(scopeLabel({ counterpartyId: 'cp-1' }, names)).toBe('Nordvind');
    expect(scopeLabel({ counterpartyId: 'cp-9', bookId: 'b-1' }, names)).toBe('cp-9 · DEMO-FI-TRD');
    expect(scopeLabel({ productFamily: 'GUARANTEE_OF_ORIGIN' }, names)).toBe('GoO');
    expect(scopeLabel({ allowanceProductCode: 'EUA', complianceYear: 2026 }, names)).toBe('EUA 2026');
    expect(scopeLabel({}, names)).toBe('');
    expect(scopeLabel({ bucketCode: 'NORDIC_HYDRO' }, names)).toBe('NORDIC_HYDRO');
    expect(scopeLabel({ bucketCode: 'NORDIC_HYDRO', horizonDays: 90 }, names)).toBe(
      'NORDIC_HYDRO · expiring within 90 days',
    );
    expect(scopeLabel({ horizonDays: 30 }, names)).toBe('expiring within 30 days');
    expect(scopeLabel({ bucketCode: 'NORDIC_HYDRO', productionYear: 2025, horizonDays: 90 }, names)).toBe(
      'NORDIC_HYDRO · vintage 2025 · expiring within 90 days',
    );
  });

  it('gives the unit of each limit type', () => {
    expect(limitUnit({ type: 'CREDIT_EXPOSURE', currency: 'EUR', scope: {} })).toBe('EUR');
    expect(limitUnit({ type: 'TENOR_MAX_MONTHS', currency: null, scope: {} })).toBe('months');
    expect(limitUnit({ type: 'COMPLIANCE_SHORT', currency: null, scope: {} })).toBe('t');
    expect(limitUnit({ type: 'POSITION_NET', currency: null, scope: { productFamily: 'GUARANTEE_OF_ORIGIN' } })).toBe(
      'MWh',
    );
    expect(limitUnit({ type: 'POSITION_NET', currency: null, scope: { productFamily: 'EMISSION_ALLOWANCE' } })).toBe(
      't',
    );
  });

  it('measures GoO limits in MWh, or in EUR when a currency is set', () => {
    expect(limitUnit({ type: 'GOO_CONCENTRATION', currency: null, scope: { bucketCode: 'NORDIC_HYDRO' } })).toBe('MWh');
    expect(limitUnit({ type: 'GOO_CONCENTRATION', currency: 'EUR', scope: { bucketCode: 'NORDIC_HYDRO' } })).toBe(
      'EUR',
    );
    expect(limitUnit({ type: 'GOO_BASIS', currency: undefined, scope: {} })).toBe('MWh');
    expect(limitUnit({ type: 'GOO_BASIS', currency: 'EUR', scope: {} })).toBe('EUR');
    expect(limitUnit({ type: 'GOO_EXPIRY', currency: null, scope: { horizonDays: 60 } })).toBe('MWh');
  });

  it('scopes GoO limits to the whole entity, with a bucket and a horizon where they apply', () => {
    expect(LIMIT_TYPES).toEqual(expect.arrayContaining(['GOO_CONCENTRATION', 'GOO_EXPIRY', 'GOO_BASIS']));
    expect(LIMIT_FIELDS.GOO_CONCENTRATION).toMatchObject({
      bucket: 'required',
      horizon: 'hidden',
      book: false,
      vintage: true,
    });
    expect(LIMIT_FIELDS.CREDIT_EXPOSURE.vintage).toBe(false);
    expect(LIMIT_FIELDS.GOO_CONCENTRATION.currency).toBe('optional');
    expect(LIMIT_FIELDS.GOO_EXPIRY).toMatchObject({ bucket: 'optional', horizon: 'required', currency: 'hidden' });
    expect(LIMIT_FIELDS.GOO_BASIS).toMatchObject({ bucket: 'hidden', horizon: 'hidden', currency: 'optional' });
    for (const type of ['GOO_CONCENTRATION', 'GOO_EXPIRY', 'GOO_BASIS'] as const) {
      expect(LIMIT_FIELDS[type].book).toBe(false);
    }
    const others = LIMIT_TYPES.filter((type) => !type.startsWith('GOO_'));
    for (const type of others) {
      expect(LIMIT_FIELDS[type]).toMatchObject({ bucket: 'hidden', horizon: 'hidden' });
    }
  });

  it('builds the lifecycle from request to approval, rejection or retirement', () => {
    const t = (key: string) => key.replace('risk.lifecycle.', '');
    const limit = {
      requestedBy: 'risk',
      requestedAt: '2026-10-01T08:00:00Z',
      approvedBy: 'risk2',
      approvedAt: '2026-10-01T09:00:00Z',
      validTo: '2026-12-31',
    };
    const states = (status: 'PENDING_APPROVAL' | 'ACTIVE' | 'REJECTED' | 'RETIRED') =>
      limitSteps({ ...limit, status }, t).map((step) => `${step.label}:${step.state}`);
    expect(states('PENDING_APPROVAL')).toEqual(['requested:done', 'approval:current']);
    expect(states('ACTIVE')).toEqual(['requested:done', 'active:current']);
    expect(states('REJECTED')).toEqual(['requested:done', 'rejected:failed']);
    expect(states('RETIRED')).toEqual(['requested:done', 'active:done', 'retired:done']);
    expect(limitSteps({ ...limit, status: 'ACTIVE' }, t)[1]).toMatchObject({ by: 'risk2' });
  });

  it('colours and caps utilisation', () => {
    expect(utilisationColor('BREACH')).toBe('red');
    expect(utilisationColor('WARNING')).toBe('orange');
    expect(utilisationColor(undefined)).toBe('gray');
    expect(utilisationBar(130)).toBe(100);
    expect(utilisationBar(42.5)).toBe(42.5);
    expect(utilisationBar(undefined)).toBe(0);
  });
});
