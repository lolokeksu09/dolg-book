// Configuration only; no side effects and no credentials are printed.
export function resolveRuntime(env){
 const isAmvera=env.AMVERA==='1';
 const database=env.LEDGER_DATABASE||(isAmvera?'/data/dolg-book.sqlite':'');
 if(!database)throw new Error('LEDGER_DATABASE is required');
 const keys=['IDENTITY_INTROSPECTION_URL','IDENTITY_ISSUER','IDENTITY_AUDIENCE','IDENTITY_CLIENT_ID','IDENTITY_CLIENT_SECRET'];
 const configured=keys.filter(key=>typeof env[key]==='string'&&env[key].length>0).length;
 if(configured!==keys.length && (configured!==0||!isAmvera))throw new Error('Incomplete identity provider configuration');
 const port=Number(env.PORT||(isAmvera?80:3000));
 if(!Number.isInteger(port)||port<1||port>65535)throw new Error('invalid PORT');
 return {database,port,host:isAmvera?'0.0.0.0':'127.0.0.1',identityReady:configured===keys.length};
}
