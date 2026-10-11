import type { Body, Schema } from '@/shared/api/types';
import { parseStringMap } from '@/features/admin/model/referenceEdits';

export type PostingRule = Schema<'PostingRuleView'>;
export type RuleSide = Schema<'RuleLine'>['side'];

/** A rule line as typed: the amount names a fact of the event (e.g. `amount`, `cost`), not a number. */
export interface RuleLineDraft {
  side: RuleSide;
  account: string;
  amount: string;
}

export interface RuleDraft {
  ruleCode: string;
  eventType: string;
  description: string;
  priority: number;
  effectiveFrom: string;
  active: boolean;
  conditions: string;
  lines: RuleLineDraft[];
}

const RULE_CODE = /^[A-Z0-9_-]{2,60}$/;

export function emptyRule(today: string): RuleDraft {
  return {
    ruleCode: '',
    eventType: '',
    description: '',
    priority: 100,
    effectiveFrom: today,
    active: true,
    conditions: '',
    lines: [
      { side: 'DEBIT', account: '', amount: 'amount' },
      { side: 'CREDIT', account: '', amount: 'amount' },
    ],
  };
}

/** A change to an existing rule starts from its current terms. */
export function ruleDraft(rule: PostingRule): RuleDraft {
  return {
    ruleCode: rule.ruleCode,
    eventType: rule.eventType,
    description: rule.description ?? '',
    priority: rule.priority,
    effectiveFrom: rule.effectiveFrom,
    active: rule.active,
    conditions: Object.keys(rule.conditions).length === 0 ? '' : JSON.stringify(rule.conditions, null, 2),
    lines: rule.lines.map((line) => ({ ...line })),
  };
}

/** Rule conditions: a JSON object of attribute to value; null when the text is not one. Blank means none. */
export const parseConditions = parseStringMap;

/** What still stops the change from being proposed, by field. */
export function ruleProblems(draft: RuleDraft): string[] {
  const problems: string[] = [];
  if (!RULE_CODE.test(draft.ruleCode.trim().toUpperCase())) problems.push('ruleCode');
  if (!draft.eventType.trim()) problems.push('eventType');
  if (!draft.effectiveFrom) problems.push('effectiveFrom');
  if (parseConditions(draft.conditions) === null) problems.push('conditions');
  if (draft.lines.length < 2 || draft.lines.some((line) => !line.account || !line.amount.trim())) {
    problems.push('lines');
  }
  if (!draft.lines.some((line) => line.side === 'DEBIT') || !draft.lines.some((line) => line.side === 'CREDIT')) {
    problems.push('sides');
  }
  return problems;
}

/** The proposal as the API takes it; a rule of a legal entity keeps that entity, a group rule stays without. */
export function ruleBody(draft: RuleDraft, legalEntityId: string | null): Body<'UpsertPostingRule'> {
  return {
    ruleCode: draft.ruleCode.trim().toUpperCase(),
    eventType: draft.eventType.trim(),
    description: draft.description.trim() || null,
    priority: Math.trunc(draft.priority),
    effectiveFrom: draft.effectiveFrom,
    active: draft.active,
    conditions: parseConditions(draft.conditions) ?? {},
    legalEntityId,
    lines: draft.lines.map((line) => ({ side: line.side, account: line.account, amount: line.amount.trim() })),
  };
}
