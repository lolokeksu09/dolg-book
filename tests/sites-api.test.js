import test from 'node:test';
import assert from 'node:assert/strict';
import {DatabaseSync} from 'node:sqlite';
import {readFileSync} from 'node:fs';
import {handleBook} from '../server/sites/book-api.js';
class LocalD1 {
 constructor(){this.db=new DatabaseSync(':memory:');this.db.exec(readFileSync(new URL('../server/sites/schema.sql',import.meta.url),'utf8'));}
 prepare(sql){const statement=this.db.prepare(sql);return {bind(...args){return {async first(){return statement.get(...args)??null},async run(){const r=statement.run(...args);return {meta:{changes:Number(r.changes)}}}}}}}
 close(){this.db.close();}
}
const empty={version:2,clients:[],entries:[]};
const req=(method,user,body,origin='https://test.example')=>new Request('https://test.example/api/book',{method,headers:{...(user?{'oai-authenticated-user-id':user}:{}),...(body?{'content-type':'application/json','origin':origin}:{})},...(body?{body:JSON.stringify(body)}:{})});
test('owner isolation, retry, version conflicts and validation on SQLite',async()=>{
 const db=new LocalD1();try{
  assert.equal((await handleBook(req('GET',null),db)).status,401);
  assert.equal((await (await handleBook(req('GET','a'),db)).json()).version,0);
  let op={version:0,requestId:'first-request-0001',book:empty};
  let r=await handleBook(req('PUT','a',op),db);assert.equal(r.status,200);assert.equal((await r.json()).version,1);
  r=await handleBook(req('PUT','a',op),db);assert.equal((await r.json()).duplicate,true);
  let other={...op,requestId:'other-request-0001'};assert.equal((await handleBook(req('PUT','a',other),db)).status,409);
  assert.equal((await (await handleBook(req('GET','b'),db)).json()).version,0);
  assert.equal((await handleBook(req('PUT','a',{version:1,requestId:'first-request-0001',book:empty}),db)).status,409);
  let changed={version:2,clients:[{id:'c',name:'Имя',phone:''}],entries:[]};
  assert.equal((await handleBook(req('PUT','a',{version:1,requestId:'second-request-001',book:changed},'https://bad.example'),db)).status,403);
  r=await handleBook(req('PUT','a',{version:1,requestId:'second-request-001',book:changed}),db);assert.equal(r.status,200);assert.equal((await r.json()).version,2);
  assert.equal((await handleBook(req('PUT','a',{version:2,requestId:'third-request-0001',book:{...changed,entries:[{id:'x',clientId:'c',type:'payment',amount:1,date:'2026-01-01T00:00:00Z',due:'',note:''}]}}),db)).status,400);
  assert.deepEqual((await (await handleBook(req('GET','a'),db)).json()).book,changed);
  assert.equal((await handleBook(req('PUT','b',{version:1,requestId:'first-request-0002',book:empty}),db)).status,409);
 }finally{db.close()}
});
