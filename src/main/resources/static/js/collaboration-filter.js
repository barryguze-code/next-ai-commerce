/* Keep the native GET form as the source of truth; present the platform's compact choice list. */
(()=>{
 const select=document.querySelector('.collaboration-entity-filter');if(!select)return;
 const picker=document.createElement('details');picker.className='collaboration-record-filter';
 const summary=document.createElement('summary');summary.textContent=select.selectedOptions[0].textContent;summary.setAttribute('aria-label','Filter by record type');
 const choices=document.createElement('div');choices.className='collaboration-record-choices';
 for(const option of select.options){const button=document.createElement('button');button.type='button';button.textContent=option.textContent;button.setAttribute('aria-pressed',String(option.selected));button.onclick=()=>{select.value=option.value;select.form.requestSubmit();};choices.append(button);}
 picker.append(summary,choices);select.after(picker);select.hidden=true;
 picker.addEventListener('keydown',event=>{if(event.key==='Escape'){picker.open=false;summary.focus();}if(event.key==='ArrowDown'||event.key==='ArrowUp'){event.preventDefault();picker.open=true;const buttons=[...choices.children],i=buttons.indexOf(document.activeElement);buttons[(i+(event.key==='ArrowDown'?1:buttons.length-1)+buttons.length)%buttons.length].focus();}});
 document.addEventListener('click',event=>{if(!picker.contains(event.target))picker.open=false;});
})();
