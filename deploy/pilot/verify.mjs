import { readFileSync } from 'node:fs';
import { randomUUID } from 'node:crypto';

const book = 'https://raschet.135-106-227-179.sslip.io';
const login = 'https://login-raschet.135-106-227-179.sslip.io';
const realm = '/realms/raschet-pilot';
let step = 'Чтение тестовых аккаунтов';
class VerificationError extends Error {}
const check = (condition, message) => { if (!condition) throw new VerificationError(message); };
const htmlAttribute = value => value.replace(/&(?:amp|quot|apos|#39|#x27|#34);/g, entity => ({
  '&amp;':'&', '&quot;':'"', '&apos;':"'", '&#39;':"'", '&#x27;':"'", '&#34;':'"',
}[entity]));

function browser() {
  const cookieJars = new Map([[book,new Map()], [login,new Map()]]);
  return async function request(url, {method='GET', body, origin}={}) {
    url = new URL(url, book);
    const jar = cookieJars.get(url.origin);
    check(jar && !url.username && !url.password, 'Получен неожиданный адрес сервиса.');
    const headers = new Headers();
    if (jar.size) headers.set('cookie', [...jar].map(([key,value])=>key+'='+value).join('; '));
    if (origin) headers.set('origin',origin);
    if (body && !(body instanceof URLSearchParams)) {
      headers.set('content-type','application/json');
      body = JSON.stringify(body);
    }
    const response = await fetch(url,{method,body,headers,redirect:'manual',signal:AbortSignal.timeout(15_000)});
    for (const cookie of response.headers.getSetCookie()) {
      const pair = cookie.split(';',1)[0];
      const eq = pair.indexOf('=');
      const name = pair.slice(0,eq);
      if (eq > 0) {
        if (!pair.slice(eq+1) || /;\s*Max-Age=0(?:;|$)/i.test(cookie)) jar.delete(name);
        else jar.set(name,pair.slice(eq+1));
      }
    }
    return response;
  };
}

function location(response, origin, path) {
  const target = new URL(response.headers.get('location') || '/', origin);
  check(target.origin === origin && target.pathname === path,'Получен неожиданный маршрут входа.');
  return target;
}

async function signIn(username, password) {
  const request = browser();
  const next = username === 'operator' ? '/admin/' : '/book/';
  const start = await request(username === 'operator' ? '/signin?next=admin' : '/signin');
  check(start.status === 303,'Сервис не начал вход.');
  const authorize = location(start,login,realm+'/protocol/openid-connect/auth');
  const page = await request(authorize);
  check(page.status === 200,'Страница входа Keycloak недоступна.');
  const form = (await page.text()).match(/<form\b[^>]*\bid=["']kc-form-login["'][^>]*>/i)?.[0];
  const action = form?.match(/\baction=(["'])(.*?)\1/i)?.[2];
  check(action,'Стандартная форма входа Keycloak не найдена.');
  const submit = new URL(htmlAttribute(action),login);
  check(submit.origin === login && submit.pathname === realm+'/login-actions/authenticate','Адрес формы Keycloak изменился.');
  const authorized = await request(submit,{method:'POST',origin:login,body:new URLSearchParams({username,password})});
  check([302,303].includes(authorized.status),'Keycloak не принял тестовые учётные данные.');
  const callback = location(authorized,book,'/signin/callback');
  check(callback.searchParams.has('code'),'Keycloak не вернул код входа.');
  const closed = await request(callback);
  check(closed.status === 303 && !location(closed,book,next).searchParams.has('login'),'Сервис не открыл сессию.');
  const session = await request('/session');
  check(session.ok,'Сервис не подтвердил сессию.');
  const data = await session.json();
  check(data.signedIn === true && data.operator === (username === 'operator'),'Сессия получила неверную роль.');
  return request;
}

async function api(request, path, expectedStatus, body) {
  const response = await request(path,body ? {method:'POST',origin:book,body} : {});
  check(response.status === expectedStatus,`Получен HTTP ${response.status}, ожидался ${expectedStatus}.`);
  return response.json();
}

try {
  const accounts = new Map(readFileSync('/run/raschet-pilot-accounts','utf8').trim().split(/\r?\n/)
    .map(line=>line.trim().split(/\s+/,2)));
  check(accounts.get('buyer') && accounts.get('operator'),'В файле отсутствуют тестовые аккаунты.');
  step = 'Вход покупателя';
  const buyer = await signIn('buyer',accounts.get('buyer'));
  step = 'Вход оператора';
  const operator = await signIn('operator',accounts.get('operator'));
  step = 'Создание вымышленного магазина';
  const runId = randomUUID();
  const {shopId} = await api(buyer,'/shops',201,{name:`Проверка пилота ${runId.slice(0,8)}`});
  check(/^[0-9a-f-]{36}$/.test(shopId),'Некорректный номер тестового магазина.');
  const path = '/shops/'+shopId;
  const adminPath = '/admin'+path;
  const client = {id:randomUUID(),name:'Тестовый клиент'};
  step = 'Проверка прав и доступа до активации';
  await api(buyer,adminPath,403);
  await api(operator,path,403);
  await api(buyer,path+'/clients',402,client);
  step = 'Активация и повтор подтверждения';
  const shop = await api(operator,adminPath,200);
  const activation = {paymentReference:'SMOKE-'+runId,expectedPaidUntil:shop.paidUntil};
  const active = await api(operator,adminPath+'/activate',201,activation);
  const repeated = await api(operator,adminPath+'/activate',200,activation);
  check(repeated.duplicate && active.shop.paidUntil === repeated.shop.paidUntil,'Повтор оплаты изменил срок.');
  step = 'Запись долга и частичной оплаты';
  await api(buyer,path+'/clients',201,client);
  await api(buyer,path+'/entries',201,{id:randomUUID(),clientId:client.id,kind:'debt',amount:35000,note:''});
  await api(buyer,path+'/entries',201,{id:randomUUID(),clientId:client.id,kind:'payment',amount:10000,note:''});
  const {entries} = await api(buyer,path+'/entries',200);
  const balance = entries.reduce((sum,row)=>sum+(row.kind==='debt' ? row.amount : -row.amount),0);
  check(entries.length === 2 && balance === 25000,'История или остаток расходятся с ожидаемыми.');
  console.log('PILOT_E2E_OK — долг 350 ₽, оплата 100 ₽, остаток 250 ₽.');
} catch (error) {
  // Never print the request, response, URLs with OAuth parameters or secrets.
  const message = error instanceof VerificationError
    ? error.message : 'Ошибка связи с сервисом.';
  console.error(`PILOT_E2E_FAILED — ${step}. ${message}`);
  process.exitCode = 1;
}
