import { choosePersona, currentPersona, personaHeaders, PERSONAS } from './personas';

describe('personas', () => {
  beforeEach(() => window.localStorage.clear());

  it('offers two users for every approving role', () => {
    for (const role of ['TRADER', 'MIDDLE_OFFICE', 'ACCOUNTANT', 'RISK'] as const) {
      expect(PERSONAS.filter((persona) => persona.roles.includes(role)).length).toBeGreaterThanOrEqual(2);
    }
  });

  it('remembers the chosen persona and sends it as dev headers', () => {
    expect(currentPersona().id).toBe('trader');
    choosePersona('middle2');
    const persona = currentPersona();
    expect(persona.id).toBe('middle2');
    expect(personaHeaders(persona)).toEqual({ 'X-Actor': 'middle2', 'X-Roles': 'MIDDLE_OFFICE' });
  });

  it('falls back to the first persona for an unknown id', () => {
    choosePersona('nobody');
    expect(currentPersona()).toEqual(PERSONAS[0]);
  });
});
