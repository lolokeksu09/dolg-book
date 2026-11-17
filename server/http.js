import { createServer } from 'node:http';
import { readFile } from 'node:fs/promises';
import { join } from 'node:path';

// Requires an introspector; there is deliberately no fallback to caller-supplied identity.
export function createApi({store, authenticate, staticDirectory=null}) {
 if(!store || typeof authenticate!=='function')throw new Error('identity verifier required');
 return createServer(async(req,res)=>{
  const send=(status,body)=>{res.writeHead(status,{'content-type':'application/json; charset=utf-8','cache-control':'no-store','x-content-type-options':'nosniff'});res.end(JSON.stringify(body));};
  try {
   if(req.url==='/health' && req.method==='GET')return send(200,{status:'ok'});
   const staticAssets={'/':['index.html','text/html; charset=utf-8'],'/index.html':['index.html','text/html; charset=utf-8'],'/app.js':['app.js','text/javascript; charset=utf-8'],'/ledger.js':['ledger.js','text/javascript; charset=utf-8'],'/style.css':['style.css','text/css; charset=utf-8']};
   const assetPath=new URL(req.url,'http://localhost').pathname;
   const asset=Object.hasOwn(staticAssets,assetPath)?staticAssets[assetPath]:null;
   if(staticDirectory&&asset&&(req.method==='GET'||req.method==='HEAD')){
    try{const data=await readFile(join(staticDirectory,asset[0]));res.writeHead(200,{'content-type':asset[1],'cache-control':'no-cache','x-content-type-options':'nosniff'});return res.end(req.method==='HEAD'?undefined:data);}
    catch{return send(503,{error:'interface unavailable'});}
   }
   let actor;try{actor=await authenticate(req.headers.authorization);}catch{return send(503,{error:'identity unavailable'});}
   if(!actor)return send(401,{error:'unauthorized'});
   const path=new URL(req.url,'http://localhost').pathname;
   const parts=path.split('/').filter(Boolean).map(p=>decodeURIComponent(p));
   const route=parts.join('/');
   if(req.method==='GET' && parts.length===3 && parts[0]==='shops' && parts[2]==='entries')return send(200,{entries:store.list(actor,parts[1])});
   if(req.method==='DELETE' && parts.length===4 && parts[0]==='shops' && parts[2]==='members'){store.removeClerk(actor,parts[1],parts[3]);return send(200,{ok:true});}
   if(req.method!=='POST')return send(404,{error:'not found'});
   if(req.headers['content-type']?.split(';')[0].trim().toLowerCase()!=='application/json')return send(415,{error:'expected JSON'});
   let bytes=0,chunks=[];for await(const chunk of req){bytes+=chunk.length;if(bytes>16384)return send(413,{error:'body too large'});chunks.push(chunk);}
   let body;try{body=JSON.parse(Buffer.concat(chunks).toString('utf8'));}catch{return send(400,{error:'invalid JSON'});}
   if(!body||Array.isArray(body)||typeof body!=='object')return send(400,{error:'invalid input'});
   const keys=(expected)=>Object.keys(body).sort().join(',')===expected;
   if(route==='shops' && keys('name'))return send(201,{shopId:store.createShop(actor,body.name)});
   if(parts.length===3&&parts[0]==='shops'&&parts[2]==='members'&&keys('role,userId')){store.addMember(actor,parts[1],body.userId,body.role);return send(201,{ok:true});}
   if(parts.length===3&&parts[0]==='shops'&&parts[2]==='clients'&&keys('id,name')){store.createClient(actor,parts[1],body.id,body.name);return send(201,{ok:true});}
   if(parts.length===3&&parts[0]==='shops'&&parts[2]==='entries'){const result=store.addEntry(actor,parts[1],body);return send(result.duplicate?200:201,result);}
   return send(404,{error:'not found'});
  }catch(err){
   const m=err?.message||'';
   if(m==='forbidden'||m==='unauthenticated')return send(403,{error:'forbidden'});
   if(m==='not found')return send(404,{error:'not found'});
   if(['invalid input','payment exceeds balance','balance limit','URI malformed'].includes(m))return send(400,{error:'invalid input'});
   if(m==='idempotency conflict'||String(err?.code||'').startsWith('ERR_SQLITE_CONSTRAINT'))return send(409,{error:'conflict'});
   return send(500,{error:'internal error'});
  }
 });
}
