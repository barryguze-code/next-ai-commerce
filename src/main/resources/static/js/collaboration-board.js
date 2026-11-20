(()=>{
'use strict';
const root=document.getElementById('task-board');if(!root?.dataset.parent)return;
const parent=root.dataset.parent,user=root.dataset.user,filters=document.getElementById('board-filters'),feedback=document.getElementById('board-feedback');
const state=NextAiCollaborationBoardState.create(root,user,parent),prefs=state.preferences(),columns=[...root.querySelectorAll('.kanban-column')];
const cursors=new WeakMap(),accountNames=new Map([...filters.account.options].map(o=>[o.value,o.textContent])),storeNames=new Map([...filters.store.options].map(o=>[o.value,o.textContent]));
let mine=false,epoch=0,revision=0,asOf=null,detailId=null,detailEpoch=0;
const zone=Intl.DateTimeFormat().resolvedOptions().timeZone||'America/Los_Angeles';
const positionKey='nextai.collaboration.position:'+encodeURIComponent(user)+':'+parent;
const make=(tag,text,cls)=>{const n=document.createElement(tag);if(text!=null)n.textContent=text;if(cls)n.className=cls;return n;};
function params(){const p=new URLSearchParams(new FormData(filters));p.set('parent',parent);p.set('mine',String(mine));p.set('zone',zone);return p;}
function saveChoices(){state.savePreferences({initialCards:Number(filters.size.value),scope:JSON.stringify({account:filters.account.value,store:filters.store.value,q:filters.q.value,mine}),groups:[...root.querySelectorAll('details[data-group][open]')].map(n=>n.dataset.group)});}
try{const scope=JSON.parse(prefs.scope);if([...filters.account.options].some(o=>o.value===scope.account))filters.account.value=scope.account;if([...filters.store.options].some(o=>o.value===scope.store))filters.store.value=scope.store;filters.q.value=typeof scope.q==='string'?scope.q:'';mine=scope.mine===true;}catch(_){}
filters.size.value=prefs.initialCards;for(const b of filters.querySelectorAll('[data-mine]'))b.setAttribute('aria-pressed',String((b.dataset.mine==='true')===mine));
for(const d of root.querySelectorAll('details[data-group]'))d.open=prefs.groups.includes(d.dataset.group);
function remember(){const p=state.capture();delete p.focused;try{sessionStorage.setItem(positionKey,JSON.stringify(p))}catch(_){}saveChoices();}
window.addEventListener('pagehide',remember);
async function json(url,options){const r=await fetch(url,{...options,headers:{Accept:'application/json',...options?.headers},cache:'no-store'});if(r.status===401||r.status===403){state.invalidate();
 epoch++; detailEpoch++;
 root.querySelectorAll('[data-cards]').forEach(list=>list.replaceChildren());
 const detail=document.getElementById('board-detail');
 detail.querySelector('[data-messages]').replaceChildren();
 detail.querySelector('[data-title]').textContent='Task';
 document.querySelectorAll('#board-detail[open],#board-new[open],#task-board dialog[open]').forEach(dialog=>dialog.close());
 feedback.textContent='Your access changed. Reload to choose an available scope.';
 throw Error('Your access changed. Reload to choose an available scope.');}let data;try{data=await r.json()}catch(_){throw Error('Please sign in again or retry.');}if(!r.ok)throw Error(data.error||'The task could not be updated.');return data;}
async function post(path,values){const body=new FormData(document.getElementById('board-csrf'));body.set('parent',parent);for(const [k,v] of Object.entries(values)){if(Array.isArray(v))v.forEach(x=>body.append(k,x));else if(v!=null)body.set(k,v);}return json('/app/collaboration/board/'+path,{method:'POST',body});}
let dragged=null;
function enableQueueMove(article,task,actions){
 const moveTo=position=>mutate(article,'tasks/'+task.id+'/position',{position,revision:task.queueRevision});
 const grip=make('span','⠿','queue-grip');
 grip.draggable=true;
 grip.title='Drag to another active task to change queue position';
 grip.setAttribute('aria-hidden','true');
 grip.ondragstart=event=>{
  dragged={parent,id:task.id,revision:task.queueRevision};
  event.dataTransfer.effectAllowed='move';
  event.dataTransfer.setData('text/plain',task.id);
 };
 grip.ondragend=()=>{dragged=null;};
 article.ondragover=event=>{if(dragged?.parent===parent&&dragged.id!==task.id)event.preventDefault();};
 article.ondrop=async event=>{
  event.preventDefault();
  const source=dragged;dragged=null;
  if(!source||source.parent!==parent||source.id===task.id)return;
  const original=root.querySelector('[data-card-id="'+CSS.escape(source.id)+'"]');
  if(original)await mutate(original,'tasks/'+source.id+'/position',{position:task.position,revision:source.revision});
 };
 actions.append(grip);
 for(const [offset,label] of [[-1,'Move earlier'],[1,'Move later']]){
  const button=make('button',offset<0?'↑':'↓');
  button.type='button';button.title=label;button.setAttribute('aria-label',label+' in tenant queue');
  button.dataset.focusKey=offset<0?'earlier':'later';button.disabled=task.position+offset<1;
  button.onclick=()=>moveTo(task.position+offset);actions.append(button);
 }
}
function card(task){
 const article=make('article',null,'kanban-card');article.dataset.cardId=task.id;article.tabIndex=-1;
 const heading=make('h3'),open=make('button',task.title);open.type='button';open.dataset.open='';open.dataset.focusKey='open';open.onclick=()=>openDetail(task,open);heading.append(open);article.append(heading);
 article.append(make('small',task.restrictedContext?'Restricted record context':task.accountId?(accountNames.get(task.accountId)||'Restricted account')+(task.storeId?' · '+(storeNames.get(task.storeId)||'Restricted marketplace'):''):'Parent task'));
 const assign=make('button',task.assignees||'Assigned to creator','assignment-button');assign.type='button';assign.dataset.focusKey='assignees';assign.onclick=()=>assignments(task,assign);article.append(assign);if(task.dueDate)article.append(make('small','Due '+task.dueDate));
 const actions=make('div',null,'card-actions');
 if(task.position!=null){enableQueueMove(article,task,actions);const label=make('label','Position '),input=make('input');input.type='number';input.min='1';input.value=task.position;input.setAttribute('aria-label','Queue position for '+task.title);input.dataset.focusKey='position';input.onchange=()=>mutate(article,'tasks/'+task.id+'/position',{position:input.value,revision:task.queueRevision});label.append(input);actions.append(label);}
 const select=make('select');select.setAttribute('aria-label','Status for '+task.title);select.dataset.focusKey='state';for(const [value,label] of [['OPEN','Open'],['IN_PROGRESS','In progress'],['DONE','Done']]){const o=make('option',label);o.value=value;o.selected=value===task.state;select.append(o);}select.onchange=()=>mutate(article,'tasks/'+task.id+'/state',{state:select.value,revision});actions.append(select);article.append(actions);return article;
}
async function assignments(task,trigger){
 try{const data=await json('/app/collaboration/board/tasks/'+task.id+'/assignees?parent='+parent),dialog=make('dialog',null,'invite-dialog'),form=make('form',null,'dialog-shell'),header=make('header',null,'dialog-header'),body=make('div',null,'dialog-body'),footer=make('footer',null,'dialog-actions');
 header.append(make('h2','Assigned to'));for(const person of data.people){const label=make('label'),input=make('input');input.type='checkbox';input.name='people';input.value=person.id;input.checked=data.assigned.includes(person.id);label.append(input,document.createTextNode(person.name));body.append(label);}
 const error=make('p');error.setAttribute('role','status');body.append(error);const cancel=make('button','Cancel'),save=make('button','Apply','primary-button');cancel.type='button';cancel.onclick=()=>dialog.close();save.type='submit';const remove=make('button','Remove myself');remove.type='button';remove.onclick=async()=>{try{await post('tasks/'+task.id+'/leave-assignment',{});dialog.close();await refresh();}catch(e){error.textContent=e.message;}};footer.append(remove,cancel,save);form.append(header,body,footer);dialog.append(form);root.append(dialog);dialog.addEventListener('close',()=>{dialog.remove();trigger.focus({preventScroll:true});});
 form.onsubmit=async e=>{e.preventDefault();save.disabled=true;try{await post('tasks/'+task.id+'/assignees',{people:[...form.querySelectorAll('input:checked')].map(n=>n.value)});dialog.close();await refresh();}catch(e){error.textContent=e.message;}finally{save.disabled=false;}};dialog.showModal();
 }catch(e){feedback.textContent=e.message;}
}
async function mutate(element,path,values){
 const anchor=state.capture(),controls=[...element.querySelectorAll('button,input,select')].filter(n=>!n.disabled);
 controls.forEach(n=>n.disabled=true);
 try{await post(path,values);await refresh();feedback.textContent='Saved.';}
 catch(error){await refresh(false);feedback.textContent=error.message;}
 finally{controls.forEach(n=>n.disabled=false);state.restore(anchor);}
}
function rankedKey(column){return positionKey+':ranked:'+JSON.stringify([column,filters.account.value,filters.store.value,filters.q.value,mine,filters.size.value]);}
async function load(container,column,group,append,request,currentEpoch){
 const p=params();p.set('state',column);p.set('size',column==='DONE'&&!group?'5':filters.size.value);if(asOf)p.set('asOf',asOf);if(group)p.set('group',group);
 const prior=cursors.get(container);if(append&&prior){if(column==='DONE'){p.set('before',prior.closedAt);p.set('beforeId',prior.id);}else p.set('after',prior.position);}
 const data=await json('/app/collaboration/board/data?'+p,{signal:request?.signal});if(currentEpoch!==epoch||(request&&!request.current()))return;
 revision=Math.max(revision,data.revision);if(column==='DONE'&&!group){
  asOf=data.asOf;
  for(const section of container.querySelectorAll('details[data-group]')){
   const count=data.groups?.[section.dataset.group]||0;
   section.hidden=count===0;section.querySelector('[data-group-count]').textContent=String(count);
  }
 }
 const ranked=container.querySelector(':scope > [data-ranked]');
 const extra=ranked?.querySelector('[data-ranked-cards]');
 if(ranked&&!append){extra.replaceChildren();ranked.hidden=!data.more;}
 const list=append&&extra?extra:container.querySelector(':scope > [data-cards]');if(!append)list.replaceChildren();data.cards.forEach(c=>list.append(card({...c,queueRevision:data.revision})));if(!list.children.length)list.append(make('p','No tasks in this view.','muted'));
 cursors.set(container,data.cards.at(-1)||prior);const more=container.querySelector(':scope > [data-more]');more.hidden=!data.more||(column==='DONE'&&!group);if(ranked){
  more.hidden=!data.more||!ranked.open;
  ranked.ontoggle=()=>{try{sessionStorage.setItem(rankedKey(column),JSON.stringify({open:ranked.open,count:extra.children.length}));}catch{}more.hidden=!ranked.open||!data.more;if(ranked.open&&data.more&&!extra.children.length&&!more.disabled)more.click();};
 }
 if(group&&append)saveGroup(container);
 if(ranked&&append){try{sessionStorage.setItem(rankedKey(column),JSON.stringify({open:ranked.open,count:extra.children.length}));}catch{}}
 more.onclick=async()=>{const anchor=state.capture();more.disabled=true;try{await load(container,column,group,true,null,epoch);state.restore(anchor);}catch(e){(container.querySelector('[data-group-error]')||feedback).textContent=e.message;}finally{more.disabled=false;}};
}
async function refresh(clear=true){const anchor=state.capture(),request=state.request(),currentEpoch=++epoch;asOf=null;revision=0;if(clear)feedback.textContent='Loading tasks…';saveChoices();try{
 await Promise.all(columns.map(c=>load(c,c.dataset.state,null,false,request,currentEpoch)));
 if(!request.current())return;
 for(const column of columns){
  const ranked=column.querySelector('[data-ranked]');if(!ranked||ranked.hidden)continue;
  let saved;try{saved=JSON.parse(sessionStorage.getItem(rankedKey(column.dataset.state)));}catch{}
  ranked.open=Boolean(saved?.open);
  if(!ranked.open)continue;
  const more=column.querySelector(':scope > [data-more]'),extra=ranked.querySelector('[data-ranked-cards]');
  ranked.ontoggle=null;
  do{await load(column,column.dataset.state,null,true,request,currentEpoch);}
  while(request.current()&&!more.hidden&&extra.children.length<(saved?.count||0));
  if(!request.current())return;
 }
 for(const d of root.querySelectorAll('details[data-group]')){
  const saved=groupPreference(d);d.open=Boolean(saved?.open)&&!d.hidden;
  if(d.open)await restoreGroup(d,saved,request,currentEpoch);
  if(!request.current())return;
 }
 anchor.expanded=[...root.querySelectorAll('details[data-group][open]')].map(d=>d.dataset.group);
 if(request.current()){state.restore(anchor);if(clear)feedback.textContent='';}
 }catch(e){if(e.name!=='AbortError'&&currentEpoch===epoch)feedback.textContent=e.message;}}
filters.addEventListener('submit',e=>{e.preventDefault();refresh();});
filters.addEventListener('change',e=>{if(e.target.name==='parent'){remember();location.href='/app/collaboration/board?parent='+encodeURIComponent(e.target.value);return;}if(e.target.name==='account'){const selected=filters.store.selectedOptions[0];if(selected?.dataset.account!==filters.account.value)filters.store.value='';}if(e.target.name==='store'&&filters.store.value)filters.account.value=filters.store.selectedOptions[0].dataset.account;refresh();});
for(const b of filters.querySelectorAll('[data-mine]'))b.onclick=()=>{mine=b.dataset.mine==='true';filters.querySelectorAll('[data-mine]').forEach(n=>n.setAttribute('aria-pressed',String(n===b)));refresh();};
function groupPreference(d){try{return JSON.parse(sessionStorage.getItem(rankedKey(d.dataset.group)));}catch{return null;}}
function saveGroup(d){try{sessionStorage.setItem(rankedKey(d.dataset.group),JSON.stringify({open:d.open,count:d.querySelectorAll('[data-card-id]').length}));}catch{}}
const pendingGroups=new WeakMap(),loadedGroups=new WeakMap();
async function restoreGroup(d,saved,request,generation){
 if(pendingGroups.get(d)===generation||loadedGroups.get(d)===generation)return;
 pendingGroups.set(d,generation);
 const error=d.querySelector('[data-group-error]');error.textContent='';
 try{
  await load(d,'DONE',d.dataset.group,false,request,generation);
  while(generation===epoch&&d.open&&!d.querySelector('[data-more]').hidden&&d.querySelectorAll('[data-card-id]').length<(saved?.count||0))
   await load(d,'DONE',d.dataset.group,true,request,generation);
  if(generation===epoch)loadedGroups.set(d,generation);
 }catch(e){if(generation===epoch){
  error.textContent=e.message+' ';const retry=make('button','Retry');retry.type='button';
  retry.onclick=()=>restoreGroup(d,saved,null,epoch);error.append(retry);
 }}
 finally{if(pendingGroups.get(d)===generation)pendingGroups.delete(d);}
}
for(const d of root.querySelectorAll('details[data-group]'))d.addEventListener('toggle',async()=>{
 if(!asOf||pendingGroups.has(d))return;
 const saved=groupPreference(d);if(d.open&&!d.hidden)await restoreGroup(d,saved,null,epoch);saveGroup(d);
});
for(const button of root.querySelectorAll('[data-close]'))button.onclick=()=>button.closest('dialog').close();
const newDialog=document.getElementById('board-new');root.querySelector('[data-new-task]').onclick=()=>newDialog.showModal();
newDialog.querySelector('form').onsubmit=async e=>{e.preventDefault();const f=e.currentTarget,button=f.querySelector('[type=submit]');button.disabled=true;try{await post('tasks',{title:f.elements.title.value,body:f.elements.body.value,date:f.elements.date.value||null,zone,account:filters.account.value||null,store:filters.store.value||null,people:[...f.querySelectorAll('[name=people]:checked')].map(n=>n.value)});newDialog.close();f.reset();await refresh();}catch(error){f.querySelector('[data-error]').textContent=error.message;}finally{button.disabled=false;}};
const detail=document.getElementById('board-detail');let detailTrigger=null,oldest=null;
async function notes(append=false){const current=++detailEpoch,p=new URLSearchParams({parent});if(append&&oldest){p.set('before',oldest.createdAt);p.set('beforeId',oldest.id);}const data=await json('/app/collaboration/board/tasks/'+detailId+'/messages?'+p);if(current!==detailEpoch||!detail.open)return;const list=detail.querySelector('[data-messages]');if(!append)list.replaceChildren();for(const note of data){const a=make('article');a.append(make('strong',note.author),make('small',new Date(note.createdAt).toLocaleString()),make('p',note.body));for(const file of note.files||[]){const link=make('a',file.name);link.href='/app/collaboration/board/tasks/'+detailId+'/files/'+file.id+'?parent='+encodeURIComponent(parent);link.setAttribute('download','');a.append(link);}list.append(a);}oldest=data.at(-1);detail.querySelector('[data-older]').hidden=data.length<50;}
async function openDetail(task,trigger){detailId=task.id;detailTrigger=trigger;detail.querySelector('[data-title]').textContent=task.title;detail.querySelector('[data-messages]').replaceChildren();detail.showModal();try{await notes()}catch(e){detail.querySelector('[data-error]').textContent=e.message;}}
detail.addEventListener('close',()=>{detailEpoch++;detailTrigger?.focus({preventScroll:true});});detail.querySelector('[data-older]').onclick=()=>notes(true).catch(e=>detail.querySelector('[data-error]').textContent=e.message);
detail.querySelector('form').onsubmit=async e=>{e.preventDefault();const f=e.currentTarget;try{await post('tasks/'+detailId+'/messages',{body:f.elements.body.value,attachments:[...f.elements.attachments.files]});f.reset();await notes();}catch(error){f.querySelector('[data-error]').textContent=error.message;}};
refresh().then(async ()=>{
 const linked=new URLSearchParams(location.search).get('task');
 if(linked){
  try{const task=await json('/app/collaboration/board/tasks/'+encodeURIComponent(linked)+'?parent='+encodeURIComponent(parent));await openDetail(task,null);}
  catch(error){feedback.textContent=error.message;}
  return;
 }
 try{const saved=JSON.parse(sessionStorage.getItem(positionKey));if(saved)state.restore(saved)}catch(_){}});
})();
