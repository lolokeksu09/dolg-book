import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { Store } from '../server/store.js';

test('separate shops, role checks, idempotency and restored backup',async()=>{
 const dir=mkdtempSync(join(tmpdir(),'dolg-test-'));let db,restored;
 try {
  db=new Store(join(dir,'ledger.sqlite'));const a=db.createShop('owner-a','A'),b=db.createShop('owner-b','B');
  db.addMember('owner-a',a,'seller','clerk');db.createClient('seller',a,'c','Test');db.createClient('owner-b',b,'c','Other');
  const debt={id:'op-1',clientId:'c',kind:'debt',amount:270000,note:'sample'};
  assert.deepEqual(db.addEntry('seller',a,debt),{duplicate:false});assert.deepEqual(db.addEntry('seller',a,debt),{duplicate:true});
  assert.throws(()=>db.addEntry('seller',a,{...debt,amount:1}),/idempotency conflict/);
  assert.throws(()=>db.list('seller',b),/forbidden/);assert.throws(()=>db.list('',a),/unauthenticated/);
  assert.throws(()=>db.addMember('seller',a,'evil','owner'),/forbidden/);
  assert.throws(()=>db.addEntry('owner-b',a,{...debt,id:'op-2'}),/forbidden/);
  assert.throws(()=>db.addEntry('seller',a,{...debt,id:'op-2',kind:'payment',amount:270001}),/payment exceeds/);
  db.addEntry('seller',a,{...debt,id:'op-3',kind:'payment',amount:100050});
  assert.equal(db.list('owner-a',a).length,2);assert.deepEqual(db.list('owner-b',b),[]);
  await db.backup(join(dir,'backup.sqlite'));restored=new Store(join(dir,'backup.sqlite'));
  assert.deepEqual(restored.list('owner-a',a),db.list('owner-a',a));assert.throws(()=>restored.list('owner-b',a),/forbidden/);
 } finally {restored?.close();db?.close();rmSync(dir,{recursive:true,force:true});}
});
