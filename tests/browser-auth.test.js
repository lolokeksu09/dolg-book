import test from 'node:test';
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { once } from 'node:events';
import { Store } from '../server/store.js';
import { createApi } from '../server/http.js';
import { createBrowserAuth } from '../server/browser-auth.js';
import { resolveRuntime } from '../server/config.js';

test('browser login, store activation and bookkeeping require the correct session and origin', async t => {
  const origin = 'https://book.example.test';
  const issuer = 'https://id.example.test/realms/raschet';
  const store = new Store(':memory:', {requireSubscription:true});
  const identities = {'Bearer test-owner':{subject:'operator'}, 'Bearer test-buyer':{subject:'buyer'}};
  const authenticate = async auth => identities[auth];
  let time = Date.now();
  let challenge;
  const browserAuth = createBrowserAuth({origin,issuer,clientId:'raschet',clientSecret:'sample-only',authenticate,now:()=>time,
    exchange: async (url, {body}) => {
      assert.equal(url, issuer + '/protocol/openid-connect/token');
      assert.equal(body.get('redirect_uri'), origin + '/signin/callback');
      assert.equal(createHash('sha256').update(body.get('code_verifier')).digest('base64url'), challenge);
      return Response.json({access_token:body.get('code'),token_type:'Bearer',expires_in:90});
    },
  });
  const server = createApi({store,authenticate,browserAuth,adminSubject:'operator'});
  server.listen(0, '127.0.0.1');
  await once(server, 'listening');
  t.after(async () => { await new Promise(resolve => server.close(resolve)); store.close(); });
  const root = 'http://127.0.0.1:' + server.address().port;
  function request(path, {session, method='GET', body, from=origin}={}) {
    return fetch(root + path, {
      method, redirect:'manual', body:body && JSON.stringify(body), headers:{
        ...(session && {cookie:session}), ...(method !== 'GET' && {origin:from}),
        ...(body && {'content-type':'application/json'}),
      },
    });
  }
  async function login(code, next) {
    const start = await request('/signin?next=' + next);
    assert.equal(start.status,303);
    const location = new URL(start.headers.get('location'));
    assert.equal(location.origin, 'https://id.example.test');
    assert.equal(location.searchParams.get('code_challenge_method'),'S256');
    challenge = location.searchParams.get('code_challenge');
    const callback = '/signin/callback?' + new URLSearchParams({code,state:location.searchParams.get('state'),iss:issuer});
    const state = start.headers.get('set-cookie').split(';')[0];
    assert.equal((await request(callback,{session:'__Host-raschet-state=' + 'a'.repeat(43)})).status,400);
    const signed = await request(callback,{session:state});
    assert.equal(signed.status,303);
    assert.equal(signed.headers.get('location'), next === 'admin' ? '/admin/' : '/book/');
    assert.equal((await request(callback,{session:state})).status,400);
    const session = signed.headers.getSetCookie().find(value=>value.startsWith('__Host-raschet='));
    assert.match(session, /; Path=\/; Secure; HttpOnly; SameSite=Lax; Max-Age=90/);
    assert.doesNotMatch(session,/test-(owner|buyer)/);
    return session.split(';')[0];
  }
  assert.equal((await request('/book/')).status,200);
  assert.equal((await request('/shops')).status,401);
  const buyer = await login('test-buyer','//foreign.example.test');
  assert.deepEqual(await (await request('/session',{session:buyer})).json(), {enabled:true,signedIn:true,operator:false});
  assert.equal((await request('/shops',{session:buyer,method:'POST',body:{name:'Test'},from:'https://foreign.example.test'})).status,401);
  const created = await request('/shops',{session:buyer,method:'POST',body:{name:'Тестовая книга'}});
  assert.equal(created.status,201);
  const {shopId} = await created.json();
  const shop = '/shops/' + shopId;
  const billing = '/admin' + shop;
  assert.equal((await request(billing,{session:buyer})).status,403);
  const owner = await login('test-owner','admin');
  assert.deepEqual(await (await request('/session',{session:owner})).json(), {enabled:true,signedIn:true,operator:true});
  assert.equal((await request(billing + '/activate',{session:owner,method:'POST',body:{paymentReference:'E2E',expectedPaidUntil:null}})).status,201);
  assert.equal((await request(shop + '/clients',{session:buyer,method:'POST',body:{id:'client',name:'Покупатель'}})).status,201);
  assert.equal((await request(shop + '/entries',{session:buyer,method:'POST',body:{id:'write1',clientId:'client',kind:'debt',amount:1525,note:''}})).status,201);
  assert.equal((await (await request(shop + '/entries',{session:buyer})).json()).entries[0].amount,1525);
  assert.equal((await request('/signout',{session:buyer,method:'POST',from:'https://foreign.example.test'})).status,403);
  assert.equal((await request('/signout',{session:buyer,method:'POST'})).status,204);
  assert.equal((await request('/shops',{session:buyer})).status,401);
  time += 90_000;
  assert.equal((await request(billing,{session:owner})).status,401);
});

test('browser login is only enabled for a configured HTTPS deployment', () => {
  const base = {
    AMVERA:'1', IDENTITY_ISSUER:'https://id.example.test/realms/raschet',
    IDENTITY_INTROSPECTION_URL:'https://id.example.test/introspect',
    IDENTITY_CLIENT_ID:'client', IDENTITY_CLIENT_SECRET:'test-only',
    IDENTITY_AUDIENCE:'api', LEDGER_ADMIN_SUBJECT:'operator',
  };
  assert.equal(resolveRuntime({...base,LEDGER_BROWSER_ORIGIN:'https://book.example.test'}).browserOrigin,'https://book.example.test');
  for (const origin of ['http://example.test','https://example.test/path','https://user@example.test']) {
    assert.throws(()=>resolveRuntime({...base,LEDGER_BROWSER_ORIGIN:origin}), /requires HTTPS/);
  }
  assert.throws(()=>resolveRuntime({...base,LEDGER_ADMIN_SUBJECT:undefined,LEDGER_BROWSER_ORIGIN:'https://book.example.test'}), /admin subject/);
});
