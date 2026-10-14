import openApi from '../../../api/openapi/etrm-api.json';
import en from './en.json';
import tour from './tour.en.json';

const sources = import.meta.glob<string>(['/src/**/*.{ts,tsx}', '!/src/**/*.test.*', '!/src/shared/api/schema.d.ts'], {
  query: '?raw',
  import: 'default',
  eager: true,
});

function flatten(node: unknown, prefix = '', into = new Set<string>()): Set<string> {
  if (typeof node === 'string') {
    into.add(prefix);
  } else if (typeof node === 'object' && node !== null) {
    Object.entries(node).forEach(([key, child]) => flatten(child, prefix ? `${prefix}.${key}` : key, into));
  }
  return into;
}

const keys = flatten(en);
const tourKeys = flatten(tour);
const PLURALS = ['_zero', '_one', '_two', '_few', '_many', '_other'];
const has = (catalogue: Set<string>, key: string) =>
  catalogue.has(key) || PLURALS.some((suffix) => catalogue.has(key + suffix));

/** `t('a.b')` and `i18n.t('a.b')` with a plain string literal; keys built from a template are not checked. */
const STATIC_CALL = /(?<![\w$])t\(\s*'([A-Za-z][\w-]*(?:\.[\w-]+)+)'/g;

describe('translation catalogue', () => {
  it('has every key used through a static t() call', () => {
    const missing: string[] = [];
    Object.entries(sources).forEach(([file, text]) => {
      for (const match of text.matchAll(STATIC_CALL)) {
        const key = match[1];
        // Tour and guide components read their own namespace.
        const catalogue = text.includes("useTranslation('tour')") ? tourKeys : keys;
        if (key && !has(catalogue, key)) missing.push(`${file.replace('/src/', '')}: ${key}`);
      }
    });
    expect(missing).toEqual([]);
  });

  it('finds the calls it is meant to check', () => {
    const count = Object.values(sources).reduce((sum, text) => sum + [...text.matchAll(STATIC_CALL)].length, 0);
    expect(count).toBeGreaterThan(500);
  });

  it('has a status label for every constant the API defines', () => {
    const constants = new Set<string>();
    const visit = (node: unknown) => {
      if (Array.isArray(node)) node.forEach(visit);
      else if (typeof node === 'object' && node !== null) {
        const record = node as Record<string, unknown>;
        if (Array.isArray(record.enum)) {
          record.enum.forEach((value) => {
            if (typeof value === 'string' && /^[A-Z][A-Z0-9_]*$/.test(value)) constants.add(value);
          });
        }
        Object.values(record).forEach(visit);
      }
    };
    visit(openApi);
    const missing = [...constants].filter((constant) => !keys.has(`status.${constant}`)).sort();
    expect(constants.size).toBeGreaterThan(300);
    expect(missing).toEqual([]);
  });
});
