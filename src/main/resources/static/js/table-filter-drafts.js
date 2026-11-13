/* Draft state only. Domain adapters remain responsible for querying and filtering. */
(()=>{
 if(window.NextAiFilterDrafts)return;
 const clone=value=>value===undefined?undefined:structuredClone(value);
 function create({readApplied,renderDraft,commit,emptyState,onApplied}){
  if(typeof readApplied!=='function'||typeof commit!=='function')throw new TypeError('A filter adapter requires readApplied and commit.');
  let draft,opened=false,pending=null,footer=null,close=null;
  const render=()=>renderDraft?.(clone(draft));
  const busy=value=>footer?.querySelectorAll('button').forEach(button=>button.disabled=value);
  const feedback=message=>{const status=footer?.querySelector('[data-filter-feedback]');if(status)status.textContent=message;};
  function open(){if(pending)return false;draft=clone(readApplied());opened=true;feedback('');render();return true;}
  function update(nextOrUpdater){
   if(pending)return false;if(!opened)open();
   if(typeof nextOrUpdater==='function'){const copy=clone(draft),next=nextOrUpdater(copy);draft=clone(next===undefined?copy:next);}
   else draft=clone(nextOrUpdater);
   feedback('');return true;
  }
  function clear(){if(pending)return false;if(!opened)open();draft=clone(typeof emptyState==='function'?emptyState():emptyState);feedback('');render();return true;}
  function discard(){if(pending)return false;draft=clone(readApplied());opened=false;feedback('');render();return true;}
  function apply(){
   if(pending)return pending;if(!opened)open();
   const snapshot=clone(draft);busy(true);feedback('');
   // Start on a microtask so repeated activation shares the same in-flight commit.
   pending=Promise.resolve().then(()=>commit(clone(snapshot))).then(result=>{
    if(result===false)return false;
    draft=clone(snapshot);opened=false;
    // A presentation callback cannot turn an already-applied filter into a failed commit.
    try{onApplied?.(clone(snapshot),result);}catch{feedback('Filters were applied. Reopen Filters to refresh the summary.');return true;}
    try{close?.();}catch{feedback('Filters were applied. You can close this panel.');}
    return true;
   }).catch(error=>{feedback(error?.message||'Filters could not be applied. Please try again.');return false;})
    .finally(()=>{pending=null;busy(false);});
   return pending;
  }
  function mountFooter(host,options={}){
   if(footer)footer.remove();close=options.close||null;
   footer=document.createElement('footer');footer.className='table-filter-footer';
   const status=document.createElement('p');status.dataset.filterFeedback='';status.setAttribute('role','status');status.setAttribute('aria-live','polite');
   const clearButton=document.createElement('button');clearButton.type='button';clearButton.className='secondary-button compact-button';clearButton.textContent='Clear filters';clearButton.addEventListener('click',clear);
   const applyButton=document.createElement('button');applyButton.type='button';applyButton.className='primary-button compact-button';applyButton.textContent='Apply filters';applyButton.addEventListener('click',apply);
   footer.append(status,clearButton,applyButton);host.append(footer);busy(Boolean(pending));return footer;
  }
  return{open,update,clear,apply,discard,getDraft:()=>clone(opened?draft:readApplied()),mountFooter};
 }
 window.NextAiFilterDrafts={create};
})();
