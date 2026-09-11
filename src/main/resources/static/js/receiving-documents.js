(()=>{
const button=document.querySelector('[data-open-selected]'),count=document.querySelector('[data-selected-count]');
if(!button)return;
const key='receiving-selection:'+(document.querySelector('.rw-selection-bar')?.dataset.selectionAccount||'current-view');
let selected;try{selected=new Set(JSON.parse(sessionStorage.getItem(key)||'[]'));}catch(_){selected=new Set();}
document.querySelectorAll('[data-source-select]').forEach(input=>{if(input.disabled)selected.delete(input.value);input.checked=!input.disabled&&selected.has(input.value);});
function update(){
  button.disabled=selected.size===0;
  button.textContent=selected.size>1?'Receive '+selected.size+' selected together':'Receive selected document';
  document.querySelector('[data-clear-selected]').disabled=selected.size===0;
  document.querySelector('.rw-selection-bar').classList.toggle('has-selection',selected.size>0);
  count.textContent=selected.size?selected.size+' document'+(selected.size===1?'':'s')+' selected across pages · ready to count outstanding quantities':'Select open documents to receive together';
  document.querySelectorAll('[data-source-select]').forEach(input=>input.closest('tr').classList.toggle('is-selected',input.checked));
  document.querySelectorAll('[data-open-selection]').forEach(action=>{action.hidden=!selected.size;action.disabled=!selected.size;const label=action.querySelector('strong');if(label)label.textContent=button.textContent;else action.textContent=button.textContent;});
  document.querySelectorAll('[data-single-selection-hint]').forEach(hint=>hint.hidden=!selected.size);
  try{sessionStorage.setItem(key,JSON.stringify([...selected]));}catch(_){}
}
update();
document.querySelector('[data-clear-selected]').onclick=()=>{selected.clear();document.querySelectorAll('[data-source-select]').forEach(input=>input.checked=false);update();};
document.addEventListener('change',event=>{
  const input=event.target;if(!input.matches('[data-source-select]'))return;
  if(input.checked&&selected.size>=12){input.checked=false;count.textContent='Choose up to 12 documents at a time.';return;}
  if(input.checked)selected.add(input.value);else selected.delete(input.value);
  update();
});
button.onclick=()=>{if(selected.size)window.location.assign('/app/receiving/work?'+new URLSearchParams({documents:[...selected].join(',')}));};
document.addEventListener('click',event=>{if(event.target.closest('[data-open-selection]'))button.click();});
})();
