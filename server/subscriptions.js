function nextMonth(timestamp) {
  const date = new Date(timestamp);
  const day = date.getUTCDate();
  date.setUTCDate(1);
  date.setUTCMonth(date.getUTCMonth() + 1);
  const next = new Date(date);
  next.setUTCMonth(next.getUTCMonth() + 1, 0);
  date.setUTCDate(Math.min(day, next.getUTCDate()));
  return date.toISOString();
}

export class Subscriptions {
  constructor(store, now = Date.now) {
    this.store = store;
    this.db = store.db;
    this.now = now;
    this.db.exec(`
      CREATE TABLE IF NOT EXISTS subscription_periods (
        shop_id TEXT PRIMARY KEY REFERENCES shops(id),
        paid_until TEXT NOT NULL
      );
      CREATE TABLE IF NOT EXISTS subscription_events (
        payment_ref TEXT PRIMARY KEY,
        shop_id TEXT NOT NULL REFERENCES shops(id),
        operator TEXT NOT NULL,
        activated_at TEXT NOT NULL,
        starts_at TEXT NOT NULL,
        paid_until TEXT NOT NULL
      );
      CREATE INDEX IF NOT EXISTS subscription_events_shop ON subscription_events(shop_id);
    `);
  }

  status(shopId, now = new Date(this.now()).toISOString()) {
    const paidUntil = this.db.prepare('SELECT paid_until FROM subscription_periods WHERE shop_id=?').get(shopId)?.paid_until ?? null;
    return { paidUntil, status: !paidUntil ? 'pending' : paidUntil > now ? 'active' : 'expired' };
  }

  requireActive(shopId) {
    if (this.status(shopId).status !== 'active') throw Error('subscription required');
  }

  shop(shopId, now = new Date(this.now()).toISOString()) {
    const shop = this.db.prepare('SELECT id, name FROM shops WHERE id=?').get(shopId);
    if (!shop) throw Error('not found');
    const owners = this.db.prepare("SELECT members.user_id AS subject, owner_contacts.email FROM members LEFT JOIN owner_contacts ON members.user_id=owner_contacts.user_id WHERE members.shop_id=? AND members.role='owner' ORDER BY members.user_id").all(shopId);
    const events = this.db.prepare(`
      SELECT payment_ref AS paymentReference, operator, activated_at AS activatedAt,
        starts_at AS startsAt, paid_until AS paidUntil
      FROM subscription_events WHERE shop_id=? ORDER BY rowid DESC LIMIT 50
    `).all(shopId);
    const access = this.status(shopId, now);
    return { ...shop, ...access, owners, events, nextPaidUntil: nextMonth(access.status === 'active' ? access.paidUntil : now) };
  }

  activate(operator, shopId, paymentReference, expectedPaidUntil) {
    if (typeof paymentReference !== 'string' || !/^[A-Za-z0-9_-]{1,64}$/.test(paymentReference)) throw Error('invalid input');
    return this.store.tx(() => {
      const now = new Date(this.now()).toISOString();
      const shop = this.shop(shopId, now);
      const previous = this.db.prepare('SELECT shop_id FROM subscription_events WHERE payment_ref=?').get(paymentReference);
      if (previous) {
        if (previous.shop_id !== shopId) throw Error('idempotency conflict');
        return { duplicate: true, shop };
      }
      if (shop.paidUntil !== expectedPaidUntil) throw Error('subscription changed');
      const startsAt = shop.status === 'active' ? shop.paidUntil : now;
      const paidUntil = nextMonth(startsAt);
      this.db.prepare('INSERT INTO subscription_events VALUES (?,?,?,?,?,?)').run(paymentReference, shopId, operator, now, startsAt, paidUntil);
      this.db.prepare(`
        INSERT INTO subscription_periods VALUES (?,?)
        ON CONFLICT(shop_id) DO UPDATE SET paid_until=excluded.paid_until
      `).run(shopId, paidUntil);
      return { duplicate: false, shop: this.shop(shopId, now) };
    });
  }
}
