import { describe, expect, it } from 'vitest';
import { matchHelp, matchScreens, searchesTrades } from './palette';

const screens = [
  { path: '/', label: 'Home' },
  { path: '/trades', label: 'Trades', section: 'Trading' },
  { path: '/market-data', label: 'Market data', section: 'Trading' },
  { path: '/ets', label: 'EU ETS compliance', section: 'Finance and risk' },
  { path: '/admin/taxonomy', label: 'Taxonomy', section: 'Administration' },
];

describe('command palette', () => {
  it('lists every screen for an empty query, up to the limit', () => {
    expect(matchScreens(screens, '  ').map((s) => s.path)).toEqual([
      '/',
      '/trades',
      '/market-data',
      '/ets',
      '/admin/taxonomy',
    ]);
    expect(matchScreens(screens, '', 2)).toHaveLength(2);
  });

  it('matches every word in label or section, prefix matches first', () => {
    expect(matchScreens(screens, 'tra').map((s) => s.path)).toEqual(['/trades', '/market-data', '/admin/taxonomy']);
    expect(matchScreens(screens, 'ets comp').map((s) => s.path)).toEqual(['/ets']);
    expect(matchScreens(screens, 'admin').map((s) => s.path)).toEqual(['/admin/taxonomy']);
    expect(matchScreens(screens, 'xyz')).toEqual([]);
  });

  it('ignores case and accents', () => {
    expect(matchScreens([{ path: '/x', label: 'Lämpö' }], 'LAMPO')).toHaveLength(1);
  });

  it('searches trades from two characters', () => {
    expect(searchesTrades('T')).toBe(false);
    expect(searchesTrades(' TR ')).toBe(true);
  });

  it('finds help headings and page titles from two characters, titles first', () => {
    const index = [
      { page: 'trading', title: 'Trading', headings: [{ text: 'Capture a trade', anchor: 'capture-a-trade' }] },
      { page: 'risk', title: 'Risk', headings: [{ text: 'Limits and breaches', anchor: 'limits-and-breaches' }] },
    ];
    expect(matchHelp(index, 'c')).toEqual([]);
    expect(matchHelp(index, 'capture')).toEqual([
      { path: '/help/trading#capture-a-trade', label: 'Capture a trade', page: 'Trading' },
    ]);
    expect(matchHelp(index, 'risk').map((hit) => hit.path)).toEqual(['/help/risk', '/help/risk#limits-and-breaches']);
  });
});
