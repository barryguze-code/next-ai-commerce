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
