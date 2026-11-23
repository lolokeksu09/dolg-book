const find = id => document.getElementById(id);
const lookup = find('lookup');
const activate = find('activate');
const tokenInput = lookup.elements.token;
const paymentInput = activate.elements.payment;
const dialog = find('confirm');
const statuses = {pending: 'Ожидает активации', active: 'Активен', expired: 'Истёк'};
const formatter = new Intl.DateTimeFormat('ru-RU', {
  dateStyle: 'medium', timeStyle: 'short', timeZone: 'Europe/Moscow',
});
const date = value => value ? formatter.format(new Date(value)) + ' МСК' : '—';

let token = '';
let selected = null;
let pending = null;
let busy = false;

function showStatus(message) {
  find('status').textContent = message;
}

function showShop(shop) {
  selected = shop;
  find('shop-result').hidden = !shop;
  if (!shop) return;
  find('shop-name').textContent = shop.name;
  find('owner').textContent = shop.owners.map(owner => owner.email || owner.subject).join(', ');
  find('access').textContent = statuses[shop.status];
  find('expiry').textContent = date(shop.paidUntil);
  find('activation-action').textContent = shop.status === 'active' ? 'Продлить на месяц' : 'Активировать на месяц';
  const events = shop.events.map(event => {
    const row = document.createElement('li');
    row.textContent = `${date(event.activatedAt)} · Оплата ${event.paymentReference} · До ${date(event.paidUntil)} · Оператор: ${event.operator}`;
    return row;
  });
  find('events').replaceChildren(...events);
  find('empty').hidden = events.length > 0;
}

function setBusy(value) {
  busy = value;
  for (const button of document.querySelectorAll('button')) button.disabled = value;
}

function logout() {
  token = '';
  selected = pending = null;
  lookup.reset();
  activate.reset();
  find('token-label').hidden = false;
  tokenInput.required = true;
  find('logout').hidden = true;
  showShop(null);
}

async function api(path, method = 'GET', body) {
  const headers = {Authorization: `Bearer ${token}`};
  if (body) headers['Content-Type'] = 'application/json';
  const response = await fetch(path, {method, headers, body: body && JSON.stringify(body), cache: 'no-store', redirect: 'error'});
  if (!response.ok) {
    if (response.status === 401 || response.status === 403) {
      logout();
      throw Error('Нет доступа. Проверьте токен оператора.');
    }
    if (response.status === 409 && (await response.json()).error === 'subscription changed') {
      throw Error('Срок изменился. Найдите магазин снова и проверьте новую дату.');
    }
    throw Error({
      404: 'Магазин не найден.',
      409: 'Этот номер оплаты уже использован для другого магазина.',
      503: 'Служба входа недоступна.',
    }[response.status] || 'Запрос не выполнен.');
  }
  return response.json();
}

lookup.addEventListener('submit', async event => {
  event.preventDefault();
  if (busy) return;
  const id = lookup.elements.shop.value.trim().toLowerCase();
  const suppliedToken = tokenInput.value.trim();
  if (suppliedToken) token = suppliedToken;
  showShop(null);
  activate.reset();
  setBusy(true);
  showStatus('Ищем магазин…');
  try {
    showShop(await api('/admin/shops/' + encodeURIComponent(id)));
    find('token-label').hidden = true;
    tokenInput.required = false;
    tokenInput.value = '';
    find('logout').hidden = false;
    showStatus('Магазин найден.');
  } catch (error) {
    showStatus(error.message);
  } finally {
    setBusy(false);
  }
});
find('logout').onclick = () => {
  logout();
  showStatus('Вы вышли из админки.');
  tokenInput.focus();
};

activate.addEventListener('submit', event => {
  event.preventDefault();
  if (busy || !selected) return;
  pending = {shop: selected.id, paymentReference: paymentInput.value.trim(), expectedPaidUntil: selected.paidUntil};
  find('confirmation').textContent = `${selected.name}. Оплата ${pending.paymentReference}. Доступ до ${date(selected.nextPaidUntil)}.`;
  dialog.showModal();
});
find('cancel').onclick = () => dialog.close();
dialog.addEventListener('cancel', event => { if (busy) event.preventDefault(); });
dialog.addEventListener('close', () => { pending = null; });
find('apply').onclick = async () => {
  if (busy || !pending) return;
  const {shop, paymentReference, expectedPaidUntil} = pending;
  setBusy(true);
  try {
    const result = await api('/admin/shops/' + encodeURIComponent(shop) + '/activate', 'POST', {paymentReference, expectedPaidUntil});
    showShop(result.shop);
    activate.reset();
    showStatus(result.duplicate ? 'Эта оплата уже учтена. Срок остался прежним.' : `Доступ оплачен до ${date(result.shop.paidUntil)}.`);
  } catch (error) {
    showStatus(error.message);
  } finally {
    dialog.close();
    setBusy(false);
  }
};
