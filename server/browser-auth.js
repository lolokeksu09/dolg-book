import { createHash, randomBytes, timingSafeEqual } from 'node:crypto';

const cookieName = '__Host-raschet';
const stateName = '__Host-raschet-state';
const minutes = 60_000;
const identifier = () => randomBytes(32).toString('base64url');
const cookie = (name, value, seconds) => `${name}=${value}; Path=/; Secure; HttpOnly; SameSite=Lax; Max-Age=${seconds}`;

function getCookie(request, name) {
  return request.headers.cookie?.split(';').map(part => part.trim())
    .find(part => part.startsWith(name + '='))?.slice(name.length + 1);
}

function equal(left, right) {
  return typeof left === 'string' && typeof right === 'string' && left.length === 43 && right.length === 43
    && Buffer.byteLength(left) === Buffer.byteLength(right)
    && timingSafeEqual(Buffer.from(left), Buffer.from(right));
}

export function createBrowserAuth({origin, issuer, clientId, clientSecret, authenticate, exchange = fetch, now = Date.now}) {
  const authorizations = new Map();
  const sessions = new Map();
  const redirectUri = origin + '/signin/callback';
  const send = (response, status, headers = {}, body) => {
    response.writeHead(status, {'cache-control':'no-store','referrer-policy':'no-referrer','x-content-type-options':'nosniff',...headers});
    response.end(body);
  };
  const redirect = (response, path, cookies) => send(response, 303, {location:path,'set-cookie':cookies});
  const end = kind => cookie(kind, '', 0);
  const purge = (map) => {
    const time = now();
    for (const [id, entry] of map) if (entry.expires <= time) map.delete(id);
  };

  async function route(request, response, url) {
    const path = url.pathname;
    purge(authorizations);
    purge(sessions);
    if (request.method === 'GET' && path === '/signin') {
      const target = url.searchParams.get('next') === 'admin' ? '/admin/' : '/book/';
      const state = identifier();
      const verifier = identifier();
      const challenge = createHash('sha256').update(verifier).digest('base64url');
      if (authorizations.size >= 2048) return send(response, 503);
      authorizations.set(state, {verifier, target, expires:now() + 5 * minutes});
      const endpoint = new URL(issuer + '/protocol/openid-connect/auth');
      endpoint.search = new URLSearchParams({
        client_id:clientId, redirect_uri:redirectUri, response_type:'code', scope:'openid email',
        state, code_challenge:challenge, code_challenge_method:'S256', prompt:'login',
      });
      return redirect(response, endpoint.href, cookie(stateName, state, 300));
    }
    if (request.method === 'GET' && path === '/signin/callback') {
      const state = url.searchParams.get('state');
      if (!equal(state, getCookie(request, stateName))) return send(response, 400);
      const attempt = authorizations.get(state);
      authorizations.delete(state);
      if (!attempt || (url.searchParams.has('iss') && url.searchParams.get('iss') !== issuer)) return send(response, 400);
      try {
        const code = url.searchParams.get('code');
        if (url.searchParams.has('error') || !code || code.length > 4096) throw Error();
        const result = await exchange(issuer + '/protocol/openid-connect/token', {
          method:'POST', redirect:'error', signal:AbortSignal.timeout(5000),
          headers:{'content-type':'application/x-www-form-urlencoded'},
          body:new URLSearchParams({client_id:clientId,client_secret:clientSecret,grant_type:'authorization_code',code,code_verifier:attempt.verifier,redirect_uri:redirectUri}),
        });
        if (!result.ok) throw Error();
        const data = await result.json();
        if (typeof data.access_token !== 'string' || data.access_token.length > 8192 ||
            typeof data.token_type !== 'string' || data.token_type.toLowerCase() !== 'bearer' ||
            !Number.isSafeInteger(data.expires_in) || data.expires_in < 1) throw Error();
        const header = 'Bearer ' + data.access_token;
        if (!await authenticate(header) || sessions.size >= 2048) throw Error();
        sessions.delete(getCookie(request, cookieName));
        const id = identifier();
        const seconds = Math.min(data.expires_in, 1800);
        sessions.set(id, {header, expires:now() + seconds * 1000});
        return redirect(response, attempt.target, [end(stateName),cookie(cookieName,id,seconds)]);
      } catch {
        return redirect(response, attempt.target + '?login=failed', end(stateName));
      }
    }
    if (request.method === 'POST' && path === '/signout') {
      if (request.headers.origin !== origin) return send(response, 403);
      sessions.delete(getCookie(request, cookieName));
      return send(response, 204, {'set-cookie':end(cookieName)});
    }
    return false;
  }

  return {
    route,
    authorization(request) {
      const session = sessions.get(getCookie(request, cookieName));
      if (!session || session.expires <= now()) return undefined;
      if (!['GET','HEAD'].includes(request.method) && request.headers.origin !== origin) return undefined;
      return session.header;
    },
  };
}
