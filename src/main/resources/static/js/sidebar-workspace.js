(()=>{'use strict';
function mount(){
 const sidebar=document.querySelector('.sidebar'),page=document.querySelector('.app-page');if(!sidebar||!page)return;
 const user=document.querySelector('.sidebar-user-copy strong')?.textContent.trim();if(!user)return;
 const key='nextai.workspace.v1:'+user;let saved={};try{saved=JSON.parse(localStorage.getItem(key)||'{}');}catch(_){}
 let width=Math.max(220,Math.min(360,Number(saved.width)||260)),collapsed=saved.collapsed===true;
 const persist=()=>{try{localStorage.setItem(key,JSON.stringify({...saved,width,collapsed}));}catch(_){}};
 sidebar.querySelector('.brand')?.setAttribute('aria-label','Next AI Commerce home');
 const toggle=sidebar.querySelector('.sidebar-edge-toggle')||document.createElement('button');toggle.type='button';toggle.className='sidebar-edge-toggle';
 const resize=sidebar.querySelector('.sidebar-resize')||document.createElement('div');resize.className='sidebar-resize';resize.tabIndex=0;resize.setAttribute('role','separator');resize.setAttribute('aria-orientation','vertical');resize.setAttribute('aria-label','Sidebar width');resize.setAttribute('aria-valuemin','220');resize.setAttribute('aria-valuemax','360');
 function paint(){page.style.setProperty('--sidebar-width',width+'px');page.classList.toggle('sidebar-collapsed',collapsed);toggle.textContent=collapsed?'›':'‹';toggle.setAttribute('aria-label',collapsed?'Expand navigation':'Collapse navigation');toggle.setAttribute('aria-expanded',String(!collapsed));resize.setAttribute('aria-valuenow',String(width));resize.tabIndex=collapsed?-1:0;}
 toggle.addEventListener('click',()=>{collapsed=!collapsed;paint();persist();});
 sidebar.querySelectorAll('.product-nav a,.nav-group-label').forEach(n=>{const label=n.textContent.trim();if(!n.getAttribute('aria-label'))n.setAttribute('aria-label',label);[...n.childNodes].filter(x=>x.nodeType===3&&x.textContent.trim()).forEach(x=>{const span=document.createElement('span');span.className='nav-item-label';x.replaceWith(span);span.append(x);});});
 sidebar.querySelectorAll('.product-nav details').forEach(n=>{
  if(n.querySelector('a.active'))n.classList.add('nav-current-module');
  const group=n.querySelector('summary .nav-group-label')?.textContent.trim()||n.querySelector('summary')?.textContent.trim();
  if(!group)return;
  if(typeof saved.navGroups?.[group]==='boolean')n.open=saved.navGroups[group];
  n.addEventListener('toggle',()=>{saved.navGroups={...saved.navGroups,[group]:n.open};persist();});
 });
 sidebar.querySelectorAll('.product-nav summary').forEach(n=>n.addEventListener('click',e=>{if(!collapsed)return;e.preventDefault();collapsed=false;n.parentElement.open=true;paint();persist();n.focus();}));
 let drag;
 resize.addEventListener('pointerdown',e=>{if(e.button!==0)return;drag={x:e.clientX,width};resize.focus();resize.setPointerCapture(e.pointerId);page.classList.add('sidebar-resizing');e.preventDefault();});
 resize.addEventListener('pointermove',e=>{if(!drag)return;width=Math.max(220,Math.min(360,drag.width+e.clientX-drag.x));paint();});
 function finish(cancel){if(!drag)return;if(cancel)width=drag.width;drag=null;page.classList.remove('sidebar-resizing');paint();persist();}
 resize.addEventListener('pointerup',()=>finish(false));resize.addEventListener('pointercancel',()=>finish(true));
 resize.addEventListener('keydown',e=>{if(e.key==='Escape'){finish(true);return;}const next={ArrowLeft:width-8,ArrowRight:width+8,Home:220,End:360}[e.key];if(next==null)return;e.preventDefault();width=Math.max(220,Math.min(360,next));paint();persist();});
 sidebar.append(resize,toggle);paint();
 function shortcuts(){
 const header=document.querySelector('.workspace-header'),picker=document.querySelector('.context-menu'),huddle=header?.querySelector('.huddle-launcher');if(!header||!picker||!huddle)return false;
 const forms=[...picker.querySelectorAll('form')].filter(f=>f.querySelector('[name=storeId]'));
 const stores=forms.map(form=>{const id=form.querySelector('[name=storeId]').value,button=form.querySelector('button[type=submit]');return {id,form,button,name:form.querySelector('[name=storeName]')?.value||button?.textContent.trim(),account:form.querySelector('[name=tenantName]')?.value};}).filter(s=>s.button);
 if(!stores.length)return true;
 const allowed=new Set(stores.map(s=>s.id));saved.pins=Array.isArray(saved.pins)?saved.pins.filter(id=>allowed.has(id)):[];persist();
 header.classList.add('workspace-has-store-shortcuts');
 const bar=document.createElement('div');bar.className='store-shortcuts';bar.setAttribute('aria-label','Store shortcuts');
 const add=document.createElement('button');add.type='button';add.className='store-shortcut-add';add.innerHTML='<svg aria-hidden="true" width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-dasharray="2 2"><path d="M12 5v14M5 12h14"/></svg>';add.setAttribute('aria-label','Manage store shortcuts');
 let capacity=-1,dragged=null,suppressShortcutClick=false;
 const announcement=document.createElement('span');announcement.className='sr-only';announcement.setAttribute('role','status');header.append(announcement);
 function moveShortcut(id,target){const from=saved.pins.indexOf(id),to=saved.pins.indexOf(target);if(from<0||to<0||from===to)return; saved.pins.splice(from,1);saved.pins.splice(to,0,id);persist();render();announcement.textContent='Shortcut moved to position '+(to+1);}

 function render(){const slots=Math.max(0,Math.floor((bar.clientWidth+8)/48)),visible=saved.pins.length+1<=slots?saved.pins.length:Math.max(0,slots-2);capacity=slots;bar.replaceChildren();saved.pins.forEach((id,index)=>{const s=stores.find(s=>s.id===id),wrap=document.createElement('span'),b=document.createElement('button'),remove=document.createElement('button');wrap.className='store-shortcut';wrap.dataset.shortcutId=id;b.type='button';b.draggable=true;
 b.addEventListener('dragstart',event=>{dragged=id;suppressShortcutClick=true;event.dataTransfer.effectAllowed='move';event.dataTransfer.setData('text/plain',id);});
 wrap.addEventListener('dragover',event=>{if(!dragged)return;event.preventDefault();wrap.classList.toggle('shortcut-drop-changed',dragged!==id);});
 wrap.addEventListener('dragleave',()=>wrap.classList.remove('shortcut-drop-changed'));
 wrap.addEventListener('drop',event=>{if(!dragged)return;event.preventDefault();moveShortcut(dragged,id);dragged=null;setTimeout(()=>suppressShortcutClick=false,0);});
 b.addEventListener('dragend',()=>{dragged=null;bar.querySelectorAll('.shortcut-drop-changed').forEach(e=>e.classList.remove('shortcut-drop-changed'));setTimeout(()=>suppressShortcutClick=false,0);});
 b.addEventListener('keydown',event=>{if(!event.altKey||!['ArrowLeft','ArrowRight'].includes(event.key))return;event.preventDefault();const target=saved.pins[saved.pins.indexOf(id)+(event.key==='ArrowLeft'?-1:1)];if(target){moveShortcut(id,target);bar.querySelector('[data-shortcut-id="'+CSS.escape(id)+'"] button')?.focus();}});
b.setAttribute('aria-label',s.account+' · '+s.name);b.dataset.tooltip=s.account+' · '+s.name;if(s.button.classList.contains('selected'))b.setAttribute('aria-current','true');const image=s.form.closest('.context-account')?.querySelector('.account-brand-logo img,.account-brand-logo')||s.button.querySelector('img');if(image?.tagName==='IMG'){const img=image.cloneNode();img.alt='';b.append(img);}else b.textContent=s.name.slice(0,2).toUpperCase();const channel=s.button.querySelector('.mini-channel img');if(channel){const badge=channel.cloneNode();badge.alt='';badge.className='store-shortcut-channel';b.append(badge);}b.addEventListener('click',event=>{if(suppressShortcutClick){event.preventDefault();return;}s.button.click();});remove.type='button';remove.className='store-shortcut-remove';remove.textContent='×';remove.setAttribute('aria-label','Remove '+s.name+' shortcut');remove.addEventListener('click',()=>{saved.pins=saved.pins.filter(x=>x!==id);persist();render();add.focus();});wrap.append(b,remove);if(index<visible)bar.append(wrap);else{let more=bar.querySelector('.store-shortcut-more');if(!more){more=document.createElement('details');more.className='store-shortcut-more';const summary=document.createElement('summary');summary.textContent='…';summary.setAttribute('aria-label','More store shortcuts');const list=document.createElement('div');list.className='store-shortcut-more-list';more.append(summary,list);bar.append(more);}more.querySelector('.store-shortcut-more-list').append(wrap);}});bar.append(add);}
 let managing=false,restoreShortcutFocus=true;
 document.addEventListener('click',e=>{if(managing&&picker.open&&!picker.contains(e.target)&&!add.contains(e.target)){restoreShortcutFocus=false;picker.open=false;}});
 add.addEventListener('click',()=>{managing=true;restoreShortcutFocus=true;picker.classList.add('store-shortcut-managing');stores.forEach(s=>{s.button.setAttribute('aria-pressed',String(saved.pins.includes(s.id)));s.button.dataset.shortcutPinned=String(saved.pins.includes(s.id));});picker.open=true;picker.querySelector('input[type=search]')?.focus();});
 stores.forEach(s=>s.button.addEventListener('click',e=>{if(!managing)return;e.preventDefault();e.stopImmediatePropagation();saved.pins=saved.pins.includes(s.id)?saved.pins.filter(id=>id!==s.id):[...saved.pins,s.id];persist();render();s.button.setAttribute('aria-pressed',String(saved.pins.includes(s.id)));s.button.dataset.shortcutPinned=String(saved.pins.includes(s.id));},true));
 picker.addEventListener('submit',e=>{if(managing){e.preventDefault();e.stopImmediatePropagation();}},true);
 picker.addEventListener('toggle',()=>{if(!picker.open){if(managing&&restoreShortcutFocus)add.focus();managing=false;picker.classList.remove('store-shortcut-managing');stores.forEach(s=>s.button.removeAttribute('aria-pressed'));}});
 const pickerHeading=picker.querySelector('.context-popover header strong'),originalHeading=pickerHeading?.textContent;picker.addEventListener('toggle',()=>{if(pickerHeading)pickerHeading.textContent=managing?'Store shortcuts':originalHeading;});
 huddle.before(bar);render();if(typeof ResizeObserver!=='undefined')new ResizeObserver(()=>{if(Math.max(0,Math.floor((bar.clientWidth+8)/48))!==capacity)render();}).observe(bar);return true;
 }
 if(!shortcuts()){const observer=new MutationObserver(()=>{if(shortcuts())observer.disconnect();});observer.observe(document.body,{childList:true,subtree:true});}
}
if(document.readyState==='loading')document.addEventListener('DOMContentLoaded',mount);else mount();
})();
