import test from 'node:test';
import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { createApi } from '../server/http.js';
import { createIntrospector } from '../server/auth.js';
import { Store } from '../server/store.js';
import { resolveRuntime } from '../server/config.js';

const listen = server => new Promise(resolve => server.listen(0, '127.0.0.1', () => resolve('http://127.0.0.1:' + server.address().port)));
const close = server => new Promise(resolve => server.close(resolve));
const subjects = { operator: 'billing-operator', buyer: 'buyer-1', other: 'buyer-2' };

test('operator activates one month, repeats safely, and expiry preserves read access', async t => {
  let instant = Date.parse('2026-01-31T12:00:00.000Z');
  const provider = createServer(async (req, res) => {
    let body = '';
    for await (const chunk of req) body += chunk;
    const token = new URLSearchParams(body).get('token');
    const sub = subjects[token];
    res.setHeader('Content-Type', 'application/json');
    res.end(JSON.stringify({
      active: Boolean(sub), iss: 'test-id', aud: 'raschet', sub,
      exp: Math.floor(Date.now() / 1000) + 300, email: token + '@example.test', email_verified: token === 'buyer',
    }));
  });
  const endpoint = await listen(provider);
  t.after(() => close(provider));
  const authenticate = createIntrospector({
    endpoint, issuer: 'test-id', audience: 'raschet', clientId: 'test', clientSecret: 'test',
    allowLoopbackHttp: true, includeVerifiedEmail: true,
  });
  const store = new Store(':memory:', {requireSubscription: true, now: () => instant});
  const api = createApi({store, authenticate, adminSubject: subjects.operator});
  const root = await listen(api);
  t.after(async () => { await close(api); store.close(); });

  async function request(method, path, token, body) {
    const headers = {};
    if (token) headers.Authorization = 'Bearer ' + token;
    if (body) headers['Content-Type'] = 'application/json';
    const res = await fetch(root + path, {method, headers, body: body && JSON.stringify(body)});
    return {status: res.status, data: await res.json()};
  }

  const adminPage = await fetch(root + '/admin/');
  assert.equal(adminPage.status, 200);
  assert.match(adminPage.headers.get('content-security-policy'), /form-action 'none'/);
  assert.match(await adminPage.text(), /Подписки магазинов/);
  assert.equal((await fetch(root + '/admin/app.js')).status, 200);

  const first = await request('POST', '/shops', 'buyer', {name: 'Магазин 1'});
  const second = await request('POST', '/shops', 'other', {name: 'Магазин 2'});
  assert.equal(first.status, 201);
  assert.equal(second.status, 201);
  const shop = first.data.shopId;
  const another = second.data.shopId;
  const workspace = '/shops/' + shop;
  const billing = '/admin' + workspace;
  const activation = billing + '/activate';
  assert.deepEqual((await request('GET', '/shops', 'buyer')).data.shops.map(s => s.id), [shop]);
  assert.equal((await request('GET', workspace, 'other')).status, 403);
  assert.equal((await request('GET', billing)).status, 401);
  assert.equal((await request('GET', billing, 'buyer')).status, 403);
  assert.equal((await request('POST', activation, 'buyer', {paymentReference:'ref01',expectedPaidUntil:null})).status, 403);
  assert.equal((await request('POST', workspace + '/clients', 'buyer', {id:'c',name:'Клиент'})).status, 402);
  const info = (await request('GET', billing, 'operator')).data;
  assert.equal(info.status, 'pending');
  assert.equal(info.nextPaidUntil, '2026-02-28T12:00:00.000Z');
  assert.deepEqual(info.owners, [{subject: 'buyer-1', email: 'buyer@example.test'}]);
  assert.equal((await request('GET', '/admin/shops/' + another, 'operator')).data.owners[0].email, null);
  assert.equal((await request('GET', workspace, 'operator')).status, 403);

  const [a, b] = await Promise.all([
    request('POST', activation, 'operator', {paymentReference:'ref01',expectedPaidUntil:null}),
    request('POST', activation, 'operator', {paymentReference:'ref01',expectedPaidUntil:null}),
  ]);
  assert.deepEqual([a.status, b.status].sort(), [200, 201]);
  assert.equal(a.data.shop.paidUntil, '2026-02-28T12:00:00.000Z');
  assert.equal(b.data.shop.events.length, 1);
  assert.equal((await request('POST', '/admin/shops/' + another + '/activate', 'operator', {paymentReference:'ref01',expectedPaidUntil:null})).status, 409);

  assert.equal((await request('POST', workspace + '/clients', 'buyer', {id:'c',name:'Клиент'})).status, 201);
  const operation = {id:'d-1',clientId:'c',kind:'debt',amount:35000,note:''};
  assert.equal((await request('POST', workspace + '/entries', 'buyer', operation)).status, 201);
  const stale = await request('POST', activation, 'operator', {paymentReference:'ref02',expectedPaidUntil:null});
  assert.equal(stale.status, 409);
  assert.equal(stale.data.error, 'subscription changed');
  const renewal = await request('POST', activation, 'operator', {paymentReference:'ref02',expectedPaidUntil:a.data.shop.paidUntil});
  assert.equal(renewal.data.shop.paidUntil, '2026-03-28T12:00:00.000Z');
  assert.equal(renewal.data.shop.events[0].operator, subjects.operator);

  instant = Date.parse('2026-03-28T12:00:00.000Z');
  assert.equal((await request('GET', workspace, 'buyer')).data.status, 'expired');
  assert.deepEqual((await request('GET', workspace + '/entries', 'buyer')).data.entries.map(e => e.id), ['d-1']);
  assert.equal((await request('POST', workspace + '/entries', 'buyer', {...operation,id:'d-2'})).status, 402);
  assert.equal((await request('POST', workspace + '/entries', 'buyer', operation)).data.duplicate, true);
  instant = Date.parse('2026-04-10T12:00:00.000Z');
  assert.equal((await request('POST', activation, 'operator', {paymentReference:'ref03',expectedPaidUntil:renewal.data.shop.paidUntil})).data.shop.paidUntil, '2026-05-10T12:00:00.000Z');
});

test('operator configuration requires identity verification', () => {
  assert.throws(() => resolveRuntime({AMVERA:'1', LEDGER_ADMIN_SUBJECT:'operator'}), /requires a configured identity provider/);
  assert.throws(() => resolveRuntime({AMVERA:'1', LEDGER_ADMIN_SUBJECT:'  '}), /valid subject/);
});
