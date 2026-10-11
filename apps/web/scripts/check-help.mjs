// The in-app manual (docs/user-guide, served at /help): every page the app links to exists, links between pages
// and to headings resolve, and screenshots exist and stay small (300 kB each, 12 MB together).
import { existsSync, readdirSync, readFileSync, statSync } from 'node:fs';
import { join } from 'node:path';

const root = new URL('../../../', import.meta.url).pathname;
const guide = join(root, 'docs/user-guide');
const images = join(root, 'docs/images');
const routes = readFileSync(new URL('../src/app/help/routes.ts', import.meta.url), 'utf8');
const pages = [...routes.match(/HELP_PAGES = \[([^\]]+)\]/)[1].matchAll(/'([a-z0-9-]+)'/g)].map((m) => m[1]);
const targets = [...routes.matchAll(/\{ page: '([a-z0-9-]+)'(?:, anchor: '([a-z0-9-]+)')? \}/g)].map((m) => [
  m[1],
  m[2],
]);

const slugify = (text) =>
  text
    .normalize('NFKD')
    .replace(/[̀-ͯ]/g, '')
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, '-')
    .replace(/^-+|-+$/g, '');

const problems = [];
const anchors = new Map();
const sources = new Map();
for (const page of pages) {
  const file = join(guide, `${page}.md`);
  if (!existsSync(file)) {
    problems.push(`missing page docs/user-guide/${page}.md`);
    continue;
  }
  const text = readFileSync(file, 'utf8');
  sources.set(page, text);
  let code = false;
  const found = new Set();
  for (const line of text.split('\n')) {
    if (line.startsWith('```')) code = !code;
    const heading = code ? null : /^#{2,3}\s+(.+?)\s*$/.exec(line);
    if (heading) found.add(slugify(heading[1]));
  }
  anchors.set(page, found);
}
for (const file of existsSync(guide) ? readdirSync(guide) : []) {
  if (file.endsWith('.md') && !pages.includes(file.slice(0, -3))) problems.push(`page ${file} is not in HELP_PAGES`);
}
for (const [page, anchor] of targets) {
  if (anchor && anchors.has(page) && !anchors.get(page).has(anchor))
    problems.push(`routes.ts: ${page}#${anchor} has no heading`);
}
let total = 0;
for (const [page, text] of sources) {
  for (const [, href] of text.matchAll(/(?<!!)\[[^\]]*\]\(([^)\s]+)\)/g)) {
    if (/^(https?:|mailto:)/.test(href)) continue;
    const match = /^(?:\.\/)?(?:([a-z0-9-]+)\.md)?(?:#([a-z0-9-]+))?$/.exec(href);
    const target = match?.[1] ?? page;
    if (!match || !anchors.has(target)) problems.push(`${page}.md: link ${href} goes nowhere`);
    else if (match[2] && !anchors.get(target).has(match[2])) problems.push(`${page}.md: link ${href} has no heading`);
  }
  for (const [, src] of text.matchAll(/!\[[^\]]*\]\(([^)\s]+)\)/g)) {
    const file = join(images, src.split('/').pop());
    if (!existsSync(file)) problems.push(`${page}.md: image ${src} is missing from docs/images`);
  }
}
for (const file of existsSync(images) ? readdirSync(images) : []) {
  const size = statSync(join(images, file)).size;
  total += size;
  if (size > 300 * 1024) problems.push(`docs/images/${file} is ${Math.round(size / 1024)} kB (limit 300 kB)`);
}
if (total > 12 * 1024 * 1024) problems.push(`docs/images holds ${Math.round(total / 1048576)} MB (limit 12 MB)`);

if (problems.length > 0) {
  problems.forEach((problem) => console.error(`help: ${problem}`));
  process.exit(1);
}
console.log(`help: ${pages.length} pages, ${targets.length} screen links, images ${Math.round(total / 1024)} kB`);
