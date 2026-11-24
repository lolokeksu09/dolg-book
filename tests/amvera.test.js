import test from 'node:test';
import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { createServer } from 'node:net';
import { mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { once } from 'node:events';

const getPort=async()=>{const s=createServer();s.listen(0,'127.0.0.1');await once(s,'listening');const p=s.address().port;await new Promise(r=>s.close(r));return p;};
test('Amvera entrypoint serves the interface and keeps API protected',async()=>{
 const dir=mkdtempSync(join(tmpdir(),'dolg-amvera-'));const port=await getPort();let logs='';
 const child=spawn(process.execPath,['server/start.js'],{cwd:process.cwd(),env:{...process.env,AMVERA:'1',PORT:String(port),LEDGER_DATABASE:join(dir,'test.sqlite'),IDENTITY_INTROSPECTION_URL:'https://id.example.test/introspect',IDENTITY_ISSUER:'https://id.example.test',IDENTITY_AUDIENCE:'test-api',IDENTITY_CLIENT_ID:'test-client',IDENTITY_CLIENT_SECRET:'synthetic-test-secret'},stdio:['ignore','pipe','pipe']});
 child.stdout.on('data',c=>logs+=c);child.stderr.on('data',c=>logs+=c);
 try{
  await Promise.race([new Promise((resolve,reject)=>{const timeout=setTimeout(()=>reject(new Error('startup timeout')),5000);child.stdout.on('data',c=>{if(String(c).includes('Ledger server listening')){clearTimeout(timeout);resolve();}});child.once('exit',()=>{clearTimeout(timeout);reject(new Error('server stopped early'));});})]);
  assert.match(logs,/0\.0\.0\.0:/);
  const url=`http://127.0.0.1:${port}`;
  let res=await fetch(url+'/health');assert.equal(res.status,200);assert.deepEqual(await res.json(),{status:'ok'});
  res=await fetch(url+'/');assert.equal(res.status,200);assert.match(await res.text(),/Долговая книга/);
  for(const path of ['/app.js','/ledger.js','/style.css'])assert.equal((await fetch(url+path)).status,200);
  res=await fetch(url+'/shops/test/entries');assert.equal(res.status,401);
  res=await fetch(url+'/server/start.js');assert.notEqual(res.status,200);
  assert.doesNotMatch(logs,/synthetic-test-secret/);
 } finally {if(child.exitCode===null){const ended=once(child,'exit');child.kill('SIGTERM');await ended;}rmSync(dir,{recursive:true,force:true});}
});
test('Amvera demo runs without identity provider while API fails closed',async()=>{
 const dir=mkdtempSync(join(tmpdir(),'dolg-demo-'));const port=await getPort();let errors='';
 const env={...process.env,AMVERA:'1',PORT:String(port),LEDGER_DATABASE:join(dir,'test.sqlite')};
 for(const name of ['IDENTITY_INTROSPECTION_URL','IDENTITY_ISSUER','IDENTITY_AUDIENCE','IDENTITY_CLIENT_ID','IDENTITY_CLIENT_SECRET'])delete env[name];
 const child=spawn(process.execPath,['server/start.js'],{cwd:process.cwd(),env,stdio:['ignore','pipe','pipe']});child.stderr.on('data',c=>errors+=c);
 try{
  await new Promise((resolve,reject)=>{const timer=setTimeout(()=>reject(new Error('startup timeout')),5000);child.stdout.on('data',c=>{if(String(c).includes('Ledger server listening')){clearTimeout(timer);resolve();}});child.once('exit',()=>{clearTimeout(timer);reject(new Error('early exit: '+errors));});});
  const url=`http://127.0.0.1:${port}`;
  assert.equal((await fetch(url+'/')).status,200);
  assert.equal((await fetch(url+'/shops/test/entries')).status,401);
  assert.equal((await fetch(url+'/shops/test/entries',{headers:{authorization:'Bearer synthetic-token'}})).status,503);
 }finally{if(child.exitCode===null){const ended=once(child,'exit');child.kill('SIGTERM');await ended;}rmSync(dir,{recursive:true,force:true});}
});
