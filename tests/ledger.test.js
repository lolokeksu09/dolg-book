import test from 'node:test';
import assert from 'node:assert/strict';
import {moneyToCents,balance,overdue,validateBook,addEntry,demoBook,activeEntries} from '../dist/ledger.js';
const initial=()=>({version:1,clients:[{id:'c',name:'Клиент',phone:''}],entries:[]});
const entry=(id,type,amount,due='')=>({id,clientId:'c',type,amount,date:'2026-11-01T12:00:00Z',due,note:''});
test('money uses integer kopecks and rejects ambiguous input',()=>{assert.equal(moneyToCents('1 234,56'),123456);assert.equal(moneyToCents('0.01'),1);for(const s of ['0','-5','1.234','NaN','1e5','1,2,3'])assert.throws(()=>moneyToCents(s));});
test('partial payment reduces oldest unpaid debt, overdue excludes future records',()=>{let b=addEntry(initial(),entry('1','debt',10000,'2026-11-02'));b=addEntry(b,entry('2','debt',15000,'2026-12-02'));b=addEntry(b,entry('3','payment',6000));assert.equal(balance(b,'c'),19000);assert.equal(overdue(b,'c','2026-11-17'),4000);b=addEntry(b,entry('4','payment',9000));assert.equal(overdue(b,'c','2026-11-17'),0);assert.equal(balance(b,'c'),10000);});
test('invalid imports cannot introduce overpayment, duplicate IDs or orphan entries',()=>{assert.throws(()=>addEntry(initial(),entry('1','payment',1)));const b=addEntry(initial(),entry('1','debt',100));assert.throws(()=>addEntry(b,entry('1','payment',50)));assert.throws(()=>validateBook({...b,clients:[]}));assert.throws(()=>addEntry(b,entry('2','debt',100,'2026-02-30')));assert.throws(()=>addEntry(b,{...entry('2','debt',100),amount:1.5}));});
test('backup roundtrip preserves exact ledger and demo is internally valid',()=>{const b=demoBook();assert.deepEqual(validateBook(JSON.parse(JSON.stringify(b))),b);assert.equal(b.clients.reduce((n,c)=>n+balance(b,c.id),0),801000);});
const reverse=(original,id='r')=>({...entry(id,'reversal',original.amount),reverses:original.id,note:'Ошибочная запись'});
test('cancelling payment restores balance and overdue without removing history',()=>{
  let b=addEntry(initial(),entry('d','debt',10000,'2026-11-02'));const p=entry('p','payment',4000);b=addEntry(b,p);b=addEntry(b,reverse(p));
  assert.equal(balance(b,'c'),10000);assert.equal(overdue(b,'c','2026-11-17'),10000);assert.equal(b.entries.length,3);assert.deepEqual(activeEntries(b).map(e=>e.id),['d']);assert.deepEqual(validateBook(JSON.parse(JSON.stringify(b))),b);
});
test('cancellation only accepts latest active client entry and rejects forged reversals',()=>{
  const d=entry('d','debt',10000),p=entry('p','payment',4000);let b=addEntry(addEntry(initial(),d),p);
  assert.throws(()=>addEntry(b,reverse(d)));assert.throws(()=>addEntry(b,{...reverse(p),amount:3000}));assert.throws(()=>addEntry(b,{...reverse(p),clientId:'missing'}));assert.throws(()=>addEntry(b,{...reverse(p),note:' '}));
  b=addEntry(b,reverse(p));assert.throws(()=>addEntry(b,reverse(p,'r2')));b=addEntry(b,reverse(d,'r3'));assert.equal(balance(b,'c'),0);assert.equal(activeEntries(b).length,0);
});
test('legacy v1 backup is migrated losslessly and editing contact preserves ledger',()=>{
  const legacy=addEntry(initial(),entry('d','debt',1050));legacy.version=1;const b=validateBook(legacy);assert.equal(b.version,2);assert.equal(balance(b,'c'),1050);
  const edited=validateBook({...b,clients:[{...b.clients[0],name:'Новое имя',phone:'+7 900'}]});assert.deepEqual(edited.entries,b.entries);assert.equal(balance(edited,'c'),1050);
});
