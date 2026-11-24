import test from 'node:test';
import assert from 'node:assert/strict';
import {resolveRuntime} from '../server/config.js';
test('Amvera defaults database to persistent storage and permits only all-or-no provider settings',()=>{
 assert.deepEqual(resolveRuntime({AMVERA:'1'}),{database:'/data/dolg-book.sqlite',port:80,host:'0.0.0.0',identityReady:false});
 assert.throws(()=>resolveRuntime({}),/LEDGER_DATABASE/);
 assert.throws(()=>resolveRuntime({AMVERA:'1',IDENTITY_ISSUER:'test'}),/Incomplete identity/);
 const configured={AMVERA:'1',LEDGER_DATABASE:'/data/custom.sqlite',IDENTITY_INTROSPECTION_URL:'https://id.test/introspect',IDENTITY_ISSUER:'https://id.test',IDENTITY_AUDIENCE:'a',IDENTITY_CLIENT_ID:'id',IDENTITY_CLIENT_SECRET:'secret'};
 assert.equal(resolveRuntime(configured).identityReady,true);assert.equal(resolveRuntime(configured).database,'/data/custom.sqlite');
});
