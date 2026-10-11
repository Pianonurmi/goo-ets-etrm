import { useQuery } from '@tanstack/react-query';
import type { HelpPageId } from './routes';

/** A heading of a help page, as the build indexes it (vite.help.ts). */
export interface HelpIndexEntry {
  page: string;
  title: string;
  headings: { text: string; anchor: string }[];
}

async function text(path: string): Promise<string> {
  const response = await fetch(path, { headers: { Accept: 'text/markdown, application/json' } });
  if (!response.ok) throw new Error(`${path}: ${response.status}`);
  return response.text();
}

/** The manual is content served as static files (`/help/pages`, `/help/index.json`), not part of the code bundle. */
export function useHelpPage(id: HelpPageId) {
  return useQuery({ queryKey: ['help', 'page', id], queryFn: () => text(`/help/pages/${id}.md`), staleTime: Infinity });
}

export function useHelpIndex(enabled = true) {
  return useQuery({
    queryKey: ['help', 'index'],
    queryFn: async () => JSON.parse(await text('/help/index.json')) as HelpIndexEntry[],
    staleTime: Infinity,
    enabled,
  });
}
