import test from 'node:test';
import assert from 'node:assert/strict';
import { spawn, spawnSync } from 'node:child_process';
import { createServer } from 'node:net';
import { mkdtempSync, readFileSync, readdirSync, rmSync, writeFileSync, existsSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { once } from 'node:events';

function files(dir, prefix='') { return readdirSync(dir,{withFileTypes:true}).flatMap(e=>e.isDirectory()?files(join(dir,e.name),prefix+e.name+'/'):[prefix+e.name]); }
const freePort=async()=>{const s=createServer();s.listen(0,'127.0.0.1');await once(s,'listening');const port=s.address().port;await new Promise(r=>s.close(r));return port;};
test('Amvera build contains only runtime files and actually runs',async()=>{
 const build=()=>{const r=spawnSync(process.execPath,['scripts/build-amvera.mjs'],{encoding:'utf8'});assert.equal(r.status,0,r.stderr);};
 build();writeFileSync('amvera-dist/stale.md','synthetic');build();
 const all=files('amvera-dist').filter(x=>x!=='.dolg-build-marker').sort();
 assert.deepEqual(all,['dist/app.js','dist/index.html','dist/ledger.js','dist/style.css','package.json','server/admin/app.js','server/admin/index.html','server/admin/style.css','server/auth.js','server/config.js','server/http.js','server/start.js','server/store.js','server/subscriptions.js']);
 assert.equal(existsSync('amvera-dist/stale.md'),false);
 assert.match(readFileSync('amvera.yaml','utf8'),/additionalCommands: node scripts\/build-amvera\.mjs/);
 assert.match(readFileSync('amvera.yaml','utf8'),/"amvera-dist\/\*": \//);
 const dir=mkdtempSync(join(tmpdir(),'amvera-build-'));const port=await freePort();
 const child=spawn(process.execPath,['server/start.js'],{cwd:'amvera-dist',env:{...process.env,AMVERA:'1',PORT:String(port),LEDGER_DATABASE:join(dir,'test.sqlite'),IDENTITY_INTROSPECTION_URL:'https://id.example.test/introspect',IDENTITY_ISSUER:'https://id.example.test',IDENTITY_AUDIENCE:'test-api',IDENTITY_CLIENT_ID:'test-client',IDENTITY_CLIENT_SECRET:'synthetic-test-secret'},stdio:['ignore','pipe','pipe']});
 let errors='';child.stderr.on('data',c=>errors+=c);
 try{
  await new Promise((resolve,reject)=>{const timeout=setTimeout(()=>reject(new Error('startup timeout: '+errors)),5000);child.stdout.on('data',c=>{if(String(c).includes('Ledger server listening')){clearTimeout(timeout);resolve();}});child.once('exit',()=>{clearTimeout(timeout);reject(new Error('server stopped early: '+errors));});});
  const base=`http://127.0.0.1:${port}`;let r=await fetch(base+'/');assert.equal(r.status,200);const html=await r.text();assert.match(html,/Исходный код на GitHub/);assert.doesNotMatch(html,/project-source\.zip/);
  for(const path of ['/app.js','/ledger.js','/style.css'])assert.equal((await fetch(base+path)).status,200);
  assert.notEqual((await fetch(base+'/AGENTS.md')).status,200);assert.notEqual((await fetch(base+'/project-source.zip')).status,200);
  assert.equal((await fetch(base+'/shops/example/entries')).status,401);
 }finally{if(child.exitCode===null){const end=once(child,'exit');child.kill('SIGTERM');await end;}rmSync(dir,{recursive:true,force:true});}
});
