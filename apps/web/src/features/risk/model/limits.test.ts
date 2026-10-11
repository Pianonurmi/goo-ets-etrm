import { describe, expect, it } from 'vitest';
import { limitSteps, limitUnit, scopeLabel, utilisationBar, utilisationColor } from './limits';

const names = {
  counterparty: (id: string) => (id === 'cp-1' ? 'Nordvind' : undefined),
  book: (id: string) => (id === 'b-1' ? 'DEMO-FI-TRD' : undefined),
};

describe('limits', () => {
  it('describes a scope with names and falls back to ids', () => {
    expect(scopeLabel({ counterpartyId: 'cp-1' }, names)).toBe('Nordvind');
    expect(scopeLabel({ counterpartyId: 'cp-9', bookId: 'b-1' }, names)).toBe('cp-9 · DEMO-FI-TRD');
    expect(scopeLabel({ productFamily: 'GUARANTEE_OF_ORIGIN' }, names)).toBe('GoO');
    expect(scopeLabel({ allowanceProductCode: 'EUA', complianceYear: 2026 }, names)).toBe('EUA 2026');
    expect(scopeLabel({}, names)).toBe('');
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
