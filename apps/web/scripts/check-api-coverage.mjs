// API coverage: every operation in the OpenAPI contract (ADR 0027) is reachable from the web UI, or is listed in
// api-only.json with a reason. The UI is how the system is meant to be used, so a new endpoint without a screen is
// a reviewed decision, not an accident. `planned` lists operations whose screen is scheduled; it must end empty.
//
// A call is found when a source file names the operation's path with its method:
//   $api.useQuery('get', '/api/v1/...'), $api.useMutation('post', '/api/v1/...'), api.GET('/api/v1/...'),
//   uploadText('/api/v1/...') (POST), download(`/api/v1/...`) (GET), authorisedFetch or fetch(`/api/v1/...`, { method }).
// Any other path literal (a typed path in a lookup table, a URL helper) counts when the contract has only one method
// for that path. Template expressions and path parameters both become `{}`; query strings are ignored.
import { readdirSync, readFileSync, statSync } from 'node:fs';
import { join, relative } from 'node:path';

const root = new URL('..', import.meta.url).pathname;
const spec = JSON.parse(readFileSync(join(root, '../api/openapi/etrm-api.json'), 'utf8'));
const allow = JSON.parse(readFileSync(join(root, 'api-only.json'), 'utf8'));
const listed = { ...allow.apiOnly, ...allow.planned };

const normalise = (path) =>
  path
    .split('?')[0]
    .replace(/\$\{[^}]*\}/g, '{}')
    .replace(/\{[^}]*\}/g, '{}')
    .replace(/\/$/, '');

const operations = Object.entries(spec.paths).flatMap(([path, methods]) =>
  Object.entries(methods)
    .filter(([method]) => ['get', 'post', 'put', 'patch', 'delete'].includes(method))
    .map(([method, op]) => ({ key: `${method.toUpperCase()} ${normalise(path)}`, id: op.operationId })),
);

function sources(dir) {
  return readdirSync(dir).flatMap((name) => {
    const path = join(dir, name);
    if (statSync(path).isDirectory()) return sources(path);
    if (!/\.(ts|tsx)$/.test(name) || /\.test\.tsx?$/.test(name) || name.endsWith('.d.ts')) return [];
    return [path];
  });
}

const PATH = String.raw`['"\`](\/api\/v1\/[^'"\`]*)['"\`]`;
const patterns = [
  {
    re: new RegExp(String.raw`\$api\.\w+\(\s*['"](get|post|put|patch|delete)['"]\s*,\s*` + PATH, 'g'),
    method: (m) => m[1],
  },
  { re: new RegExp(String.raw`\bapi\.(GET|POST|PUT|PATCH|DELETE)\(\s*` + PATH, 'g'), method: (m) => m[1] },
  { re: new RegExp(String.raw`\buploadText(?:<[^>]*>)?\(\s*` + PATH, 'g'), method: () => 'POST', path: 1 },
  { re: new RegExp(String.raw`\bdownload\(\s*` + PATH, 'g'), method: () => 'GET', path: 1 },
  {
    re: new RegExp(String.raw`\b(?:authorisedFetch|fetch)\(\s*` + PATH + String.raw`([\s\S]{0,300})`, 'g'),
    method: (m) => /method:\s*['"](\w+)['"]/.exec(m[2])?.[1] ?? 'GET',
    path: 1,
  },
];

const methodsByPath = new Map();
for (const op of operations) {
  const [method, path] = op.key.split(' ');
  methodsByPath.set(path, [...(methodsByPath.get(path) ?? []), method]);
}

const calls = new Map();
const record = (key, file) => {
  if (!calls.has(key)) calls.set(key, relative(root, file));
};
for (const file of sources(join(root, 'src'))) {
  const text = readFileSync(file, 'utf8');
  for (const { re, method, path = 2 } of patterns) {
    for (const match of text.matchAll(re)) record(`${method(match).toUpperCase()} ${normalise(match[path])}`, file);
  }
  for (const match of text.matchAll(new RegExp(PATH, 'g'))) {
    const methods = methodsByPath.get(normalise(match[1])) ?? [];
    if (methods.length === 1) record(`${methods[0]} ${normalise(match[1])}`, file);
  }
}

const problems = [];
const known = new Set(operations.map((op) => op.id));
for (const id of Object.keys(listed)) {
  if (!known.has(id)) problems.push(`api-only.json lists ${id}, which the contract does not have`);
}
let covered = 0;
for (const op of operations) {
  const called = calls.has(op.key);
  if (called) covered += 1;
  if (!called && !listed[op.id])
    problems.push(`${op.id} (${op.key}) has no UI caller: add a screen, or list it in api-only.json with a reason`);
  if (called && listed[op.id])
    problems.push(`${op.id} is called from ${calls.get(op.key)}: remove it from api-only.json`);
}

const planned = Object.keys(allow.planned ?? {}).length;
const apiOnly = Object.keys(allow.apiOnly ?? {}).length;
console.log(
  `API coverage: ${covered} of ${operations.length} operations called from the UI, ${apiOnly} API-only, ${planned} planned`,
);
if (problems.length) {
  problems.forEach((problem) => console.error(`  ${problem}`));
  process.exit(1);
}
