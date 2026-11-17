import {today,moneyToCents,balance,overdue,validateBook,addEntry,demoBook,activeEntries} from './ledger.js';
const $=s=>document.querySelector(s), $$=s=>[...document.querySelectorAll(s)];
const blank=()=>({version:2,clients:[],entries:[]});
let mode='own',book=blank(),filter='all',entryClient='',entryType='debt',detailId='',pending=null,loadBlocked=false,editClientId='',reverseEntryId='';
const key=()=>`dolgbook-v1-${mode}`;
const rub=n=>new Intl.NumberFormat('ru-RU',{style:'currency',currency:'RUB',maximumFractionDigits:n%100?2:0}).format(n/100);
const esc=s=>String(s).replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
const dateText=s=>new Intl.DateTimeFormat('ru-RU',{day:'numeric',month:'short',year:'numeric'}).format(new Date(s.length===10?s+'T12:00:00':s));
const id=()=>crypto.randomUUID();
function toast(message){const error=$('dialog[open] .dialog-error');if(error){error.textContent=message;error.hidden=false;}$('#toast').textContent=message;$('#toast').classList.add('show');clearTimeout(toast.timer);toast.timer=setTimeout(()=>$('#toast').classList.remove('show'),5500);}
function load(){loadBlocked=false;try{const raw=localStorage.getItem(key());book=raw?validateBook(JSON.parse(raw)):(mode==='demo'?demoBook():blank());}catch(e){book=blank();loadBlocked=true;toast('Не удалось прочитать записи. Изменения заблокированы. Сохраните исходную копию в разделе «Данные и копии».');}render();}
function save(next){if(loadBlocked)throw Error('Сначала сохраните исходную копию и восстановите книгу из корректного файла.');const clean=validateBook(next);try{localStorage.setItem(key(),JSON.stringify(clean));}catch(e){throw Error('Браузер не сохранил запись. Проверьте свободное место и разрешение на хранение данных.');}book=clean;render();}
function switchView(view){if(!['book','history','business','settings'].includes(view))view='book';$$('.view').forEach(el=>el.hidden=el.id!==`view-${view}`);$$('[data-view]').forEach(el=>el.classList.toggle('active',el.dataset.view===view));location.hash=view;}
$('#today').textContent=new Intl.DateTimeFormat('ru-RU',{day:'numeric',month:'long',year:'numeric'}).format(new Date());
$$('[data-view]').forEach(b=>b.addEventListener('click',()=>switchView(b.dataset.view)));
window.addEventListener('hashchange',()=>switchView(location.hash.slice(1)));
$('#switch-mode').onclick=()=>{mode=mode==='own'?'demo':'own';filter='all';$('#search').value='';load();toast(mode==='demo'?'Это вымышленные данные. Ваша книга хранится отдельно.':'Открыта ваша книга.');};
$$('[data-filter]').forEach(b=>b.onclick=()=>{filter=b.dataset.filter;render();});
$('#search').oninput=render;
function historyRows(entries){
  if(!entries.length)return '<div class="empty"><p>Операций пока нет.</p></div>';
  const reversed=new Set(book.entries.filter(e=>e.type==='reversal').map(e=>e.reverses));
  return [...entries].reverse().map(e=>{const c=book.clients.find(c=>c.id===e.clientId),cancelled=reversed.has(e.id),original=e.type==='reversal'?book.entries.find(x=>x.id===e.reverses):null;
    const label=original?`Отмена ${original.type==='debt'?'долга':'оплаты'}`:e.type==='payment'?'Принята оплата':'Записан долг';
    const sign=original?(original.type==='debt'?'−':'+'):e.type==='payment'?'−':'+';
    return `<div class="history-row ${cancelled?'cancelled':''}"><div><strong>${esc(c.name)}</strong><small>${label} · ${dateText(e.date)}${cancelled?' · Отменено':''}</small>${e.note?`<small>${esc(e.note)}</small>`:''}${e.due?`<small>Вернуть до ${dateText(e.due)}</small>`:''}</div><div class="history-amount ${e.type}"><strong>${sign}${rub(e.amount)}</strong></div></div>`;
  }).join('');
}
function render(){
  $('#mode-label').textContent=mode==='demo'?'ДЕМО · ВЫМЫШЛЕННЫЕ ДАННЫЕ':'МОЯ КНИГА';$('#mode-label').classList.toggle('demo',mode==='demo');$('#switch-mode').textContent=mode==='demo'?'Вернуться в мою книгу':'Посмотреть пример';
  const items=book.clients.map(c=>({...c,b:balance(book,c.id),o:overdue(book,c.id)}));
  $('#total').textContent=rub(items.reduce((n,c)=>n+c.b,0));const active=items.filter(c=>c.b>0).length;$('#active-count').textContent=`Клиентов с долгом: ${active}`;$('#overdue').textContent=rub(items.reduce((n,c)=>n+c.o,0));
  $('#paid-month').textContent=rub(activeEntries(book).filter(e=>{const d=new Date(e.date),n=new Date();return e.type==='payment'&&d.getMonth()===n.getMonth()&&d.getFullYear()===n.getFullYear();}).reduce((n,e)=>n+e.amount,0));
  $('#client-count').textContent=book.clients.length;$$('[data-filter]').forEach(b=>b.classList.toggle('selected',filter===b.dataset.filter));
  const q=$('#search').value.trim().toLowerCase(),shown=items.filter(c=>(`${c.name} ${c.phone}`.toLowerCase().includes(q))&&(filter==='all'||filter==='debt'&&c.b>0||filter==='overdue'&&c.o>0||filter==='closed'&&c.b===0)).sort((a,b)=>b.o-a.o||b.b-a.b||a.name.localeCompare(b.name,'ru'));
  $('#clients').innerHTML=shown.length?shown.map(c=>`<div class="client-row"><button class="client-person" data-detail="${esc(c.id)}"><span class="avatar">${esc(c.name.split(/\s+/).slice(0,2).map(n=>n[0]).join(''))}</span><span><strong>${esc(c.name)}</strong><small>${esc(c.phone||'Открыть историю клиента')}</small></span></button><div class="balance">${rub(c.b)}<small>${c.b?'остаток долга':'всё оплачено'}</small></div><div class="status ${c.o?'late':''}">${c.o?'◷ Срок прошёл':c.b?'Ведём учёт':'✓ Расчёт закрыт'}</div><div class="row-actions"><button class="debt" data-entry="debt" data-client="${esc(c.id)}">＋ В долг</button><button class="pay" data-entry="payment" data-client="${esc(c.id)}" ${c.b?'':'disabled'}>Принять оплату</button></div></div>`).join(''):`<div class="empty"><div class="empty-symbol">д.</div><h3>${book.clients.length?'Ничего не найдено':'Начните с первого клиента'}</h3><p>${book.clients.length?'Попробуйте другое имя или измените фильтр.':'Добавьте имя, запишите сумму — остаток посчитается автоматически.'}</p>${book.clients.length?'':'<button class="secondary" id="empty-add">＋ Добавить клиента</button>'}</div>`;
  $('#empty-add')?.addEventListener('click',openClient);$$('[data-entry]').forEach(b=>b.onclick=()=>openEntry(b.dataset.client,b.dataset.entry));$$('[data-detail]').forEach(b=>b.onclick=()=>openDetail(b.dataset.detail));
  $('#history').innerHTML=historyRows(book.entries);$('#book-note').textContent=loadBlocked?'Ошибка чтения: исходные записи сохранены в браузере, изменения заблокированы.':mode==='demo'?'Режим примера: все имена, суммы и операции вымышлены. Ваша книга хранится отдельно.':'Записи сохраняются только в этом браузере. Синхронизация между устройствами пока не подключена.';
  try{const last=localStorage.getItem(key()+'-backup');$('#backup-status').textContent=last?'Последняя выгрузка: '+dateText(last):'Резервная копия ещё не выгружалась.';}catch{}
}
function openClient(clientId=''){
  if(loadBlocked)return toast('Восстановите книгу в разделе «Данные и копии».');
  editClientId=typeof clientId==='string'?clientId:'';
  $('#client-form').reset();$('#client-dialog .dialog-error').hidden=true;
  const c=book.clients.find(c=>c.id===editClientId);
  if(c){$('#client-form [name=name]').value=c.name;$('#client-form [name=phone]').value=c.phone;}
  $('#client-submit-debt').hidden=!!c;$('#client-title').textContent=c?'Изменить клиента':'Новый клиент';$('#client-submit').textContent=c?'Сохранить изменения':'Создать карточку';$('#client-hint').textContent=c?'История и остаток клиента сохранятся.':'После создания карточки можно записать долг.';
  $('#client-dialog').showModal();
}
$('#add-client').onclick=()=>openClient();
$('#client-form').onsubmit=e=>{e.preventDefault();try{
  const f=new FormData(e.target),name=f.get('name').trim(),phone=f.get('phone').trim();if(!name)throw Error('Введите имя клиента.');
  const newClientId=editClientId||id();
  save({...book,clients:editClientId?book.clients.map(c=>c.id===editClientId?{...c,name,phone}:c):[...book.clients,{id:newClientId,name,phone}]});
  const addDebt=!editClientId&&e.submitter?.id==='client-submit-debt';
  $('#client-dialog').close();if(editClientId)openDetail(editClientId);else if(addDebt)openEntry(newClientId,'debt');else toast('Карточка клиента создана.');
}catch(err){toast(err.message);}};
function openEntry(clientId,type){entryClient=clientId;entryType=type;const c=book.clients.find(c=>c.id===clientId);$('#entry-form').reset();$('#entry-dialog .dialog-error').hidden=true;$('#entry-client').textContent=c.name;$('#entry-title').textContent=type==='debt'?'Записать долг':'Принять оплату';$('#entry-balance').textContent='Текущий остаток: '+rub(balance(book,clientId));$('#due-label').hidden=type==='payment';$('#entry-submit').textContent=type==='debt'?'Записать долг':'Сохранить оплату';$('#entry-dialog').showModal();}
$('#entry-form').onsubmit=e=>{e.preventDefault();try{const f=new FormData(e.target),amount=moneyToCents(f.get('amount'));if(entryType==='payment'&&amount>balance(book,entryClient))throw Error('Оплата превышает остаток долга.');save(addEntry(book,{id:id(),clientId:entryClient,type:entryType,amount,date:new Date().toISOString(),due:entryType==='debt'?f.get('due'):'',note:f.get('note').trim()}));$('#entry-dialog').close();toast(entryType==='debt'?'Долг записан.':'Оплата сохранена. Остаток пересчитан.');}catch(err){toast(err.message);}};
function openDetail(clientId){
  detailId=clientId;const c=book.clients.find(c=>c.id===clientId),last=activeEntries(book).filter(e=>e.clientId===clientId).at(-1);
  $('#detail-name').textContent=c.name;
  $('#detail-content').innerHTML=`<p>${esc(prefs.shop)} · ${esc(c.phone)} · Выписка на ${dateText(today())}${mode==='demo'?' · ДЕМО':''}</p><p class="muted">Долги и оплаты из книги магазина. Не является кассовым чеком.</p><div class="detail-balance">Остаток: ${rub(balance(book,clientId))}</div>${historyRows(book.entries.filter(e=>e.clientId===clientId))}`;
  $('#detail-reverse').disabled=!last;$('#detail-dialog').showModal();
}
$('#detail-edit').onclick=()=>{$('#detail-dialog').close();openClient(detailId);};
$('#detail-reverse').onclick=()=>{
  const e=activeEntries(book).filter(e=>e.clientId===detailId).at(-1);if(!e)return;
  reverseEntryId=e.id;$('#detail-dialog').close();$('#reverse-form').reset();$('#reverse-dialog .dialog-error').hidden=true;
  const after=balance(book,detailId)+(e.type==='debt'?-e.amount:e.amount);
  $('#reverse-description').textContent=`${book.clients.find(c=>c.id===detailId).name}: ${e.type==='debt'?'долг':'оплата'} ${rub(e.amount)} от ${dateText(e.date)}. Остаток после отмены: ${rub(after)}.`;
  $('#reverse-dialog').showModal();
};
$('#reverse-form').onsubmit=e=>{e.preventDefault();try{
  const original=book.entries.find(e=>e.id===reverseEntryId),reason=new FormData(e.target).get('reason').trim();if(!original||!reason)throw Error('Укажите причину отмены.');
  save(addEntry(book,{id:id(),clientId:original.clientId,type:'reversal',amount:original.amount,date:new Date().toISOString(),due:'',note:reason,reverses:original.id}));
  $('#reverse-dialog').close();openDetail(original.clientId);
}catch(err){toast(err.message);}};
$('#print-statement').onclick=()=>window.print();$$('[data-close]').forEach(b=>b.onclick=()=>b.closest('dialog').close());
function download(name,data,type){const url=URL.createObjectURL(new Blob([data],{type})),a=document.createElement('a');a.href=url;a.download=name;a.click();setTimeout(()=>URL.revokeObjectURL(url),1000);}
$('#export-json').onclick=()=>{try{const raw=loadBlocked?localStorage.getItem(key()):JSON.stringify(book,null,2);if(!raw)throw Error('Нет данных для выгрузки.');download(`dolgbook-${mode}-${today()}.json`,raw,'application/json');localStorage.setItem(key()+'-backup',new Date().toISOString());render();toast('Файл передан браузеру для скачивания.');}catch(e){toast(e.message);}};
$('#export-csv').onclick=()=>{if(loadBlocked)return toast('Сначала выгрузите исходную JSON-копию.');const cell=v=>'"'+String(/^[=+\-@\t\r]/.test(String(v))?"'"+v:v).replace(/"/g,'""')+'"';const rows=[['Клиент','Телефон','Дата','Тип','Сумма, ₽','Вернуть до','Комментарий','ID операции','Отменяет ID'],...book.entries.map(e=>{const c=book.clients.find(c=>c.id===e.clientId);return[c.name,c.phone,e.date,e.type==='debt'?'Долг':e.type==='payment'?'Оплата':'Отмена',(e.amount/100).toFixed(2).replace('.',','),e.due,e.note,e.id,e.reverses||''];})];download(`dolgbook-${mode}-${today()}.csv`,'\ufeff'+rows.map(r=>r.map(cell).join(';')).join('\r\n'),'text/csv;charset=utf-8');toast('CSV передан браузеру для скачивания.');};
$('#import-json').onchange=async e=>{const file=e.target.files[0];e.target.value='';if(!file)return;try{if(file.size>5*1024*1024)throw Error('Файл больше 5 МБ.');pending=validateBook(JSON.parse(await file.text()));$('#confirm-text').textContent=`Будет заменена ${mode==='demo'?'книга примера':'ваша книга'}: ${book.clients.length} клиентов, ${book.entries.length} операций. В файле: ${pending.clients.length} клиентов, ${pending.entries.length} операций. Текущие записи будут заменены. Сначала сохраните резервную копию.`;$('#confirm-dialog').showModal();}catch(err){pending=null;toast(err instanceof SyntaxError?'Файл не является корректным JSON.':err.message);}};
$('#confirm-cancel').onclick=()=>{pending=null;$('#confirm-dialog').close();};$('#confirm-dialog').addEventListener('cancel',()=>pending=null);
$('#confirm-import').onclick=()=>{if(!pending)return;try{const wasBlocked=loadBlocked;loadBlocked=false;try{save(pending);}catch(e){loadBlocked=wasBlocked;throw e;}pending=null;$('#confirm-dialog').close();toast('Книга восстановлена.');}catch(err){toast(err.message);}};
function calculate(){const price=Number($('#price').value),target=Number($('#target').value);if(!Number.isFinite(price)||!Number.isFinite(target)||price<1||price>1000000||target<1||target>1000000000){$('#needed').textContent='—';$('#calc-detail').textContent='Введите положительную цену до 1 млн ₽ и цель до 1 млрд ₽.';return;}const n=Math.ceil(target/price);$('#needed').textContent=new Intl.NumberFormat('ru-RU').format(n);$('#calc-detail').textContent=`${new Intl.NumberFormat('ru-RU').format(n)} × ${rub(Math.round(price*100))} = ${rub(Math.round(n*price*100))} выручки в месяц. Это масштаб требуемой базы, а не оценка спроса.`;}
$('#price').oninput=calculate;$('#target').oninput=calculate;
$('#copy-offer').onclick=async()=>{try{await navigator.clipboard.writeText($('#offer').textContent);toast('Текст скопирован.');}catch{toast('Браузер не разрешил копирование. Выделите текст вручную.');}};
window.addEventListener('storage',e=>{if(e.key===key())load();});
let prefs={shop:'Мой магазин',tablet:false};
try{const p=JSON.parse(localStorage.getItem('dolgbook-preferences')||'null');if(p){if(typeof p.shop==='string'&&p.shop.trim()&&p.shop.length<=80)prefs.shop=p.shop.trim();prefs.tablet=p.tablet===true;}}catch{}
function applyPrefs(){document.body.classList.toggle('tablet-mode',prefs.tablet);$('#tablet-mode').setAttribute('aria-pressed',String(prefs.tablet));$('#tablet-mode').textContent=prefs.tablet?'Обычный режим':'Крупный режим';$('#shop-title').textContent=prefs.shop;$('#shop-name').value=prefs.shop;}
function savePrefs(next){try{localStorage.setItem('dolgbook-preferences',JSON.stringify(next));prefs=next;applyPrefs();}catch{toast('Не удалось сохранить настройки браузера.');}}
$('#tablet-mode').onclick=()=>savePrefs({...prefs,tablet:!prefs.tablet});
$('#shop-form').onsubmit=e=>{e.preventDefault();const name=$('#shop-name').value.trim();if(!name)return toast('Введите название магазина.');savePrefs({...prefs,shop:name});};
applyPrefs();switchView(location.hash.slice(1)||'book');load();calculate();
