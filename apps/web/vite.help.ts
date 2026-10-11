import { cpSync, existsSync, mkdirSync, readdirSync, readFileSync, statSync, writeFileSync } from 'node:fs';
import { extname, join, resolve, sep } from 'node:path';
import type { Plugin } from 'vite';
import { slugify } from './src/app/help/slug';

/**
 * The in-app manual: `docs/user-guide/*.md` and the screenshots in `docs/images` are content, not code, so they are
 * served as static files under `/help/` (copied into the bundle, where the jar serves them; served from the
 * repository by the dev server) and fetched when opened: `/help/pages/<id>.md`, `/help/images/<file>`, and
 * `/help/index.json`, the titles and headings the command palette searches.
 */
const TYPES: Record<string, string> = {
  '.md': 'text/markdown; charset=utf-8',
  '.json': 'application/json',
  '.png': 'image/png',
  '.svg': 'image/svg+xml',
  '.webp': 'image/webp',
};

export interface HelpIndexEntry {
  page: string;
  title: string;
  headings: { text: string; anchor: string }[];
}

export function helpIndex(guide: string): HelpIndexEntry[] {
  if (!existsSync(guide)) return [];
  return readdirSync(guide)
    .filter((file) => file.endsWith('.md'))
    .sort()
    .map((file) => {
      let title = file.replace(/\.md$/, '');
      const headings: HelpIndexEntry['headings'] = [];
      let code = false;
      for (const line of readFileSync(join(guide, file), 'utf8').split('\n')) {
        if (line.startsWith('```')) code = !code;
        const match = code ? null : /^(#{1,3})\s+(.+?)\s*$/.exec(line);
        if (!match?.[1] || !match[2]) continue;
        if (match[1] === '#') title = match[2];
        else headings.push({ text: match[2], anchor: slugify(match[2]) });
      }
      return { page: file.replace(/\.md$/, ''), title, headings };
    });
}

export function helpPlugin(repositoryRoot: string): Plugin {
  const guide = resolve(repositoryRoot, 'docs/user-guide');
  const images = resolve(repositoryRoot, 'docs/images');
  const serveFrom = (directory: string, url: string): string | null => {
    const file = resolve(directory, `.${decodeURIComponent(url.split('?')[0] ?? '/')}`);
    return file.startsWith(directory + sep) && existsSync(file) && statSync(file).isFile() ? file : null;
  };
  let outDir = '';
  return {
    name: 'etrm-help',
    configResolved(config) {
      outDir = resolve(config.root, config.build.outDir);
    },
    configureServer(server) {
      server.middlewares.use('/help', (request, response, next) => {
        const url = request.url ?? '/';
        let body: string | Buffer | null = null;
        let type = TYPES['.json'] ?? '';
        if (url.split('?')[0] === '/index.json') {
          body = JSON.stringify(helpIndex(guide));
        } else {
          const [directory, rest] = url.startsWith('/pages/')
            ? [guide, url.slice('/pages'.length)]
            : url.startsWith('/images/')
              ? [images, url.slice('/images'.length)]
              : [null, ''];
          const file = directory ? serveFrom(directory, rest) : null;
          if (file) {
            body = readFileSync(file);
            type = TYPES[extname(file)] ?? 'application/octet-stream';
          }
        }
        if (body === null) {
          next();
          return;
        }
        response.setHeader('Content-Type', type);
        response.setHeader('Cache-Control', 'no-cache');
        response.end(body);
      });
    },
    closeBundle() {
      if (!outDir || !existsSync(guide)) return;
      const help = join(outDir, 'help');
      mkdirSync(join(help, 'pages'), { recursive: true });
      readdirSync(guide)
        .filter((file) => file.endsWith('.md'))
        .forEach((file) => cpSync(join(guide, file), join(help, 'pages', file)));
      writeFileSync(join(help, 'index.json'), JSON.stringify(helpIndex(guide)));
      if (existsSync(images)) cpSync(images, join(help, 'images'), { recursive: true });
    },
  };
}
