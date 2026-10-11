/** Pure helpers of the home dashboard (display only; figures are computed by the server). */

export function greeting(hour: number): string {
  if (hour < 5 || hour >= 18) return 'home.greetingEvening';
  if (hour < 12) return 'home.greetingMorning';
  return 'home.greetingAfternoon';
}

/** The sign of a decimal the server sent, read from its text. */
export function isNegative(value: string | number): boolean {
  return String(value).trim().startsWith('-');
}
