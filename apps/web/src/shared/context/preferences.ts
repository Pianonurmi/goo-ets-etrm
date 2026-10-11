import { useSyncExternalStore } from 'react';

/** Display preferences kept per browser (ADR 0034): density and the collapsed sidebar. */
export type Density = 'comfortable' | 'compact';

const listeners = new Set<() => void>();

function read(key: string): string | null {
  try {
    return window.localStorage.getItem(key);
  } catch {
    return null;
  }
}

function write(key: string, value: string): void {
  try {
    window.localStorage.setItem(key, value);
  } catch {
    // Private windows may refuse storage; the choice then lasts until reload.
  }
  listeners.forEach((listener) => listener());
}

function subscribe(listener: () => void): () => void {
  listeners.add(listener);
  return () => listeners.delete(listener);
}

const DENSITY_KEY = 'etrm.density';
const SIDEBAR_KEY = 'etrm.sidebar';

export function density(): Density {
  return read(DENSITY_KEY) === 'compact' ? 'compact' : 'comfortable';
}

/** Applies the density to the document: rem-based sizes shrink a step in compact mode. */
export function applyDensity(value: Density = density()): void {
  document.documentElement.dataset.density = value;
}

export function setDensity(value: Density): void {
  applyDensity(value);
  write(DENSITY_KEY, value);
}

export function useDensity(): Density {
  return useSyncExternalStore(subscribe, density, () => 'comfortable');
}

export function sidebarCollapsed(): boolean {
  return read(SIDEBAR_KEY) === 'collapsed';
}

export function setSidebarCollapsed(collapsed: boolean): void {
  write(SIDEBAR_KEY, collapsed ? 'collapsed' : 'expanded');
}

export function useSidebarCollapsed(): boolean {
  return useSyncExternalStore(subscribe, sidebarCollapsed, () => false);
}
