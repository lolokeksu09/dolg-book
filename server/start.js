import { Store } from './store.js';
import { createApi } from './http.js';
import { createIntrospector } from './auth.js';
import { createBrowserAuth } from './browser-auth.js';
import { resolveRuntime } from './config.js';
import { fileURLToPath } from 'node:url';
const config=resolveRuntime(process.env);
const {IDENTITY_INTROSPECTION_URL,IDENTITY_ISSUER,IDENTITY_AUDIENCE,IDENTITY_CLIENT_ID,IDENTITY_CLIENT_SECRET}=process.env;
// No identity provider: allow the local-only browser demo, never grant API access.
const authenticate=config.identityReady
 ? createIntrospector({endpoint:IDENTITY_INTROSPECTION_URL,issuer:IDENTITY_ISSUER,audience:IDENTITY_AUDIENCE,clientId:IDENTITY_CLIENT_ID,clientSecret:IDENTITY_CLIENT_SECRET,includeVerifiedEmail:true})
 : async header=>{if(!header)return null;throw new Error('identity unavailable');};
const store=new Store(config.database, {requireSubscription: Boolean(config.adminSubject)});
const staticDirectory=fileURLToPath(new URL('../dist/',import.meta.url));
const browserAuth = config.browserOrigin && createBrowserAuth({
  origin:config.browserOrigin, issuer:IDENTITY_ISSUER, clientId:IDENTITY_CLIENT_ID, clientSecret:IDENTITY_CLIENT_SECRET, authenticate,
});
const server=createApi({store,authenticate,staticDirectory,adminSubject:config.adminSubject,browserAuth});
server.listen(config.port,config.host,()=>console.log(`Ledger server listening on ${config.host}:${config.port}${config.identityReady?'':' (local browser demo; API unavailable)'}`));
for(const sig of ['SIGINT','SIGTERM'])process.on(sig,()=>server.close(()=>{store.close();process.exit(0);}));
