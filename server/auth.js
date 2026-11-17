// OAuth 2.0 token introspection: identity is accepted only from the configured provider.
// Plain HTTP exists solely for loopback integration tests; production requires HTTPS.
export function createIntrospector({endpoint, issuer, audience, clientId, clientSecret, allowLoopbackHttp=false}) {
  const url = new URL(endpoint);
  if(url.username || url.password || url.hash || url.search) throw new Error('invalid identity endpoint');
  const loopback = url.hostname === 'localhost' || url.hostname === '127.0.0.1' || url.hostname === '[::1]';
  if(url.protocol !== 'https:' && !(allowLoopbackHttp && loopback && url.protocol === 'http:')) throw new Error('identity endpoint requires HTTPS');
  if(!issuer || !audience || !clientId || !clientSecret || /[\r\n]/.test(clientId+clientSecret)) throw new Error('missing identity configuration');
  return async function authenticate(header) {
    const match=typeof header==='string' && /^Bearer ([A-Za-z0-9._~+\/-]+=*)$/.exec(header);
    if(!match || match[1].length>8192) return null;
    let response;
    try {
      const credentials=Buffer.from(`${encodeURIComponent(clientId)}:${encodeURIComponent(clientSecret)}`).toString('base64');
      response=await fetch(url,{method:'POST',redirect:'error',signal:AbortSignal.timeout(4000),headers:{'content-type':'application/x-www-form-urlencoded','authorization':`Basic ${credentials}`},body:new URLSearchParams({token:match[1],token_type_hint:'access_token'})});
      if(!response.ok) throw new Error('provider unavailable');
      const length=Number(response.headers.get('content-length'));if(Number.isFinite(length)&&length>16384)throw new Error('provider unavailable');
      const reader=response.body.getReader();let bytes=0,chunks=[];while(true){const {done,value}=await reader.read();if(done)break;bytes+=value.length;if(bytes>16384){await reader.cancel();throw new Error('provider unavailable');}chunks.push(value);}
      const data=JSON.parse(Buffer.concat(chunks.map(x=>Buffer.from(x))).toString('utf8'));
      const aud=Array.isArray(data.aud)?data.aud:[data.aud];
      if(data.active!==true || data.iss!==issuer || !aud.includes(audience) || typeof data.sub!=='string' || !data.sub.trim() || data.sub.length>100 || !Number.isSafeInteger(data.exp) || data.exp<=Math.floor(Date.now()/1000) || (data.nbf!==undefined && (!Number.isSafeInteger(data.nbf)||data.nbf>Math.floor(Date.now()/1000)))) return null;
      return data.sub;
    } catch { throw new Error('identity unavailable'); }
  };
}
