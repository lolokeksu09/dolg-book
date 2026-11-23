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
 const adminSubject = env.LEDGER_ADMIN_SUBJECT?.trim() || '';
 if (env.LEDGER_ADMIN_SUBJECT !== undefined && (!adminSubject || adminSubject.length > 100 || configured !== keys.length)) {
   throw new Error('LEDGER_ADMIN_SUBJECT requires a configured identity provider and a valid subject');
 }
 const browserOrigin = env.LEDGER_BROWSER_ORIGIN;
 if (browserOrigin !== undefined) {
   const origin = new URL(browserOrigin);
   const issuer = new URL(env.IDENTITY_ISSUER || '');
   if (!adminSubject || origin.origin !== browserOrigin || origin.protocol !== 'https:' ||
       issuer.protocol !== 'https:' || issuer.username || issuer.password || issuer.search || issuer.hash ||
       !/\/realms\/[^/]+$/.test(issuer.pathname) || issuer.href !== env.IDENTITY_ISSUER) {
     throw new Error('LEDGER_BROWSER_ORIGIN requires HTTPS, a Keycloak realm issuer and an admin subject');
   }
 }
 return {database,port,host:isAmvera?'0.0.0.0':'127.0.0.1',identityReady:configured===keys.length,...(adminSubject && {adminSubject}),...(browserOrigin && {browserOrigin})};
}
