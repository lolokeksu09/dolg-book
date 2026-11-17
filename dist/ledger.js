export const MAX_AMOUNT = 100000000000;
export function today() { const d=new Date(); return `${d.getFullYear()}-${String(d.getMonth()+1).padStart(2,'0')}-${String(d.getDate()).padStart(2,'0')}`; }
export function moneyToCents(value) {
  const s=String(value).trim().replace(/\s/g,'').replace(',','.');
  if(!/^\d{1,10}(?:\.\d{1,2})?$/.test(s)) throw Error('Введите сумму больше нуля, до двух знаков после запятой.');
  const [a,b='']=s.split('.'); const n=Number(a)*100+Number(b.padEnd(2,'0'));
  if(!Number.isSafeInteger(n)||n<=0||n>MAX_AMOUNT) throw Error('Сумма должна быть от 0,01 до 1 000 000 000 ₽.');
  return n;
}
export function activeEntries(book){const reversed=new Set(book.entries.filter(e=>e.type==='reversal').map(e=>e.reverses));return book.entries.filter(e=>e.type!=='reversal'&&!reversed.has(e.id));}
export const balance=(book,id)=>activeEntries(book).filter(e=>e.clientId===id).reduce((n,e)=>n+(e.type==='debt'?e.amount:-e.amount),0);
export function overdue(book,id,date=today()) {
  const es=activeEntries(book).filter(e=>e.clientId===id);
  let payment=es.filter(e=>e.type==='payment').reduce((n,e)=>n+e.amount,0),sum=0;
  for(const e of es.filter(e=>e.type==='debt')) { const paid=Math.min(payment,e.amount);payment-=paid;if(e.due&&e.due<date)sum+=e.amount-paid; }
  return sum;
}
export function validateBook(book) {
  if(!book||![1,2].includes(book.version)||!Array.isArray(book.clients)||!Array.isArray(book.entries)||book.clients.length>10000||book.entries.length>100000)throw Error('Неверный формат книги. Нужна JSON-копия из этого приложения.');
  const clients=new Set(),entries=new Map(),balances=new Map(),stacks=new Map();let gross=0;
  const str=(v,max)=>typeof v==='string'&&v.length<=max;
  const validDate=v=>/^\d{4}-\d{2}-\d{2}$/.test(v)&&!Number.isNaN(Date.parse(v+'T12:00:00Z'))&&new Date(v+'T12:00:00Z').toISOString().slice(0,10)===v;
  for(const c of book.clients){if(!str(c.id,100)||!c.id||clients.has(c.id)||!str(c.name,80)||!c.name.trim()||!str(c.phone,30))throw Error('Некорректные данные клиента.');clients.add(c.id);balances.set(c.id,0);stacks.set(c.id,[]);}
  for(const e of book.entries){
    if(!str(e.id,100)||!e.id||entries.has(e.id)||!clients.has(e.clientId)||!['debt','payment','reversal'].includes(e.type)||!Number.isSafeInteger(e.amount)||e.amount<=0||e.amount>MAX_AMOUNT||!str(e.note,200)||!str(e.date,40)||Number.isNaN(Date.parse(e.date))||!str(e.due,10)||(e.due&&!validDate(e.due))||(e.type!=='debt'&&e.due))throw Error('Некорректная операция в книге.');
    let delta=e.type==='debt'?e.amount:-e.amount;
    if(e.type==='reversal'){
      const original=entries.get(e.reverses),stack=stacks.get(e.clientId);
      if(!original||original.type==='reversal'||original.clientId!==e.clientId||original.amount!==e.amount||stack.at(-1)!==original.id||!e.note.trim())throw Error('Можно отменить только последнюю действующую запись клиента. Укажите причину отмены.');
      stack.pop();delta=original.type==='debt'?-e.amount:e.amount;
    }else{if(e.reverses)throw Error('Неверная ссылка отмены.');stacks.get(e.clientId).push(e.id);}
    gross+=e.amount;if(!Number.isSafeInteger(gross))throw Error('Слишком большой оборот для точного расчёта.');entries.set(e.id,e);const n=balances.get(e.clientId)+delta;if(!Number.isSafeInteger(n)||n<0)throw Error('Оплата в книге превышает долг клиента.');balances.set(e.clientId,n);
  }
  return {version:2,clients:book.clients.map(c=>({id:c.id,name:c.name.trim(),phone:c.phone})),entries:book.entries.map(e=>({id:e.id,clientId:e.clientId,type:e.type,amount:e.amount,date:e.date,due:e.due,note:e.note,...(e.type==='reversal'?{reverses:e.reverses}:{})}))};
}
export function addEntry(book,entry){return validateBook({...book,entries:[...book.entries,entry]});}
export function demoBook(){
  const date=today(),ago=new Date();ago.setDate(ago.getDate()-4);const old=`${ago.getFullYear()}-${String(ago.getMonth()+1).padStart(2,'0')}-${String(ago.getDate()).padStart(2,'0')}`;
  const names=['Амина М.','Марат К.','Зарема А.','Руслан Б.'];
  const clients=names.map((name,i)=>({id:`demo-${i}`,name,phone:'Пример клиента'}));
  const entries=clients.map((c,i)=>({id:`d-${i}`,clientId:c.id,type:'debt',amount:[485000,270000,196000,85000][i],date:old+'T12:00:00.000Z',due:i===1?old:date,note:'Пример: продукты в долг'}));
  entries.push({id:'p-1',clientId:'demo-0',type:'payment',amount:150000,date:new Date().toISOString(),due:'',note:'Пример: часть оплаты'},{id:'p-2',clientId:'demo-3',type:'payment',amount:85000,date:new Date().toISOString(),due:'',note:'Пример: оплачено полностью'});
  return validateBook({version:1,clients,entries});
}
