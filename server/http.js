import { createServer } from 'node:http';
import { readFile } from 'node:fs/promises';
import { join } from 'node:path';

// Requires an introspector; there is deliberately no fallback to caller-supplied identity.
export function createApi({store, authenticate, staticDirectory=null, adminSubject='', browserAuth=null}) {
 if(!store || typeof authenticate!=='function')throw new Error('identity verifier required');
 return createServer(async(req,res)=>{
  const send=(status,body)=>{res.writeHead(status,{'content-type':'application/json; charset=utf-8','cache-control':'no-store','x-content-type-options':'nosniff'});res.end(JSON.stringify(body));};
  try {
   if(req.url==='/health' && req.method==='GET')return send(200,{status:'ok'});
   const staticAssets={'/':['index.html','text/html; charset=utf-8'],'/index.html':['index.html','text/html; charset=utf-8'],'/app.js':['app.js','text/javascript; charset=utf-8'],'/ledger.js':['ledger.js','text/javascript; charset=utf-8'],'/style.css':['style.css','text/css; charset=utf-8']};
   const url = new URL(req.url, 'http://localhost');
   const assetPath = url.pathname;
   if (browserAuth && ['/signin','/signin/callback','/signout'].includes(assetPath)) {
    if (await browserAuth.route(req, res, url) !== false) return;
   }
   if (req.method === 'GET' && assetPath === '/session') {
    if (!browserAuth) return send(200, {enabled:false});
    let identity;
    try { identity = await authenticate(browserAuth.authorization(req)); }
    catch { return send(503, {error:'identity unavailable'}); }
    const subject = typeof identity === 'string' ? identity : identity?.subject;
    return send(200, {enabled:true, signedIn:Boolean(subject), operator:Boolean(subject && subject === adminSubject)});
   }
   const bookAssets = {
    '/book/': ['index.html', 'text/html; charset=utf-8'],
    '/book/app.js': ['app.js', 'text/javascript; charset=utf-8'],
   };
   const adminAssets = {
    '/admin/': ['index.html', 'text/html; charset=utf-8'],
    '/admin/app.js': ['app.js', 'text/javascript; charset=utf-8'],
    '/admin/style.css': ['style.css', 'text/css; charset=utf-8'],
   };
   const page = adminSubject && Object.hasOwn(adminAssets, assetPath) ? 'admin'
    : browserAuth && Object.hasOwn(bookAssets, assetPath) ? 'book' : null;
   if (page && ['GET','HEAD'].includes(req.method)) {
    const [file,type] = (page === 'admin' ? adminAssets : bookAssets)[assetPath];
    const data = await readFile(new URL('./'+page+'/'+file, import.meta.url));
    res.writeHead(200, {
     'content-type':type, 'cache-control':'no-store', 'x-content-type-options':'nosniff',
     'content-security-policy':"default-src 'none'; script-src 'self'; style-src 'self'; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'",
     'referrer-policy':'no-referrer',
    });
    return res.end(req.method==='HEAD'?undefined:data);
   }
   const asset=Object.hasOwn(staticAssets,assetPath)?staticAssets[assetPath]:null;
   if(staticDirectory&&asset&&(req.method==='GET'||req.method==='HEAD')){
    try{const data=await readFile(join(staticDirectory,asset[0]));res.writeHead(200,{'content-type':asset[1],'cache-control':'no-cache','x-content-type-options':'nosniff'});return res.end(req.method==='HEAD'?undefined:data);}
    catch{return send(503,{error:'interface unavailable'});}
   }
   let identity;try{identity=await authenticate(req.headers.authorization ?? browserAuth?.authorization(req));}catch{return send(503,{error:'identity unavailable'});}
   const actor = typeof identity === 'string' ? identity : identity?.subject;
   if(!actor)return send(401,{error:'unauthorized'});
   const path=new URL(req.url,'http://localhost').pathname;
   const parts=path.split('/').filter(Boolean).map(p=>decodeURIComponent(p));
   const route=parts.join('/');
   if(parts[0]==='admin'){
    if(!adminSubject)return send(404,{error:'not found'});
    if(actor!==adminSubject)return send(403,{error:'forbidden'});
    if(req.method==='GET'&&parts.length===3&&parts[1]==='shops')return send(200,store.subscriptions.shop(parts[2]));
   }
   if(req.method==='GET'&&route==='shops')return send(200,{shops:store.listShops(actor)});
   if(req.method==='GET'&&parts.length===2&&parts[0]==='shops')return send(200,store.readShop(actor,parts[1]));
   if(req.method==='GET' && parts.length===3 && parts[0]==='shops' && parts[2]==='entries')return send(200,{entries:store.list(actor,parts[1])});
   if(req.method==='DELETE' && parts.length===4 && parts[0]==='shops' && parts[2]==='members'){store.removeClerk(actor,parts[1],parts[3]);return send(200,{ok:true});}
   if(req.method!=='POST')return send(404,{error:'not found'});
   if(req.headers['content-type']?.split(';')[0].trim().toLowerCase()!=='application/json')return send(415,{error:'expected JSON'});
   let bytes=0,chunks=[];for await(const chunk of req){bytes+=chunk.length;if(bytes>16384)return send(413,{error:'body too large'});chunks.push(chunk);}
   let body;try{body=JSON.parse(Buffer.concat(chunks).toString('utf8'));}catch{return send(400,{error:'invalid JSON'});}
   if(!body||Array.isArray(body)||typeof body!=='object')return send(400,{error:'invalid input'});
   const keys=(expected)=>Object.keys(body).sort().join(',')===expected;
   if(parts.length===4&&parts[0]==='admin'&&parts[1]==='shops'&&parts[3]==='activate'&&keys('expectedPaidUntil,paymentReference')){
    const activated = store.subscriptions.activate(actor,parts[2],body.paymentReference,body.expectedPaidUntil);
    return send(activated.duplicate?200:201,activated);
   }
   if(route==='shops' && keys('name'))return send(201,{shopId:store.createShop(actor,body.name,identity.email)});
   if(parts.length===3&&parts[0]==='shops'&&parts[2]==='members'&&keys('role,userId')){store.addMember(actor,parts[1],body.userId,body.role);return send(201,{ok:true});}
   if(parts.length===3&&parts[0]==='shops'&&parts[2]==='clients'&&keys('id,name')){store.createClient(actor,parts[1],body.id,body.name);return send(201,{ok:true});}
   if(parts.length===3&&parts[0]==='shops'&&parts[2]==='entries'){const result=store.addEntry(actor,parts[1],body);return send(result.duplicate?200:201,result);}
   return send(404,{error:'not found'});
  }catch(err){
   const m=err?.message||'';
   if(m==='forbidden'||m==='unauthenticated')return send(403,{error:'forbidden'});
   if(m==='subscription required')return send(402,{error:'subscription required'});
   if(m==='not found')return send(404,{error:'not found'});
   if(['invalid input','payment exceeds balance','balance limit','URI malformed'].includes(m))return send(400,{error:'invalid input'});
   if(m==='subscription changed')return send(409,{error:'subscription changed'});
   if(m==='idempotency conflict'||String(err?.code||'').startsWith('ERR_SQLITE_CONSTRAINT'))return send(409,{error:'conflict'});
   return send(500,{error:'internal error'});
  }
 });
}
