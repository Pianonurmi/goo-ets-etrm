/** Where a reset is, read from `ui-config` while the server restarts (pure, for the progress dialog). */
export type ResetPhase =
  { kind: 'stopping' } | { kind: 'starting' } | { kind: 'seeding'; done: number; total: number } | { kind: 'done' };

interface SeedLike {
  demoSeed?: { state: string; completedSteps: number; stepCount: number; finishedAt?: string | null } | null;
  showcase?: { resetRequestedAt?: string | null } | null;
}

export function resetPhase(config: SeedLike | null, requestedAt: string): ResetPhase {
  // Unreachable (restarting) or not yet answering.
  if (!config) return { kind: 'starting' };
  // Still the old process: it remembers the request.
  if (config.showcase?.resetRequestedAt === requestedAt) return { kind: 'stopping' };
  const seed = config.demoSeed;
  if (!seed || seed.state === 'RUNNING')
    return { kind: 'seeding', done: seed?.completedSteps ?? 0, total: seed?.stepCount ?? 0 };
  if (seed.state === 'COMPLETED' && seed.finishedAt && Date.parse(seed.finishedAt) > Date.parse(requestedAt))
    return { kind: 'done' };
  return { kind: 'starting' };
}
