import { validateBook } from '../../dist/ledger.js';

const limit=5*1024*1024;
const reply=(status,data)=>Response.json(data,{status,headers:{'cache-control':'no-store','x-content-type-options':'nosniff'}});
async function readBounded(request){
  const len=request.headers.get('content-length');if(len!==null&&(!/^\d+$/.test(len)||Number(len)>limit))throw new Error('too large');
  if(!request.body)throw new Error('invalid');
  const reader=request.body.getReader();let chunks=[],size=0;
  while(true){const {done,value}=await reader.read();if(done)break;size+=value.byteLength;if(size>limit){await reader.cancel();throw new Error('too large');}chunks.push(value);}
  const all=new Uint8Array(size);let offset=0;for(const c of chunks){all.set(c,offset);offset+=c.length;}return new TextDecoder('utf-8',{fatal:true}).decode(all);
}
async function hash(s){const b=await crypto.subtle.digest('SHA-256',new TextEncoder().encode(s));return Array.from(new Uint8Array(b),n=>n.toString(16).padStart(2,'0')).join('');}
// The Sites dispatch layer must authenticate and overwrite the identity header.
// This module must not be exposed directly to untrusted traffic.
export async function handleBook(request,db){
  const user=request.headers.get('oai-authenticated-user-id');
  if(!user||user.length>256)return reply(401,{error:'unauthorized'});
  if(!db)return reply(503,{error:'storage unavailable'});
  try{
    if(request.method==='GET'){
      const row=await db.prepare('SELECT version, book_json, updated_at FROM site_books WHERE owner_id = ?').bind(user).first();
      return reply(200,row?{version:row.version,book:JSON.parse(row.book_json),updatedAt:row.updated_at}:{version:0,book:null,updatedAt:null});
    }
    if(request.method!=='PUT')return reply(405,{error:'method not allowed'});
    const url=new URL(request.url),origin=request.headers.get('origin');
    if(!origin||origin!==url.origin)return reply(403,{error:'origin denied'});
    if(request.headers.get('content-type')?.split(';')[0].trim().toLowerCase()!=='application/json')return reply(415,{error:'expected JSON'});
    let input;try{input=JSON.parse(await readBounded(request));}catch(e){return reply(e.message==='too large'?413:400,{error:'invalid request'});}
    if(!input||Array.isArray(input)||typeof input!=='object'||Object.keys(input).sort().join(',')!=='book,requestId,version'||!Number.isSafeInteger(input.version)||input.version<0||input.version>2147483646||typeof input.requestId!=='string'||!/^[A-Za-z0-9_-]{16,100}$/.test(input.requestId))return reply(400,{error:'invalid request'});
    let book;try{book=validateBook(input.book);}catch{return reply(400,{error:'invalid book'});}
    const payload=JSON.stringify(book);if(new TextEncoder().encode(payload).length>limit-1024)return reply(413,{error:'book too large'});
    const digest=await hash(JSON.stringify([input.version,payload]));
    const result=await db.prepare(`INSERT INTO site_books (owner_id,version,book_json,request_id,request_hash,updated_at)
      SELECT ?,1,?,?,?,? WHERE ?=0 OR EXISTS (SELECT 1 FROM site_books WHERE owner_id=?)
      ON CONFLICT(owner_id) DO UPDATE SET version=site_books.version+1,book_json=excluded.book_json,request_id=excluded.request_id,request_hash=excluded.request_hash,updated_at=excluded.updated_at WHERE site_books.version=? AND site_books.request_id<>excluded.request_id`).bind(user,payload,input.requestId,digest,new Date().toISOString(),input.version,user,input.version).run();
    if(result.meta.changes===1)return reply(200,{version:input.version+1,duplicate:false});
    const latest=await db.prepare('SELECT version,request_id,request_hash FROM site_books WHERE owner_id = ?').bind(user).first();
    if(latest?.request_id===input.requestId&&latest.request_hash===digest)return reply(200,{version:latest.version,duplicate:true});
    return reply(409,{error:'version conflict',currentVersion:latest?.version??0});
  }catch{return reply(503,{error:'storage unavailable'});}
}
