import { useSyncExternalStore } from 'react';

/** The running tour and the tours finished, kept per browser (no server state). */
export interface ActiveTour {
  tour: string;
  step: number;
}

const ACTIVE = 'etrm.tour.active';
const DONE = 'etrm.tour.done';
const WELCOMED = 'etrm.welcomed';
const listeners = new Set<() => void>();

function read<T>(key: string, fallback: T): T {
  try {
    const value = window.localStorage.getItem(key);
    return value === null ? fallback : (JSON.parse(value) as T);
  } catch {
    return fallback;
  }
}

function write(key: string, value: unknown): void {
  try {
    if (value === null) window.localStorage.removeItem(key);
    else window.localStorage.setItem(key, JSON.stringify(value));
  } catch {
    // Storage may be refused (private windows); the tour then lasts until reload.
  }
}

let active: ActiveTour | null = read<ActiveTour | null>(ACTIVE, null);
let done: string[] = read<string[]>(DONE, []);

function emit() {
  listeners.forEach((listener) => listener());
}

function subscribe(listener: () => void) {
  listeners.add(listener);
  return () => listeners.delete(listener);
}

export function startTour(tour: string): void {
  active = { tour, step: 0 };
  write(ACTIVE, active);
  emit();
}

export function goToStep(step: number): void {
  if (!active) return;
  active = { ...active, step };
  write(ACTIVE, active);
  emit();
}

/** Ends the running tour; [finished] records it as done. */
export function endTour(finished: boolean): void {
  if (active && finished && !done.includes(active.tour)) {
    done = [...done, active.tour];
    write(DONE, done);
  }
  active = null;
  write(ACTIVE, null);
  emit();
}

export function useActiveTour(): ActiveTour | null {
  return useSyncExternalStore(subscribe, () => active);
}

export function useDoneTours(): string[] {
  return useSyncExternalStore(subscribe, () => done);
}

export function welcomed(): boolean {
  return read<boolean>(WELCOMED, false);
}

export function markWelcomed(): void {
  write(WELCOMED, true);
}
