import { HELP_PAGES, helpFor, helpImage, isHelpPage, resolveHelpLink } from './routes';
import { slugify } from './slug';

describe('help routing', () => {
  it('picks the most specific page for a screen', () => {
    expect(helpFor('/market-data/curves')).toEqual({ page: 'market-data', anchor: 'curves' });
    expect(helpFor('/market-data')).toEqual({ page: 'market-data' });
    expect(helpFor('/trades/new')).toEqual({ page: 'trading', anchor: 'capture-a-trade' });
    expect(helpFor('/trades/0193')).toEqual({ page: 'trading' });
    expect(helpFor('/admin/demo')).toEqual({ page: 'demo', anchor: 'resetting-the-demo' });
    expect(helpFor('/clearing')).toEqual({ page: 'clearing' });
    expect(helpFor('/clearing/breaks/0193')).toEqual({ page: 'clearing' });
    expect(helpFor('/')).toEqual({ page: 'getting-started' });
    expect(helpFor('/tradesx')).toEqual({ page: 'getting-started' });
  });

  it('resolves links between pages, anchors and external sites', () => {
    expect(resolveHelpLink('trading.md#capture-a-trade', 'ets')).toEqual({
      kind: 'internal',
      to: '/help/trading#capture-a-trade',
    });
    expect(resolveHelpLink('./risk.md', 'ets')).toEqual({ kind: 'internal', to: '/help/risk' });
    expect(resolveHelpLink('#deadlines', 'ets')).toEqual({ kind: 'internal', to: '/help/ets#deadlines' });
    expect(resolveHelpLink('https://example.org', 'ets').kind).toBe('external');
  });

  it('serves images from /help/images and knows its pages', () => {
    expect(helpImage('../images/trades-blotter.png')).toBe('/help/images/trades-blotter.png');
    expect(isHelpPage('trading')).toBe(true);
    expect(isHelpPage('nope')).toBe(false);
    expect(new Set(HELP_PAGES).size).toBe(HELP_PAGES.length);
  });

  it('slugs headings the same way everywhere', () => {
    expect(slugify('Capture a trade')).toBe('capture-a-trade');
    expect(slugify('Month end: soft and hard close')).toBe('month-end-soft-and-hard-close');
    expect(slugify('Käyttö – öljy')).toBe('kaytto-oljy');
  });
});
