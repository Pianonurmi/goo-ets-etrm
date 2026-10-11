import { greeting, isNegative } from './home';

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
});
