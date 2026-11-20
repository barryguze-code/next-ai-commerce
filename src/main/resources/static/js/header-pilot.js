/* Local Orders/Collaborate pilot: move original nodes; domain handlers remain authoritative. */
(()=>{
 if(window.NextAiHeaderPilot)return;let presentRoot;
 function init(){
 const root=document.querySelector('[data-header-pilot]');if(!root||root.dataset.headerPilotReady)return;
 const kind=root.dataset.headerPilot,header=root.querySelector(':scope>.workspace-header'),copy=header?.firstElementChild;if(!header||!copy)return;root.dataset.headerPilotReady='true';
 const move=(node,parent,before=null)=>{if(node&&parent&&(node.parentElement!==parent||(before&&node.nextElementSibling!==before)))parent.insertBefore(node,before);};
 let queued=false;
 function metadata(){
  let block=copy.querySelector('.page-header-meta');if(!block){block=document.createElement('div');block.className='page-header-meta';copy.append(block);}
  if(kind==='orders'){
   let row=block.querySelector('.page-header-meta-line');if(!row){row=document.createElement('div');row.className='page-header-meta-line';block.prepend(row);}
   move(copy.querySelector('.page-title-line .live-indicator'),row);move(copy.querySelector(':scope>.order-sync-status'),row);
  }
  move(copy.querySelector(':scope>.page-intro'),block);
 }
 function present(){queued=false;if(document.readyState==='loading'&&!window.NextAiTablePrepaint?.canPresent())return;
  if(kind==='orders'){
   const card=root.querySelector('.order-card'),bar=card?.querySelector('.order-toolbar');if(!card||!bar)return;card.classList.add('header-pilot-card');
   let line=copy.querySelector('.page-title-line');if(!line){line=document.createElement('div');line.className='page-title-line';move(copy.querySelector('h1'),line);copy.prepend(line);}
   const live=bar.querySelector('.live-indicator');if(live){copy.querySelector('.live-indicator')?.remove();move(live,line);}
   const note=bar.querySelector('.table-header-copy p,.order-heading-note');if(note){copy.querySelector('.page-intro')?.remove();note.classList.add('page-intro');move(note,copy);}
   const sync=bar.querySelector('.order-sync-status');if(sync){copy.querySelector('.order-sync-status')?.remove();move(sync,copy);}
   const duplicate=bar.querySelector('.table-header-copy');duplicate?.remove();bar.querySelector('.stream-heading h2')?.remove();
   metadata();window.NextAiTableComposition?.placeCommands(card);
  }else{
   const card=root.querySelector(':scope>.data-card'),toolbar=card?.querySelector(':scope>.table-standard-toolbar');if(!card||!toolbar)return;card.classList.add('header-pilot-card');
   if(!copy.querySelector('.page-intro')){const intro=document.createElement('p');intro.className='page-intro';intro.textContent='Conversations, tasks, and private notes in one workspace.';copy.append(intro);}
   const old=card.querySelector(':scope>.card-toolbar'),tools=toolbar.querySelector(':scope>.table-widget-tools');
   if(tools&&old){move(old.querySelector('[data-new-task]'),tools,tools.firstElementChild);const tabs=old.querySelector('.collaboration-view-tabs');if(tabs)tools.insertBefore(tabs,tools.querySelector('.table-filter-control'));old.remove();}
   const count=card.querySelector('.personal-initial-count');if(count&&count.parentElement!==toolbar){const search=toolbar.querySelector('.table-standard-search');search?.after(count);}
   window.NextAiTableWidget?.presentControlRow(toolbar);
   if(tools&&window.NextAiTableDataTools?.presentCommands){
    const task=tools.querySelector('[data-new-task]');if(task&&!task.querySelector('.table-action-label'))task.querySelector('img')?.remove();
    window.NextAiTableDataTools.presentCommands(card,toolbar,tools,toolbar.querySelector('input[type=search]'));
   }
   metadata();window.NextAiTableComposition?.placeCommands(card);
  }
  for(const target of root.querySelectorAll('.table-shortcut-target')){
   target.setAttribute('aria-label','Edit action shortcuts');target.dataset.tooltip='Edit action shortcuts';
   if(!target.dataset.pilotPinArt){target.dataset.pilotPinArt='true';target.innerHTML='<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.75" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M6 3h8l-1 6 3 3v2H4v-2l3-3-1-6ZM10 14v7M17 4h6M20 1v6"/></svg>';}
  }
 }
 const schedule=()=>{if(!queued){queued=true;requestAnimationFrame(present);}};
 new MutationObserver(schedule).observe(root,{childList:true,subtree:true});presentRoot=present;present();
 }
 window.NextAiHeaderPilot={refresh(){init();presentRoot?.();}};
 new MutationObserver(init).observe(document.documentElement,{childList:true,subtree:true});init();
})();
