/* Parser-time presentation only: useful content remains visible without waiting for deferred tools. */
(()=>{
 if(window.NextAiTablePrepaint)return;
 const aliases=new Map([['marketplace skus','marketplace listings'],['global catalogue','products'],['account catalogue','products'],['available inventory','inventory on hand'],['inventory ledger','movement history'],['fba shipments','fba shipments'],['receiving','invoices & packing lists'],['replenishment','suggested po'],['vendors & pricing','vendor directory'],['marketplace connections','marketplace connections'],['carrier integrations','carrier connections'],['platform accounts','all accounts']]);
 const text=n=>(n?.textContent||'').replace(/\s+/g,' ').trim().toLowerCase();
 function prepare(workspace){
  if(!workspace?.matches?.('.workspace'))return;
  const pageHeader=workspace.querySelector(':scope>.workspace-header'),copy=pageHeader?.firstElementChild,title=copy?.querySelector('h1');if(!title)return;
  for(const card of workspace.querySelectorAll('.data-card,[data-table-composition],.desk-card,.receive-lines')){
   if(card.closest('dialog')||card.closest('.workspace')!==workspace||!card.querySelector('[data-table-widget],table,[data-table-composition]')&&!card.hasAttribute('data-table-composition'))continue;
   card.classList.add('shared-table-composition');
   if(workspace.hasAttribute('data-header-pilot'))continue;
   workspace.classList.add('shared-table-page');
   const header=card.querySelector(':scope>.card-toolbar'),heading=header?.querySelector('h2');
   if(heading&&(text(heading)===text(title)||aliases.get(text(title))===text(heading))){
    header.dataset.prepaintDuplicate='true';const caption=heading.parentElement.querySelector('p');if(caption&&!copy.querySelector('.page-intro')){caption.classList.add('page-intro');let meta=copy.querySelector(':scope>.table-page-meta');if(!meta){meta=document.createElement('div');meta.className='table-page-meta';copy.append(meta);}meta.append(caption);}heading.remove();
   }
   if(header?.dataset.prepaintDuplicate==='true'&&!copy.querySelector('.page-intro')){const caption=header.querySelector('p');if(caption){caption.classList.add('page-intro');let meta=copy.querySelector(':scope>.table-page-meta');if(!meta){meta=document.createElement('div');meta.className='table-page-meta';copy.append(meta);}meta.append(caption);}}
   if(header&&!text(header)&&!header.querySelector('button,a,details,input,select'))header.hidden=true;
  }
 }
 const workspaces=new Set();
 function inspect(node){if(node.nodeType!==1)return;const owner=node.closest('.workspace');if(owner)workspaces.add(owner);if(node.matches('.workspace'))workspaces.add(node);node.querySelectorAll('.workspace').forEach(n=>workspaces.add(n));}
 function canPresent(){const tables=[...document.querySelectorAll('[data-table-widget],.data-card table')],roots=tables.length?tables:[...document.querySelectorAll('[data-header-pilot]>.data-card')],last=document.scripts[document.scripts.length-1];return Boolean(roots.length&&last&&roots.every(root=>!root.contains(last)&&Boolean(root.compareDocumentPosition(last)&Node.DOCUMENT_POSITION_FOLLOWING)));}
 function finishCompleteTables(){if(canPresent()){window.NextAiTableWidget?.refresh();window.NextAiTableDataTools?.refresh();window.NextAiTableComposition?.refresh();window.NextAiHeaderPilot?.refresh();}}
 new MutationObserver(records=>{for(const record of records){inspect(record.target);record.addedNodes.forEach(inspect);}for(const workspace of workspaces)prepare(workspace);workspaces.clear();finishCompleteTables();}).observe(document.documentElement,{childList:true,subtree:true});
 function finishParsed(){if(document.readyState==='loading')return;document.querySelectorAll('.workspace').forEach(prepare);window.NextAiTableWidget?.refresh();window.NextAiTableDataTools?.refresh();window.NextAiTableComposition?.refresh();window.NextAiHeaderPilot?.refresh();}
 document.addEventListener('readystatechange',finishParsed);
 window.NextAiTablePrepaint={prepare,aliases,canPresent};document.querySelectorAll('.workspace').forEach(prepare);
})();
