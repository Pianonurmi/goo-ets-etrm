import { act, renderHook } from '@testing-library/react';
import type { ReactNode } from 'react';
import { MemoryRouter, useLocation } from 'react-router';
import { useUrlState, useUrlTab } from './useUrlState';

function wrapper(initial: string) {
  return ({ children }: { children: ReactNode }) => <MemoryRouter initialEntries={[initial]}>{children}</MemoryRouter>;
}

describe('useUrlState', () => {
  it('sets, repeats and removes keys and keeps the ones the patch leaves out', () => {
    const { result } = renderHook(() => ({ state: useUrlState(), location: useLocation() }), {
      wrapper: wrapper('/x?keep=1&drop=2&status=A'),
    });
    act(() => result.current.state.set({ drop: null, status: ['B', 'C'], added: 'yes', keep: undefined, empty: '' }));
    const params = new URLSearchParams(result.current.location.search);
    expect(params.get('keep')).toBe('1');
    expect(params.has('drop')).toBe(false);
    expect(params.getAll('status')).toEqual(['B', 'C']);
    expect(params.get('added')).toBe('yes');
    expect(params.has('empty')).toBe(false);
  });
});

describe('useUrlTab', () => {
  it('reads a known tab, falls back otherwise, and starts the query afresh on a switch', () => {
    const { result } = renderHook(() => ({ tab: useUrlTab(['one', 'two'] as const, 'one'), location: useLocation() }), {
      wrapper: wrapper('/x?tab=nope&filter=1'),
    });
    expect(result.current.tab[0]).toBe('one');
    act(() => result.current.tab[1]('two'));
    expect(result.current.location.search).toBe('?tab=two');
    act(() => result.current.tab[1]('one'));
    expect(result.current.location.search).toBe('');
  });
});
