// Bundle budget (ADR 0025): the first page (entry, preloaded chunks and CSS) pays only for the shell; screens load
// lazily. The budgets sit a little above today's sizes, so growth is a reviewed decision. Fails the build when a
// budget is exceeded. Sizes are gzip, as served (response compression is on).
import { readdirSync, readFileSync } from 'node:fs';
import { join } from 'node:path';
import { gzipSync } from 'node:zlib';

const BUDGET_KB = { entry: 330, chunk: 360, total: 1000 };
const dist = new URL('../build/dist/', import.meta.url).pathname;
const assets = join(dist, 'assets');
const kb = (bytes) => Math.round(bytes / 102.4) / 10;

const sizes = readdirSync(assets)
  .filter((name) => name.endsWith('.js') || name.endsWith('.css'))
  .map((name) => ({ name, gzip: gzipSync(readFileSync(join(assets, name))).length }));
const html = readFileSync(join(dist, 'index.html'), 'utf8');
const entryNames = [...html.matchAll(/\/assets\/([^"]+\.(?:js|css))"/g)].map((match) => match[1]);
const entry = sizes.filter((size) => entryNames.includes(size.name)).reduce((sum, size) => sum + size.gzip, 0);
const largest = sizes.reduce((max, size) => (size.gzip > max.gzip ? size : max), { name: '-', gzip: 0 });
const total = sizes.reduce((sum, size) => sum + size.gzip, 0);

const checks = [
  ['first page (entry js and css)', kb(entry), BUDGET_KB.entry],
  [`largest chunk (${largest.name})`, kb(largest.gzip), BUDGET_KB.chunk],
  [`all ${sizes.length} chunks`, kb(total), BUDGET_KB.total],
];
let failed = false;
for (const [what, size, budget] of checks) {
  const ok = size <= budget;
  failed ||= !ok;
  console.log(`${ok ? 'ok  ' : 'OVER'} ${what}: ${size} kB of ${budget} kB (gzip)`);
}
if (failed) {
  console.error('Bundle budget exceeded: lazy-load the new dependency or raise the budget in a reviewed change.');
  process.exit(1);
}
