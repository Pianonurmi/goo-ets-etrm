import { createContext, use } from 'react';
import type { Schema } from '@/shared/api/types';
import type { UiConfig } from './session';

export type Me = Schema<'MeView'>;

export interface Session {
  config: UiConfig;
  me: Me;
}

export const SessionContext = createContext<Session | null>(null);

export function useSession(): Session {
  const session = use(SessionContext);
  if (!session) throw new Error('useSession outside SessionContext');
  return session;
}
