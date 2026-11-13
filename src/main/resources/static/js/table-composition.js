/* Shared presentation adapter. Domain handlers, GET forms and row renderers stay authoritative. */
(()=>{
 if(window.NextAiTableComposition)return;
 let queued=false;
 const ownerSelector='.data-card,.table-widget-surface,.receive-lines,.desk-card,[data-table-composition]';
 const own=(card,selector)=>[...card.querySelectorAll(selector)].filter(n=>n.closest(ownerSelector)===card);
 const text=n=>(n?.textContent||'').replace(/\s+/g,' ').trim();
 const duplicateTitles=new Map([['marketplace skus','marketplace listings'],['global catalogue','products'],['account catalogue','products'],['available inventory','inventory on hand'],['inventory ledger','movement history'],['fba shipments','fba shipments'],['receiving','invoices & packing lists'],['replenishment','suggested po'],['vendors & pricing','vendor directory'],['marketplace connections','marketplace connections'],['carrier integrations','carrier connections'],['platform accounts','all accounts']]);
 const labels={'receiving-work':'Receiving checklist','receiving-lines':'Invoice lines','physical-count-files':'Uploaded counts','physical-count-rows':'Counted inventory','carrier-connections':'Shipping connections'};
 function rowActions(root){for(const control of root.querySelectorAll('[data-context-action],.table-overview-rail>.table-action-trigger')){
  if(!control.matches('button,summary,a')||!window.NextAiIcons)continue;
  control.classList.add('table-action-trigger');if(!control.getAttribute('aria-label'))control.setAttribute('aria-label','Actions');control.dataset.tooltip='Actions';
  if(!control.querySelector(':scope>img[data-platform-icon]')){const badges=[...control.querySelectorAll(':scope>b,:scope>.table-warning-count')];control.replaceChildren(window.NextAiIcons.create(control.classList.contains('needs-attention')?'alert':'actions'),...badges);}
 }}
 function placeCommands(card){
  const header=own(card,':scope>.card-toolbar')[0],toolbar=own(card,'.table-standard-toolbar')[0];
  const workspace=card.closest('.workspace'),pageHeader=workspace?.querySelector(':scope>.workspace-header'),copy=pageHeader?.firstElementChild;
  const firstRoot=workspace?.querySelector('[data-table-widget]');
  if(copy&&!card.closest('dialog')&&firstRoot?.closest(ownerSelector)===card){
   const sync=own(card,'.order-sync-status,.sku-sync-status,.fba-sync-utility')[0];
   if(sync){const selector=sync.matches('.order-sync-status')?'.order-sync-status':sync.matches('.sku-sync-status')?'.sku-sync-status':'.fba-sync-utility';const previous=copy.querySelector(selector);if(previous&&previous!==sync)previous.remove();let meta=copy.querySelector(':scope>.page-header-meta,:scope>.table-page-meta');if(!meta){meta=document.createElement('div');meta.className=workspace.hasAttribute('data-header-pilot')?'page-header-meta':'table-page-meta';copy.append(meta);}let line=meta.querySelector(':scope>.page-header-meta-line,:scope>.table-page-meta-line');if(!line){line=document.createElement('div');line.className=workspace.hasAttribute('data-header-pilot')?'page-header-meta-line':'table-page-meta-line';meta.prepend(line);}line.append(sync);}
  }
  if(copy&&!card.closest('dialog')&&firstRoot?.closest(ownerSelector)===card){
   const extras=own(card,'.sku-sync-title>.sku-sync-indicator,[data-fba-compact-status] .sku-sync-indicator,.rp-heading-copy>.sku-sync-indicator,time[data-forecast-time]');
   if(extras.length){let meta=copy.querySelector(':scope>.page-header-meta,:scope>.table-page-meta');if(!meta){meta=document.createElement('div');meta.className=workspace.hasAttribute('data-header-pilot')?'page-header-meta':'table-page-meta';copy.append(meta);}let line=meta.querySelector(':scope>.page-header-meta-line,:scope>.table-page-meta-line');if(!line){line=document.createElement('div');line.className=workspace.hasAttribute('data-header-pilot')?'page-header-meta-line':'table-page-meta-line';meta.prepend(line);}for(const extra of extras){const selector=extra.matches('[data-forecast-time]')?'[data-forecast-time]':'.sku-sync-indicator';const previous=copy.querySelector(selector);if(previous&&previous!==extra)previous.remove();line.insertBefore(extra,line.querySelector('.order-sync-status,.sku-sync-status,.fba-sync-utility'));}}
  }
  const cluster=own(card,'.table-sync-actions')[0];
  if(cluster){
   const strip=own(card,'.order-tab-panel,.table-status-strip')[0];
   if(strip&&header?.contains(strip)){header.after(strip);strip.hidden=false;}
   const destination=strip||toolbar;
   if(destination){destination.classList.add('table-shared-command-row');if(cluster.parentElement!==destination||!strip&&destination.lastElementChild!==cluster)destination.append(cluster);}
   const old=own(card,'.table-header-commands')[0];if(old&&!old.children.length)old.remove();
  }
  if(header){const useful=Boolean(text(header)||header.querySelector('button,a,details,input,select'));header.hidden=!useful;}
 }
 function prepare(root){
  rowActions(root);
  const nested=!!root.closest('dialog');let card=root.closest(ownerSelector);
  if(nested&&card&&card.closest('dialog')===root.closest('dialog')&&own(card,'[data-table-widget]').length===1)card.classList.add('table-widget-surface');
  if(nested&&!root.closest('.table-widget-surface')){
   const wrap=root.parentElement?.matches('.table-wrap,.receive-table-wrap')?root.parentElement:root;
   card=document.createElement('section');card.className='table-widget-surface';wrap.before(card);card.append(wrap);
   const heading=document.createElement('header');heading.className='card-toolbar';const title=document.createElement('h3');title.textContent=root.getAttribute('aria-label')||labels[root.dataset.tableWidget]||root.dataset.title||'Document details';heading.append(title);card.prepend(heading);
  }
  if(!card)return;card.classList.add('shared-table-composition');
  const roots=own(card,'[data-table-widget]').filter(n=>!n.parentElement?.closest('[data-table-widget]'));if(roots.length!==1)return;
  const header=own(card,':scope>.card-toolbar')[0],toolbar=own(card,':scope>.table-standard-toolbar')[0];
  const workspace=root.closest('.workspace'),pageHeader=workspace?.querySelector(':scope>.workspace-header'),pageTitle=pageHeader?.querySelector('h1');
  if(header&&pageTitle&&!workspace.hasAttribute('data-header-pilot')){
   const title=header.querySelector('h2');if(title&&!root.closest('dialog')&&(text(title).toLowerCase()===text(pageTitle).toLowerCase()||duplicateTitles.get(text(pageTitle).toLowerCase())===text(title).toLowerCase())){
    const caption=title.parentElement.querySelector('p');if(caption&&!pageHeader.querySelector('.page-intro')){caption.classList.add('page-intro');pageTitle.parentElement.append(caption);}title.remove();
   }
  }
  if(header)for(const control of header.querySelectorAll('button,a')){
   if(control.closest('form,nav,.table-widget-tools,.table-column-control,.table-filter-control')||control.hasAttribute('data-table-command')||control.matches('.table-export,[data-suggest-action]'))continue;
   if(/^(add|create|new|upload|import|manage|settings|locations|physical count)\b/i.test(text(control))){control.setAttribute('data-table-command','');control.dataset.commandId||=(control.id||text(control).toLowerCase().replace(/[^a-z0-9]+/g,'-'));}
  }
  const commands=own(card,'[data-table-command]').filter(n=>!n.closest('.table-command-menu'));
  if(commands.length&&toolbar&&window.NextAiTableDataTools?.presentCommands){
   card.setAttribute('data-table-composition','');card.dataset.tableShortcuts||=root.dataset.tableWidget;
   const host=own(card,'[data-column-control]')[0]||toolbar.querySelector('.table-widget-tools');
   const search=toolbar.querySelector('input[type=search]');if(host&&search)window.NextAiTableDataTools.presentCommands(card,toolbar,host,search);
  }
  if(workspace&&pageHeader&&!workspace.hasAttribute('data-header-pilot')){
   workspace.classList.add('shared-table-page');
   const copy=pageTitle?.parentElement,caption=copy?.querySelector(':scope>.page-intro');
   if(caption){let meta=copy.querySelector(':scope>.table-page-meta');if(!meta){meta=document.createElement('div');meta.className='table-page-meta';copy.append(meta);}meta.append(caption);}
  }
  placeCommands(card);
 }
 function refresh(){queued=false;
  for(const table of document.querySelectorAll('table:not([data-table-widget])')){
   if(!table.tHead||!table.querySelector('tbody')||table.closest('[data-table-widget]'))continue;
   if(table.querySelector('input,select,textarea')||table.closest('form')){table.classList.add('table-form-surface');table.parentElement?.classList.add('table-form-frame');continue;}
   if(table.matches('.rw-table')&&table.closest('dialog')){table.dataset.tableWidget='receiving-source-lines';table.setAttribute('aria-label','Unshipped source lines');window.NextAiTableWidget?.refresh();}
  }
 document.querySelectorAll('[data-table-widget]').forEach(prepare);for(const target of document.querySelectorAll('.shared-table-composition .table-shortcut-target')){target.setAttribute('aria-label','Edit action shortcuts');target.dataset.tooltip='Edit action shortcuts';if(!target.dataset.sharedPinArt){target.dataset.sharedPinArt='true';target.innerHTML='<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.75" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M6 3h8l-1 6 3 3v2H4v-2l3-3-1-6ZM10 14v7M17 4h6M20 1v6"/></svg>';}}}
 function schedule(){if(!queued){queued=true;requestAnimationFrame(refresh);}}
 new MutationObserver(schedule).observe(document.documentElement,{childList:true,subtree:true});
 document.addEventListener('table:rows-replaced',schedule);document.addEventListener('table:refresh',schedule);refresh();
 window.NextAiTableComposition={refresh,prepare,placeCommands};
})();
