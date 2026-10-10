import type { Role } from '@/shared/api/types';

/** A development user the UI can act as (dev and test profiles only, ADR 0026). */
export interface Persona {
  id: string;
  name: string;
  roles: Role[];
}

/** Two users per approving role, so four-eyes paths can be walked through in the browser. */
export const PERSONAS: Persona[] = [
  { id: 'trader', name: 'Tia Trader', roles: ['TRADER'] },
  { id: 'trader2', name: 'Theo Trader', roles: ['TRADER'] },
  { id: 'middle', name: 'Mia Middle', roles: ['MIDDLE_OFFICE'] },
  { id: 'middle2', name: 'Max Middle', roles: ['MIDDLE_OFFICE'] },
  { id: 'backoffice', name: 'Bo Backoffice', roles: ['BACK_OFFICE', 'REGISTRY_OPERATOR'] },
  { id: 'accountant', name: 'Ada Accountant', roles: ['ACCOUNTANT'] },
  { id: 'controller', name: 'Cleo Controller', roles: ['ACCOUNTANT'] },
  { id: 'risk', name: 'Rhea Risk', roles: ['RISK'] },
  { id: 'risk2', name: 'Rafe Risk', roles: ['RISK'] },
  { id: 'auditor', name: 'Aud Itor', roles: ['AUDITOR'] },
  { id: 'admin', name: 'Adam Admin', roles: ['ADMIN'] },
];

const STORAGE_KEY = 'etrm.persona';
const DEFAULT_PERSONA = 'trader';

function read(key: string): string | null {
  try {
    return window.localStorage.getItem(key);
  } catch {
    return null;
  }
}

export function currentPersona(): Persona {
  const id = read(STORAGE_KEY) ?? DEFAULT_PERSONA;
  return PERSONAS.find((persona) => persona.id === id) ?? (PERSONAS[0] as Persona);
}

export function choosePersona(id: string): void {
  try {
    window.localStorage.setItem(STORAGE_KEY, id);
  } catch {
    // Private windows may refuse storage; the choice then lasts until reload.
  }
}

/** The dev headers the API reads in the dev and test profiles (`HeaderActorResolver`). */
export function personaHeaders(persona: Persona): Record<string, string> {
  return { 'X-Actor': persona.id, 'X-Roles': persona.roles.join(',') };
}
