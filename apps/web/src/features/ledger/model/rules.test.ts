import { describe, expect, it } from 'vitest';
import { emptyRule, parseConditions, ruleBody, ruleDraft, ruleProblems, type PostingRule } from './rules';

const rule: PostingRule = {
  id: 'r-1',
  legalEntityId: 'e-1',
  ruleCode: 'GOO_SALE_DELIVERY',
  eventType: 'DeliveryRecorded',
  conditions: { side: 'SELL' },
  lines: [
    { side: 'DEBIT', account: '1700', amount: 'amount' },
    { side: 'CREDIT', account: '3000', amount: 'amount' },
  ],
  description: 'GoO sale delivered',
  priority: 100,
  effectiveFrom: '2026-01-01',
  active: true,
  version: 2,
};

describe('posting rule changes', () => {
  it('starts a change from the rule and proposes it unchanged', () => {
    const draft = ruleDraft(rule);
    expect(ruleProblems(draft)).toEqual([]);
    expect(ruleBody(draft, rule.legalEntityId ?? null)).toEqual({
      ruleCode: 'GOO_SALE_DELIVERY',
      eventType: 'DeliveryRecorded',
      description: 'GoO sale delivered',
      priority: 100,
      effectiveFrom: '2026-01-01',
      active: true,
      conditions: { side: 'SELL' },
      legalEntityId: 'e-1',
      lines: rule.lines,
    });
  });

  it('needs a code, an event type, both sides and well-formed conditions', () => {
    const empty = emptyRule('2026-10-11');
    expect(ruleProblems(empty)).toEqual(['ruleCode', 'eventType', 'lines']);
    const draft = {
      ...empty,
      ruleCode: 'e2e_rule',
      eventType: 'X',
      lines: empty.lines.map((line) => ({ ...line, account: '1000' })),
    };
    expect(ruleProblems(draft)).toEqual([]);
    expect(ruleBody(draft, null)).toMatchObject({ ruleCode: 'E2E_RULE', description: null, conditions: {} });
    expect(ruleProblems({ ...draft, conditions: '{"a": 1}' })).toEqual(['conditions']);
    const debitsOnly = { ...draft, lines: draft.lines.map((line) => ({ ...line, side: 'DEBIT' as const })) };
    expect(ruleProblems(debitsOnly)).toEqual(['sides']);
  });

  it('reads conditions as a JSON object of strings', () => {
    expect(parseConditions(' ')).toEqual({});
    expect(parseConditions('{"movementType":"PURCHASE"}')).toEqual({ movementType: 'PURCHASE' });
    expect(parseConditions('nope')).toBeNull();
  });
});
