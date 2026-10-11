/**
 * Heading anchors of the help pages: lower case words joined by hyphens. The renderer and the build-time search
 * index (vite.config.ts) use this one function, so a search result lands on its heading.
 */
export function slugify(text: string): string {
  return text
    .normalize('NFKD')
    .replace(/[̀-ͯ]/g, '')
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, '-')
    .replace(/^-+|-+$/g, '');
}
