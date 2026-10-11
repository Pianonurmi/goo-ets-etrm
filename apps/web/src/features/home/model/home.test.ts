import { eventLabel, greeting, isNegative, orderKpis, type KpiKey } from './home';

describe('home model', () => {
  it('greets by the time of day', () => {
    expect(greeting(8)).toBe('home.greetingMorning');
    expect(greeting(14)).toBe('home.greetingAfternoon');
    expect(greeting(21)).toBe('home.greetingEvening');
    expect(greeting(3)).toBe('home.greetingEvening');
  });

  it('reads the sign of a decimal', () => {
    expect(isNegative('-12.50')).toBe(true);
    expect(isNegative('0.00')).toBe(false);
    expect(isNegative(3)).toBe(false);
  });

  const all: KpiKey[] = ['tasks', 'coverage', 'mtm', 'eua', 'breaks', 'overdue', 'risk', 'trades', 'live'];

  it('leads with what the role looks at first', () => {
    expect(orderKpis(all, ['TRADER']).slice(0, 4)).toEqual(['mtm', 'coverage', 'eua', 'trades']);
    expect(orderKpis(all, ['MIDDLE_OFFICE']).slice(0, 4)).toEqual(['tasks', 'trades', 'risk', 'coverage']);
    expect(orderKpis(all, ['ACCOUNTANT']).slice(0, 3)).toEqual(['tasks', 'overdue', 'mtm']);
    expect(orderKpis(all, ['RISK'])[0]).toBe('risk');
    expect(orderKpis(all, ['AUDITOR']).at(-1)).toBe('tasks');
  });

  it('takes the best rank across several roles and keeps only what is available', () => {
    const ordered = orderKpis(['live', 'breaks', 'mtm', 'tasks'], ['TRADER', 'BACK_OFFICE']);
    expect(ordered).toEqual(['tasks', 'mtm', 'breaks', 'live']);
    expect(orderKpis(['risk', 'tasks'], ['ADMIN'])).toEqual(['tasks', 'risk']);
  });

  it('turns an event type into words', () => {
    expect(eventLabel('TradeApproved')).toBe('Trade approved');
    expect(eventLabel('PostingRuleChangeRequested')).toBe('Posting rule change requested');
    expect(eventLabel('ETS_DEADLINE_MET')).toBe('Ets deadline met');
  });
});
