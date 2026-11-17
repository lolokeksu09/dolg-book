import { DatabaseSync, backup } from 'node:sqlite';
import { createHash, randomUUID } from 'node:crypto';

// Development domain layer. The caller must be a server-verified identity.
// This module does not provide authentication, network access or user provisioning.
export class Store {
  constructor(path) {
    this.db = new DatabaseSync(path);
    this.db.exec(`PRAGMA foreign_keys = ON; PRAGMA journal_mode = WAL;
      CREATE TABLE IF NOT EXISTS shops(id TEXT PRIMARY KEY, name TEXT NOT NULL);
      CREATE TABLE IF NOT EXISTS members(shop_id TEXT NOT NULL REFERENCES shops(id), user_id TEXT NOT NULL, role TEXT NOT NULL CHECK(role IN ('owner','clerk')), PRIMARY KEY(shop_id,user_id));
      CREATE TABLE IF NOT EXISTS clients(shop_id TEXT NOT NULL REFERENCES shops(id), id TEXT NOT NULL, name TEXT NOT NULL, PRIMARY KEY(shop_id,id));
      CREATE TABLE IF NOT EXISTS entries(shop_id TEXT NOT NULL, id TEXT NOT NULL, client_id TEXT NOT NULL, kind TEXT NOT NULL CHECK(kind IN ('debt','payment')), amount INTEGER NOT NULL CHECK(amount>0), note TEXT NOT NULL, actor TEXT NOT NULL, created_at TEXT NOT NULL, request_hash TEXT NOT NULL, PRIMARY KEY(shop_id,id), FOREIGN KEY(shop_id,client_id) REFERENCES clients(shop_id,id));`);
  }
  close() { this.db.close(); }
  tx(fn) { this.db.exec('BEGIN IMMEDIATE'); try { const out=fn(); this.db.exec('COMMIT'); return out; } catch(e) { this.db.exec('ROLLBACK'); throw e; } }
  role(actor,shop) {
    if(typeof actor!=='string'||!actor) throw new Error('unauthenticated');
    const row=this.db.prepare('SELECT role FROM members WHERE shop_id=? AND user_id=?').get(shop,actor);
    if(!row) throw new Error('forbidden'); return row.role;
  }
  createShop(actor,name) {
    text(actor,100); text(name,80); const id=randomUUID();
    return this.tx(()=>{this.db.prepare('INSERT INTO shops VALUES (?,?)').run(id,name.trim());this.db.prepare('INSERT INTO members VALUES (?,?,?)').run(id,actor,'owner');return id;});
  }
  addMember(actor,shop,user,role) {
    text(user,100);if(!['owner','clerk'].includes(role))throw new Error('invalid input');
    return this.tx(()=>{if(this.role(actor,shop)!=='owner')throw new Error('forbidden');this.db.prepare('INSERT INTO members VALUES (?,?,?)').run(shop,user,role);});
  }
  removeClerk(actor,shop,user) {
    text(user,100);
    return this.tx(()=>{if(this.role(actor,shop)!=='owner')throw new Error('forbidden');const result=this.db.prepare("DELETE FROM members WHERE shop_id=? AND user_id=? AND role='clerk'").run(shop,user);if(result.changes!==1)throw new Error('not found');});
  }
  createClient(actor,shop,id,name) {
    text(id,100);text(name,80);
    return this.tx(()=>{this.role(actor,shop);this.db.prepare('INSERT INTO clients VALUES (?,?,?)').run(shop,id,name.trim());});
  }
  addEntry(actor,shop,entry) {
    if(!entry||typeof entry!=='object'||Array.isArray(entry)||Object.keys(entry).sort().join(',')!=='amount,clientId,id,kind,note')throw new Error('invalid input');
    const {id,clientId,kind,amount,note}=entry;text(id,100);text(clientId,100);
    if(!['debt','payment'].includes(kind)||!Number.isSafeInteger(amount)||amount<=0||amount>100000000000||typeof note!=='string'||note.length>200)throw new Error('invalid input');
    const hash=createHash('sha256').update(JSON.stringify([clientId,kind,amount,note,actor])).digest('hex');
    return this.tx(()=>{
      this.role(actor,shop);
      const old=this.db.prepare('SELECT request_hash FROM entries WHERE shop_id=? AND id=?').get(shop,id);
      if(old){if(old.request_hash!==hash)throw new Error('idempotency conflict');return {duplicate:true};}
      const client=this.db.prepare('SELECT id FROM clients WHERE shop_id=? AND id=?').get(shop,clientId);if(!client)throw new Error('not found');
      const row=this.db.prepare("SELECT COALESCE(SUM(CASE kind WHEN 'debt' THEN amount ELSE -amount END),0) AS balance FROM entries WHERE shop_id=? AND client_id=?").get(shop,clientId);
      if(kind==='payment'&&amount>row.balance)throw new Error('payment exceeds balance');
      if(kind==='debt'&&(!Number.isSafeInteger(row.balance+amount)||row.balance+amount>100000000000))throw new Error('balance limit');
      this.db.prepare('INSERT INTO entries VALUES (?,?,?,?,?,?,?,?,?)').run(shop,id,clientId,kind,amount,note,actor,new Date().toISOString(),hash);return {duplicate:false};
    });
  }
  list(actor,shop) { this.role(actor,shop);return this.db.prepare('SELECT id,client_id,kind,amount,note,actor,created_at FROM entries WHERE shop_id=? ORDER BY rowid').all(shop); }
  async backup(path) { await backup(this.db,path); }
}
function text(value,max){if(typeof value!=='string'||!value.trim()||value.length>max)throw new Error('invalid input');}

