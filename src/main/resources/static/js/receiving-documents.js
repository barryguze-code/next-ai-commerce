(()=>{
const button=document.querySelector('[data-open-selected]'),count=document.querySelector('[data-selected-count]');
if(!button)return;
const key='receiving-selection:'+(document.querySelector('.rw-selection-bar')?.dataset.selectionAccount||'current-view');
let selected;try{selected=new Set(JSON.parse(sessionStorage.getItem(key)||'[]'));}catch(_){selected=new Set();}
document.querySelectorAll('[data-source-select]').forEach(input=>{if(input.disabled)selected.delete(input.value);input.checked=!input.disabled&&selected.has(input.value);});
function update(){
  count.classList.add("sr-only");
  button.disabled=false;
  const title=selected.size>1?'Receive selected · '+selected.size:selected.size===1?'Receive selected':'Receive all open';
  const description=selected.size?selected.size+' document'+(selected.size===1?'':'s')+' selected'+(selected.size>1?' across pages':''):'Open documents with quantities remaining';
  const image='/images/platform/table/'+(selected.size===1?'receive-document-owner.png':'receive-documents-owner.png');
  function present(control){
    control.dataset.actionImage=image;control.dataset.actionDescription=description;
    const label=control.querySelector('.table-action-label');
    if(label){label.querySelector('strong').textContent=title;let small=label.querySelector('small');if(!small){small=document.createElement('small');label.append(small)}small.textContent=description;const img=control.querySelector('.table-action-icon img');if(img)img.src=image;}
    else control.textContent=title;
  }
  present(button);
  document.querySelector('[data-clear-selected]').disabled=selected.size===0;

  count.textContent=selected.size?selected.size+' document'+(selected.size===1?'':'s')+' selected across pages · ready to count outstanding quantities':'Select open documents to receive together';
  document.querySelectorAll('[data-source-select]').forEach(input=>input.closest('tr').classList.toggle('is-selected',input.checked));
  document.querySelectorAll('[data-open-selection]').forEach(action=>{action.hidden=false;action.disabled=false;present(action);});
  document.querySelectorAll('[data-single-selection-hint]').forEach(hint=>hint.hidden=!selected.size);
  try{sessionStorage.setItem(key,JSON.stringify([...selected]));}catch(_){}
}
document.querySelectorAll('[data-delete-document]').forEach(control=>control.addEventListener('click',()=>{
  const dialog=document.createElement('dialog');dialog.className='invite-dialog';
  const shell=document.createElement('div');shell.className='dialog-shell';
  shell.innerHTML='<div class="dialog-header"><div><h2>Delete document?</h2><p data-document-context></p></div><button type="button" class="dialog-close" aria-label="Cancel">×</button></div><div class="dialog-body"><p>This removes the uploaded document and its unreceived lines.</p></div><p class="notice error" role="alert" hidden></p><div class="dialog-actions"><button type="button" class="secondary-button" data-cancel>Cancel</button><button type="button" class="primary-button rw-danger" data-confirm>Delete document</button></div>';
  shell.querySelector('[data-document-context]').textContent=control.dataset.documentLabel;
  dialog.append(shell);document.body.append(dialog);
  let pending=false;
  const close=()=>{if(pending)return;dialog.close();dialog.remove();control.focus({preventScroll:true})};
  shell.querySelector('.dialog-close').onclick=close;shell.querySelector('[data-cancel]').onclick=close;
  dialog.addEventListener('cancel',event=>{event.preventDefault();close()});
  const requestId=crypto.randomUUID(),confirm=shell.querySelector('[data-confirm]'),error=shell.querySelector('[role="alert"]');
  confirm.onclick=async()=>{
    pending=true;confirm.disabled=true;error.hidden=true;
    const csrf=document.getElementById('buy-shipping-csrf');
    try{
      const response=await fetch('/app/receiving/work/remove/'+control.dataset.deleteDocument,{method:'POST',headers:{'Content-Type':'application/json',[csrf.dataset.header]:csrf.value},body:JSON.stringify({requestId})});
      const result=await response.json();if(!response.ok)throw Error(result.message||'Document could not be deleted.');
      const row=document.querySelector('[data-source-select][value="'+control.dataset.deleteDocument+'"]')?.closest('tr');
      selected.delete(control.dataset.deleteDocument);row?.remove();update();dialog.close();dialog.remove();button.focus({preventScroll:true});
    }catch(problem){error.textContent=problem.message||'Document could not be deleted. Please try again.';error.hidden=false;pending=false;confirm.disabled=false;}
  };
  dialog.showModal();shell.querySelector('[data-cancel]').focus();
}));
update();
document.querySelector('[data-clear-selected]').onclick=()=>{selected.clear();document.querySelectorAll('[data-source-select]').forEach(input=>input.checked=false);update();};
document.addEventListener('change',event=>{
  const input=event.target;if(!input.matches('[data-source-select]'))return;
  if(input.checked&&selected.size>=12){input.checked=false;count.textContent='Choose up to 12 documents.';count.classList.remove('sr-only');return;}
  if(input.checked)selected.add(input.value);else selected.delete(input.value);
  update();
});
button.onclick=()=>window.location.assign(selected.size?'/app/receiving/work?'+new URLSearchParams({documents:[...selected].join(',')}):'/app/receiving/work/all');
// These controls move into a shared menu that stops event bubbling. Keep their handlers attached.
document.querySelectorAll('[data-open-selection]').forEach(action=>action.addEventListener('click',()=>button.click()));
})();
