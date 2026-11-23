const find = id => document.getElementById(id);
const shops = find('shops');
const newShop = find('new-shop');
const newClient = find('new-client');
const entry = find('entry');
const rubles = new Intl.NumberFormat('ru-RU', {style:'currency', currency:'RUB'});
const date = new Intl.DateTimeFormat('ru-RU', {dateStyle:'medium', timeStyle:'short'});
let selected;
let busy = false;
let lastWrite;

const status = message => { find('status').textContent = message; };
const hideBook = () => { selected = undefined; find('book').hidden = true; };

async function api(path, method = 'GET', body) {
  const headers = body ? {'Content-Type':'application/json'} : {};
  const response = await fetch(path, {method, headers, body:body && JSON.stringify(body), cache:'no-store', redirect:'error'});
  if (response.status === 401) {
    hideBook();
    find('workspace').hidden = find('signout').hidden = true;
    find('signin').hidden = false;
    throw Error('Войдите снова, чтобы открыть книгу.');
  }
  if (!response.ok) throw Error({
    400:'Проверьте данные. Оплата не может превышать остаток.',
    402:'Нужна действующая подписка. Откройте книгу снова, чтобы обновить статус.',
    403:'Книга недоступна для этой учётной записи.',
    409:'Запись уже существует. Обновите книгу и проверьте историю.',
    503:'Служба входа сейчас недоступна.',
  }[response.status] || 'Сервер не выполнил запрос.');
  return response.status === 204 ? null : response.json();
}

async function run(action) {
  if (busy) return;
  busy = true;
  const buttons = [...document.querySelectorAll('button')];
  for (const button of buttons) button.disabled = true;
  status('Загружаем…');
  try { status(await action()); }
  catch (error) { status(error instanceof TypeError ? 'Нет связи. Проверьте интернет и повторите действие.' : error.message); }
  finally {
    busy = false;
    for (const button of buttons) button.disabled = false;
  }
}

function rows(id, items, empty) {
  find(id).replaceChildren(...(items.length ? items : [empty]).map(text => {
    const row = document.createElement('li');
    row.textContent = text;
    return row;
  }));
}

async function showBook(id) {
  if (selected !== id) hideBook();
  const path = '/shops/' + encodeURIComponent(id);
  const [book, {entries}] = await Promise.all([api(path), api(path + '/entries')]);
  selected = book.id;
  find('book-title').textContent = book.name;
  find('shop-id').textContent = book.id;
  find('subscription').textContent = {pending:'Ожидает активации',active:'Активна',expired:'Истекла'}[book.status];
  find('expiry').textContent = book.paidUntil ? date.format(new Date(book.paidUntil)) : '—';
  const active = book.status === 'active';
  find('activation-help').hidden = active;
  newClient.hidden = !active;
  entry.hidden = !active || !book.clients.length;
  entry.elements.client.replaceChildren(...book.clients.map(client => new Option(client.name,client.id)));
  const customers = new Map(book.clients.map(client => [client.id,{name:client.name,balance:0}]));
  for (const event of entries) {
    const client = customers.get(event.client_id);
    if (client) client.balance += event.kind === 'debt' ? event.amount : -event.amount;
  }
  rows('balances',[...customers.values()].map(client => `${client.name} · ${rubles.format(client.balance / 100)}`),'Клиентов пока нет.');
  rows('history',entries.reverse().map(event => {
    const type = event.kind === 'debt' ? 'Долг' : 'Оплата';
    const name = customers.get(event.client_id)?.name ?? 'Клиент';
    return `${date.format(new Date(event.created_at))} · ${name} · ${type} ${rubles.format(event.amount / 100)}${event.note ? ' · ' + event.note : ''}`;
  }),'Операций пока нет.');
  find('book').hidden = false;
}

async function listShops(id) {
  const data = await api('/shops');
  shops.elements.shop.replaceChildren(...data.shops.map(shop => new Option(shop.name,shop.id)));
  shops.hidden = !data.shops.length;
  if (id) shops.elements.shop.value = id;
  if (data.shops.length) await showBook(shops.elements.shop.value);
}

shops.onsubmit = event => {
  event.preventDefault();
  run(async () => { await showBook(shops.elements.shop.value); return 'Книга открыта.'; });
};
newShop.onsubmit = event => {
  event.preventDefault();
  run(async () => {
    const {shopId} = await api('/shops','POST',{name:newShop.elements.name.value});
    newShop.reset();
    await listShops(shopId);
    return 'Магазин создан.';
  });
};
newClient.onsubmit = event => {
  event.preventDefault();
  run(async () => {
    const key = selected;
    await api(`/shops/${key}/clients`,'POST',{id:crypto.randomUUID(),name:newClient.elements.name.value});
    newClient.reset();
    await showBook(key);
    return 'Клиент добавлен.';
  });
};
entry.onsubmit = event => {
  event.preventDefault();
  run(async () => {
    const key = selected;
    const value = entry.elements.amount.value.trim().match(/^(\d{1,10})(?:[.,](\d{1,2}))?$/);
    if (!value) return 'Введите сумму в рублях с точностью до копеек.';
    const amount = Number(value[1]) * 100 + Number((value[2] ?? '').padEnd(2,'0'));
    if (amount < 1 || amount > 100000000000) return 'Проверьте сумму операции.';
    const data = {clientId:entry.elements.client.value,kind:entry.elements.kind.value,amount,note:entry.elements.note.value.trim()};
    const fingerprint = JSON.stringify([key,data]);
    if (lastWrite?.fingerprint !== fingerprint) lastWrite = {fingerprint,id:crypto.randomUUID()};
    await api(`/shops/${key}/entries`,'POST',{...data,id:lastWrite.id});
    lastWrite = undefined;
    entry.reset();
    await showBook(key);
    return 'Операция сохранена.';
  });
};
find('copy').onclick = () => run(async () => {
  await navigator.clipboard.writeText(selected);
  return 'Номер магазина скопирован.';
});
find('signout').onclick = () => run(async () => {
  await api('/signout','POST');
  location.replace('/book/');
  return 'Вы вышли.';
});

run(async () => {
  const session = await api('/session');
  if (!session.signedIn) {
    find('signin').hidden = !session.enabled;
    return new URL(location.href).searchParams.has('login') ? 'Вход не завершён. Попробуйте снова.' : 'Войдите, чтобы открыть книгу магазина.';
  }
  find('workspace').hidden = find('signout').hidden = false;
  await listShops();
  return shops.hidden ? 'Создайте первый магазин.' : 'Книга открыта.';
});
