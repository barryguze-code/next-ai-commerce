(()=>{
  const dialog=document.getElementById('action-suggestion-dialog');if(!dialog)return;
  const form=dialog.querySelector('form'),result=form.querySelector('[data-suggestion-result]'),submit=form.querySelector('[type=submit]');
  let origin;
  form.querySelectorAll('[data-suggestion-close]').forEach(button=>button.onclick=()=>dialog.close());
  dialog.addEventListener('close',()=>origin?.focus());
  function open(button,menu){
    origin=button;form.reset();submit.disabled=false;result.hidden=true;
    const context=(menu.dataset.title||menu.closest('tr')?.dataset.product||menu.querySelector('header strong,.table-action-heading strong')?.textContent||'Record actions').trim().slice(0,250);
    form.elements.pagePath.value=location.pathname;form.elements.menuContext.value=context;
    form.querySelector('[data-suggestion-context]').textContent=context;
    document.querySelectorAll(':popover-open').forEach(panel=>panel.hidePopover());
    dialog.showModal();form.elements.title.focus();
  }
  form.addEventListener('submit',async event=>{
    event.preventDefault();if(submit.disabled)return;submit.disabled=true;result.hidden=true;
    try{
      const response=await fetch(form.action,{method:'POST',body:new FormData(form),headers:{Accept:'application/json'}});
      if(!response.ok)throw new Error('Your suggestion could not be saved. Please try again.');
      const data=await response.json();result.textContent=data.message;result.hidden=false;
    }catch(error){result.textContent=error.message;result.hidden=false;submit.disabled=false;}
  });
  const selectors='#inventory-action-menu,.table-warning-panel,.stock-action-popover,.order-stage-actions nav,.sku-actions-dialog,.order-picture-menu,.order-compact-actions nav,[data-action-suggestion-menu]';
  function enhance(root=document){
    const menus=new Set([...(root.matches?.(selectors)?[root]:[]),...root.querySelectorAll(selectors)]);
    const parent=root.closest?.(selectors);if(parent)menus.add(parent);
    menus.forEach(menu=>{
      if(menu.querySelector('[data-suggest-action]'))return;
      const button=document.createElement('button');button.type='button';button.dataset.suggestAction='';button.className='suggest-action-entry';
      const icon=document.createElement('span');icon.textContent='✧';icon.setAttribute('aria-hidden','true');
      const copy=document.createElement('div'),title=document.createElement('strong'),description=document.createElement('small');
      title.textContent='Suggest an action';description.textContent='Help R&D improve this menu';copy.append(title,description);button.append(icon,copy);
      button.onclick=event=>{event.stopPropagation();open(button,menu)};
      (menu.id==='inventory-action-menu'?menu.querySelector('form'):menu).append(button);
    });
  }
  // Menu owners can size the complete menu synchronously, including its footer.
  window.prepareActionSuggestionMenu=enhance;
  // Only inspect added menu subtrees; chat messages and unrelated table cells
  // must not cause another scan of every action menu on the page.
  let scheduled=false;const pending=new Set();
  new MutationObserver(records=>{
    for(const record of records)for(const node of record.addedNodes){
      if(node.nodeType!==1||node.matches('[data-suggest-action]'))continue;
      if(node.matches(selectors)||node.closest(selectors)||node.querySelector(selectors))pending.add(node);
    }
    if(!pending.size||scheduled)return;scheduled=true;queueMicrotask(()=>{scheduled=false;for(const node of pending)if(node.isConnected)enhance(node);pending.clear();});
  }).observe(document.body,{childList:true,subtree:true});
  enhance();
})();
