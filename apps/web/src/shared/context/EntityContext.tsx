import { createContext, use, useMemo, useState, type ReactNode } from 'react';
import { $api } from '@/shared/api/client';
import type { Schema } from '@/shared/api/types';
import { useSession } from '@/shared/auth/SessionContext';

export type LegalEntity = Schema<'LegalEntityView'>;

interface EntityState {
  entities: LegalEntity[];
  /** The entity screens work on; null until reference data has an entity the user may see. */
  entity: LegalEntity | null;
  select: (id: string) => void;
}

const EntityContext = createContext<EntityState | null>(null);
const STORAGE_KEY = 'etrm.entity';

function stored(): string | null {
  try {
    return window.localStorage.getItem(STORAGE_KEY);
  } catch {
    return null;
  }
}

/** The legal entity selected in the header, limited to the user's scope. */
export function EntityProvider({ children }: { children: ReactNode }) {
  const { me } = useSession();
  const { data } = $api.useQuery('get', '/api/v1/reference/legal-entities');
  const [selected, setSelected] = useState<string | null>(stored);

  const value = useMemo<EntityState>(() => {
    const visible = (data ?? []).filter(
      (entity) => entity.active && (me.allEntities || me.legalEntityIds.includes(entity.id)),
    );
    const entity = visible.find((candidate) => candidate.id === selected) ?? visible[0] ?? null;
    return {
      entities: visible,
      entity,
      select: (id) => {
        setSelected(id);
        try {
          window.localStorage.setItem(STORAGE_KEY, id);
        } catch {
          // Storage may be unavailable; the selection then lasts for this page only.
        }
      },
    };
  }, [data, me, selected]);

  return <EntityContext value={value}>{children}</EntityContext>;
}

export function useEntity(): EntityState {
  const state = use(EntityContext);
  if (!state) throw new Error('useEntity outside EntityProvider');
  return state;
}
