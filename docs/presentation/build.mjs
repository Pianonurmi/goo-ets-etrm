// Builds the presentation from docs/presentation/slides/*.html in the order of deck.json:
//   provena-deck.html  one page with every slide, speaker notes under each, arrow keys to move
//   provena-deck.pdf   one 1920 × 1080 page per slide, rendered by Chromium
// Run from apps/web (it supplies Playwright):  node ../../docs/presentation/build.mjs
// PW_CHROMIUM_PATH chooses the browser binary, as for the docs screenshots.
import { readFileSync, writeFileSync } from 'node:fs';
import { createRequire } from 'node:module';
import { dirname, join } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const require = createRequire(join(here, '../../apps/web/package.json'));
const { chromium } = require('playwright');

const deck = JSON.parse(readFileSync(join(here, 'deck.json'), 'utf8'));
const escape = (text) => text.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');

const frames = deck.order.map((id, i) => {
  const html = readFileSync(join(here, 'slides', `${id}.html`), 'utf8').trim();
  const notes = /<aside>([\s\S]*?)<\/aside>/.exec(html)?.[1]?.trim() ?? '';
  return `<div class="frame" id="slide-${i + 1}"><div class="stage">${html}</div></div>
<details class="notes"><summary>${i + 1} / ${deck.order.length} · Speaker notes</summary><p>${notes}</p></details>`;
});

const page = `<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>${escape(deck.title)}</title>
<link rel="stylesheet" href="https://fonts.googleapis.com/css2?family=Geist:wght@400..700&display=swap">
<style>
  :root { color-scheme: dark; }
  body { margin: 0; background: #05080f; color: #cbd5e1; font-family: Geist, Arial, sans-serif; }
  main { max-width: 1280px; margin: 0 auto; padding: 24px 16px 64px; display: flex; flex-direction: column; gap: 12px; }
  .frame { position: relative; width: 100%; aspect-ratio: 16 / 9; overflow: hidden; border-radius: 8px; }
  .stage { position: absolute; top: 0; left: 0; width: 1920px; height: 1080px; transform-origin: top left; }
  .stage section { position: relative; box-sizing: border-box; width: 1920px; height: 1080px; overflow: hidden; }
  .stage section * { margin: 0; box-sizing: border-box; }
  .stage section ul { list-style: disc; }
  .stage section aside { display: none; }
  .notes { margin-bottom: 28px; font-size: 15px; line-height: 1.5; }
  .notes summary { cursor: pointer; color: #94a3b8; }
  .notes p { margin: 8px 0 0; max-width: 72ch; }
  @page { size: 1920px 1080px; margin: 0; }
  @media print {
    body { background: none; }
    main { max-width: none; padding: 0; gap: 0; display: block; }
    .frame { width: 1920px; height: 1080px; aspect-ratio: auto; border-radius: 0; break-after: page; }
    .stage { transform: none !important; }
    .notes { display: none; }
  }
</style>
</head>
<body>
<main>
${frames.join('\n')}
</main>
<script>
  const fit = () => document.querySelectorAll('.frame').forEach((frame) => {
    frame.querySelector('.stage').style.transform = 'scale(' + frame.clientWidth / 1920 + ')';
  });
  addEventListener('resize', fit);
  fit();
  addEventListener('keydown', (event) => {
    const step = { ArrowRight: 1, PageDown: 1, ' ': 1, ArrowLeft: -1, PageUp: -1 }[event.key];
    if (!step) return;
    const frames = [...document.querySelectorAll('.frame')];
    const current = frames.findIndex((frame) => frame.getBoundingClientRect().top > -frame.clientHeight / 2);
    frames[Math.max(0, Math.min(frames.length - 1, current + step))]?.scrollIntoView({ behavior: 'smooth' });
    event.preventDefault();
  });
</script>
</body>
</html>
`;

const htmlPath = join(here, 'provena-deck.html');
writeFileSync(htmlPath, page);

const browser = await chromium.launch({ executablePath: process.env.PW_CHROMIUM_PATH || undefined });
const tab = await browser.newPage();
await tab.goto(pathToFileURL(htmlPath).href, { waitUntil: 'networkidle' });
await tab.evaluate(() => document.fonts.ready);
await tab.pdf({ path: join(here, 'provena-deck.pdf'), width: '1920px', height: '1080px', printBackground: true });
await browser.close();
console.log(`${deck.order.length} slides: provena-deck.html, provena-deck.pdf`);
