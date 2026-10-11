import type { Role } from '@/shared/api/types';

/** A development user the UI can act as (dev and test profiles only, ADR 0026). */
export interface Persona {
  id: string;
  name: string;
  roles: Role[];
}

/**
 * The catalogue the API serves in `/api/v1/ui-config` (one source for the UI and the demo data). This copy is the
 * fallback until the configuration is read, and the fixture for unit tests.
 */
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

let catalogue: Persona[] = PERSONAS;

/** Uses the server's catalogue; an empty or missing list keeps the built-in one. */
export function setPersonas(personas: Persona[] | null | undefined): void {
  if (personas && personas.length > 0) catalogue = personas;
}

export function personas(): Persona[] {
  return catalogue;
}

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
  return catalogue.find((persona) => persona.id === id) ?? (catalogue[0] as Persona);
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
