// Load test of the main reads and writes (docs/operations/load-testing.md).
//
//   k6 run -e BASE_URL=http://localhost:8080 ops/load/etrm.js
//
// Against the dev profile the script acts through the X-Actor / X-Roles headers. Against a secured environment pass
// TOKEN (a bearer token of a user with TRADER, MIDDLE_OFFICE and BACK_OFFICE). It needs the demo data or an
// equivalent: a legal entity with a book, a counterparty and open obligations.
import http from 'k6/http';
import { check, fail, group, sleep } from 'k6';
import { Trend } from 'k6/metrics';

const BASE = __ENV.BASE_URL || 'http://localhost:8080';
const PROFILE = __ENV.PROFILE || 'baseline';

const profiles = {
  // Smoke: one user each, to check the script against an environment.
  smoke: { readers: 1, writers: 1, planners: 1, batch: 1, duration: '30s', think: 0 },
  // Baseline: the expected peak of a trading desk and its operations team, each user pausing a second between actions.
  baseline: { readers: 20, writers: 5, planners: 2, batch: 1, duration: '5m', think: 1 },
  // Stress: four times the baseline users and no pauses, to find where latency bends.
  stress: { readers: 80, writers: 20, planners: 4, batch: 2, duration: '10m', think: 0 },
};
const p = profiles[PROFILE] || fail(`unknown PROFILE ${PROFILE}`);
const duration = __ENV.DURATION || p.duration;

export const options = {
  scenarios: {
    reads: { executor: 'constant-vus', vus: p.readers, duration, exec: 'reads' },
    trades: { executor: 'constant-vus', vus: p.writers, duration, exec: 'captureTrade' },
    allocation: { executor: 'constant-vus', vus: p.planners, duration, exec: 'whatIf' },
    // Month-end work while the desk trades (review finding O9): valuation, period end, reports and exports.
    batch: { executor: 'constant-vus', vus: p.batch, duration, exec: 'batch' },
  },
  // Service levels; the run fails when one is missed (exit code 99).
  thresholds: {
    http_req_failed: ['rate<0.01'],
    'http_req_duration{kind:read}': ['p(95)<300', 'p(99)<800'],
    'http_req_duration{kind:write}': ['p(95)<800', 'p(99)<2000'],
    'http_req_duration{kind:solve}': ['p(95)<3000'],
    // Available to sell (ADR 0036): a trial on prepared figures; the first call per entity prepares them.
    'http_req_duration{kind:check}': ['p(95)<150'],
    // Batch work runs over the whole book of the entity: a valuation, the period end, a report or an export.
    'http_req_duration{kind:batch}': ['p(95)<10000'],
    checks: ['rate>0.99'],
  },
};

const tradeCapture = new Trend('etrm_trade_capture', true);

function headers(roles) {
  const h = { 'Content-Type': 'application/json' };
  if (__ENV.TOKEN) h.Authorization = `Bearer ${__ENV.TOKEN}`;
  else {
    h['X-Actor'] = __ENV.ACTOR || `load-${__VU}`;
    h['X-Roles'] = roles;
  }
  return h;
}

const READER = headers('MIDDLE_OFFICE,BACK_OFFICE,RISK,AUDITOR');
const TRADER = headers('TRADER');
const RISK = headers('RISK');
const ACCOUNTANT = headers('ACCOUNTANT');
const OPERATIONS = headers('BACK_OFFICE');

function get(path, name) {
  const res = http.get(`${BASE}${path}`, { headers: READER, tags: { kind: 'read', name } });
  check(res, { [`${name} 200`]: (r) => r.status === 200 });
  return res;
}

export function setup() {
  const entities = http.get(`${BASE}/api/v1/reference/legal-entities`, { headers: READER }).json();
  if (!Array.isArray(entities) || entities.length === 0) fail('no legal entities: load the demo data first');
  const entity = entities.find((e) => e.code === (__ENV.ENTITY || e.code));
  const books = http.get(`${BASE}/api/v1/reference/legal-entities/${entity.id}/books`, { headers: READER }).json();
  const book = books.find((b) => b.active && b.bookType === 'TRADING') || books.find((b) => b.active);
  const counterparties = http.get(`${BASE}/api/v1/reference/counterparties`, { headers: READER }).json();
  const counterparty = counterparties.find((c) => c.active && !c.linkedLegalEntityId);
  if (!book || !counterparty) fail('the entity needs an active book and an external counterparty');
  return { entityId: entity.id, bookId: book.id, counterpartyId: counterparty.id, year: new Date().getUTCFullYear() };
}

export function reads(ctx) {
  group('reads', () => {
    get(`/api/v1/trades/search?legalEntityId=${ctx.entityId}&limit=50`, 'trade search');
    get(`/api/v1/inventory/lots?holderEntityId=${ctx.entityId}&limit=100`, 'lots');
    get(`/api/v1/positions/goo?legalEntityId=${ctx.entityId}`, 'goo position');
    get(`/api/v1/positions/ets?legalEntityId=${ctx.entityId}&complianceYear=${ctx.year}`, 'ets ladder');
    get(`/api/v1/workflow/tasks?status=OPEN&limit=50`, 'inbox');
    get(`/api/v1/workflow/tasks/counts?actionable=true`, 'inbox counts');
    get(`/api/v1/audit/events?limit=50`, 'audit');
    availableToSell(ctx);
  });
  sleep(p.think);
}

const NORDIC_HYDRO = {
  type: 'all',
  of: [
    { type: 'countryInRegion', region: 'NORDIC' },
    { type: 'descendantOf', attribute: 'ENERGY_SOURCE', code: 'HYDRO' },
  ],
};

function availableToSell(ctx) {
  const delivery = new Date(Date.now() + 30 * 86400000).toISOString().slice(0, 10);
  const body = { holderEntityId: ctx.entityId, demands: [{ criterion: NORDIC_HYDRO, quantityMwh: 100, deliveryDate: delivery }] };
  const res = http.post(`${BASE}/api/v1/allocation/headroom/check`, JSON.stringify(body), {
    headers: READER,
    tags: { kind: 'check', name: 'available to sell' },
  });
  check(res, { 'available to sell 200': (r) => r.status === 200 });
}

export function captureTrade(ctx) {
  const today = new Date().toISOString().slice(0, 10);
  const delivery = new Date(Date.now() + 30 * 86400000).toISOString().slice(0, 10);
  const next = new Date(Date.now() + 31 * 86400000).toISOString().slice(0, 10);
  const body = {
    legalEntityId: ctx.entityId,
    bookId: ctx.bookId,
    counterpartyId: ctx.counterpartyId,
    productFamily: 'EMISSION_ALLOWANCE',
    instrumentType: 'FORWARD',
    tradeDate: today,
    regulatory: { executionTimestamp: new Date().toISOString(), venueMic: 'XOFF' },
    legs: [
      {
        side: 'BUY',
        allowanceProductCode: 'EUA',
        quantity: 1000,
        unit: 'TCO2E',
        price: '70.25',
        priceCurrency: 'EUR',
        deliveries: [
          { deliveryWindow: { start: delivery, endExclusive: next }, deliveryDate: delivery, quantity: 1000 },
        ],
      },
    ],
  };
  const res = http.post(`${BASE}/api/v1/trades`, JSON.stringify(body), {
    headers: TRADER,
    tags: { kind: 'write', name: 'capture trade' },
  });
  check(res, { 'capture trade 201': (r) => r.status === 201 || r.status === 200 });
  tradeCapture.add(res.timings.duration);
  if (res.status < 300) get(`/api/v1/trades/${res.json('id')}`, 'trade detail');
  sleep(p.think);
}

export function whatIf(ctx) {
  const res = http.post(
    `${BASE}/api/v1/allocation/plans/what-if`,
    JSON.stringify({ holderEntityId: ctx.entityId, idempotencyKey: `load-${__VU}-${__ITER}` }),
    { headers: headers('MIDDLE_OFFICE'), tags: { kind: 'solve', name: 'allocation what-if' } },
  );
  // 422 when the entity has no open obligations left: the request was still served.
  check(res, { 'what-if served': (r) => r.status === 200 || r.status === 422 });
  sleep(p.think);
}

function batchCall(method, path, body, roleHeaders, name, ok) {
  // The statuses listed as served are not failed requests (a period end refused by its gate still did its checks).
  const params = { headers: roleHeaders, tags: { kind: 'batch', name }, responseCallback: http.expectedStatuses(...ok) };
  const res = method === 'POST' ? http.post(`${BASE}${path}`, JSON.stringify(body), params) : http.get(`${BASE}${path}`, params);
  check(res, { [`${name} served`]: (r) => ok.includes(r.status) });
  return res;
}

export function batch(ctx) {
  const today = new Date().toISOString().slice(0, 10);
  const period = today.slice(0, 7);
  group('batch', () => {
    // An ad hoc valuation of the entity's whole book (curves, options, FX), stored as a draft run.
    batchCall('POST', '/api/v1/valuation/runs', { legalEntityId: ctx.entityId, valuationDate: today, kind: 'ADHOC', note: 'load test' },
      RISK, 'valuation run', [200, 201]);
    // The period end of the open month; a re-run posts only differences. 422 when a gate refuses it (no official
    // valuation for the period, a closed period): the measurement still ran.
    batchCall('POST', '/api/v1/ledger/period-end/runs', { legalEntityId: ctx.entityId, period }, ACCOUNTANT, 'period end', [200, 201, 422]);
    batchCall('GET', `/api/v1/reports/GL_TRIAL_BALANCE?legalEntityId=${ctx.entityId}&period=${period}&format=csv`, null, ACCOUNTANT,
      'trial balance', [200]);
    batchCall('GET', `/api/v1/reports/MTM_BY_TRADE?legalEntityId=${ctx.entityId}&format=csv`, null, RISK, 'mtm report', [200, 404, 422]);
    batchCall('GET', `/api/v1/settlement/invoices/export?legalEntityId=${ctx.entityId}&format=csv`, null, OPERATIONS, 'invoice export', [200]);
  });
  sleep(Math.max(p.think, 1));
}
