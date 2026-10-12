import { expect, test, type Page } from '@playwright/test';
import { signIn } from '../fixtures';

/**
 * The pictures of the manual and the README, written to docs/images/<area>-<screen>.png. Each shot names the
 * persona it is taken as and where; detail pages open the first record of their list. Review the images by eye
 * before committing them (docs/ui/testing.md).
 */
const OUT = '../../docs/images';

interface Shot {
  name: string;
  persona: string;
  path: string;
  /** Opens a record from the list: clicks the first grid row (or the given selector) and waits for the URL. */
  open?: { selector?: string; url: RegExp };
  /** Something to do before the picture, e.g. open a tab or a drawer. */
  then?: (page: Page) => Promise<void>;
  dark?: boolean;
}

// The first data cell of the first grid on the page (header cells are column headers, not grid cells).
const firstRow = '[role="grid"] [role="row"] [role="gridcell"]';

const SHOTS: Shot[] = [
  { name: 'home-trader', persona: 'trader', path: '/' },
  { name: 'home-dark', persona: 'trader', path: '/', dark: true },
  { name: 'home-accountant', persona: 'accountant', path: '/' },
  { name: 'inbox-middle', persona: 'middle', path: '/inbox' },
  { name: 'trades-blotter', persona: 'trader', path: '/trades' },
  { name: 'trades-deliveries', persona: 'backoffice', path: '/trades?view=deliveries' },
  { name: 'trades-new', persona: 'trader', path: '/trades/new' },
  { name: 'trades-detail', persona: 'middle', path: '/trades', open: { url: /\/trades\/[0-9a-f-]{36}$/ } },
  { name: 'confirmations-list', persona: 'backoffice', path: '/confirmations' },
  { name: 'inventory-lots', persona: 'backoffice', path: '/inventory' },
  {
    name: 'inventory-search',
    persona: 'backoffice',
    path: '/inventory?tab=search',
    then: (page) => page.getByTestId('run-search').click(),
  },
  {
    name: 'inventory-lot',
    persona: 'backoffice',
    path: '/inventory',
    open: { url: /\/inventory\/lots\/[0-9a-f-]{36}$/ },
  },
  { name: 'allocation-workbench', persona: 'trader', path: '/allocation' },
  { name: 'positions-goo', persona: 'trader', path: '/positions' },
  {
    name: 'positions-headroom',
    persona: 'trader',
    path: '/positions',
    then: async (page) => {
      await page.getByTestId('goo-view').getByText('Available to sell').click();
      await expect(page.getByTestId('headroom-table')).toBeVisible({ timeout: 60_000 });
    },
  },
  {
    name: 'positions-products',
    persona: 'trader',
    path: '/positions',
    then: async (page) => {
      await page.getByTestId('goo-view').getByText('Products').click();
      await expect(page.getByTestId('goo-hierarchy')).toBeVisible({ timeout: 60_000 });
    },
  },
  {
    name: 'positions-future',
    persona: 'trader',
    path: '/positions',
    then: async (page) => {
      await page.getByTestId('goo-view').getByText('Future supply').click();
      await expect(page.getByTestId('future-supply-kpis')).toBeVisible({ timeout: 60_000 });
    },
  },
  { name: 'registry-instructions', persona: 'backoffice', path: '/registry' },
  {
    name: 'registry-instruction',
    persona: 'backoffice',
    path: '/registry',
    open: { url: /\/registry\/instructions\/[0-9a-f-]{36}$/ },
  },
  { name: 'reconciliation-breaks', persona: 'backoffice', path: '/reconciliation' },
  { name: 'marketdata-quotes', persona: 'risk', path: '/market-data' },
  { name: 'marketdata-curves', persona: 'risk', path: '/market-data/curves' },
  { name: 'valuation-runs', persona: 'risk', path: '/valuation' },
  {
    name: 'valuation-portfolio',
    persona: 'risk',
    path: '/valuation',
    open: { url: /\/valuation\/runs\/[0-9a-f-]{36}/ },
    then: async (page) => {
      await page.getByTestId('portfolio-tab').click();
      await expect(page.getByTestId('portfolio-bridge')).toBeVisible({ timeout: 60_000 });
    },
  },
  { name: 'risk-limits', persona: 'risk', path: '/risk' },
  { name: 'ets-overview', persona: 'middle', path: '/ets' },
  { name: 'settlement-items', persona: 'accountant', path: '/settlement' },
  { name: 'settlement-invoices', persona: 'accountant', path: '/settlement?tab=invoices' },
  {
    name: 'settlement-invoice',
    persona: 'accountant',
    path: '/settlement?tab=invoices',
    open: { url: /\/settlement\/invoices\/[0-9a-f-]{36}$/ },
  },
  { name: 'ledger-entries', persona: 'accountant', path: '/ledger' },
  { name: 'monthend-close', persona: 'accountant', path: '/month-end' },
  { name: 'reports-catalogue', persona: 'accountant', path: '/reports' },
  { name: 'admin-reference', persona: 'middle', path: '/admin/reference' },
  { name: 'audit-trail', persona: 'auditor', path: '/audit' },
  { name: 'guide', persona: 'trader', path: '/guide' },
  {
    name: 'help-drawer',
    persona: 'accountant',
    path: '/month-end',
    then: async (page) => {
      // The blurred backdrop makes a PNG over the 300 kB limit; the dimming alone shows the drawer just as well.
      await page.addStyleTag({ content: '.mantine-Drawer-overlay { backdrop-filter: none !important; }' });
      await page.getByTestId('help-button').click();
      await expect(page.getByTestId('help-content')).toBeVisible();
    },
  },
  {
    name: 'tour-step',
    persona: 'trader',
    path: '/guide',
    then: async (page) => {
      await page.getByTestId('guide-start-trade').click();
      await page.getByTestId('tour-next').click();
      await page.getByTestId('tour-next').click();
      await expect(page.getByTestId('tour-card')).toBeVisible();
    },
  },
];

/** Lets data load and charts draw. The app polls (inbox count), so the network is never idle for long: wait a little. */
async function settle(page: Page) {
  await page.waitForLoadState('networkidle', { timeout: 5_000 }).catch(() => undefined);
  await page.waitForTimeout(800);
}

test('the sign-in page', async ({ page }) => {
  await page.goto('/');
  await expect(page.getByTestId('login-submit')).toBeVisible();
  await settle(page);
  await page.screenshot({ path: `${OUT}/showcase-login.png`, animations: 'disabled', caret: 'hide' });
});

test('every screen of the manual', async ({ page }) => {
  test.setTimeout(900_000);
  await signIn(page);
  await expect(page.getByTestId('persona-menu')).toBeVisible();
  for (const shot of SHOTS) {
    await test.step(shot.name, async () => {
      await page.emulateMedia({ colorScheme: shot.dark ? 'dark' : 'light' });
      await page.evaluate(
        ({ persona, dark }) => {
          window.localStorage.setItem('etrm.persona', persona);
          window.localStorage.setItem('mantine-color-scheme-value', dark ? 'dark' : 'light');
        },
        { persona: shot.persona, dark: Boolean(shot.dark) },
      );
      await page.goto(shot.path);
      await settle(page);
      if (shot.open) {
        await page
          .locator(shot.open.selector ?? firstRow)
          .first()
          .click({ timeout: 30_000 });
        await expect(page).toHaveURL(shot.open.url);
        await settle(page);
      }
      if (shot.then) {
        await shot.then(page);
        await settle(page);
      }
      await page.screenshot({ path: `${OUT}/${shot.name}.png`, animations: 'disabled', caret: 'hide' });
    });
  }
});
