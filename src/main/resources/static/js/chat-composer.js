(()=>{
  function boot(){
    document.querySelectorAll('#contextual-thread-dialog .collaboration-composer').forEach(form=>{
      if(form.dataset.compactReady)return;form.dataset.compactReady='true';
      const field=form.querySelector('textarea'),tools=form.querySelector('.thread-compose-tools'),submit=form.querySelector('button[type=submit]'),footer=form.querySelector('footer');
      if(!field||!tools||!submit)return;
      field.rows=1;field.setAttribute('aria-label','Message');
      const resize=()=>{field.style.height='auto';field.style.height=Math.min(160,field.scrollHeight)+'px';};field.addEventListener('input',resize);field.addEventListener('focus',resize);
      submit.innerHTML='<svg viewBox="0 0 24 24" aria-hidden="true"><path d="m3 3 18 9-18 9 4-9-4-9ZM7 12h14"/></svg>';submit.setAttribute('aria-label','Send message');submit.title='Send message · Enter';
      const attachment=tools.querySelector('label');attachment.title='Attach files';attachment.setAttribute('aria-label','Attach files');
      const add=document.createElement('button');add.type='button';add.className='chat-add-person';add.title='Mention a teammate';add.setAttribute('aria-label','Mention a teammate');add.innerHTML='<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M15 20v-2a4 4 0 0 0-4-4H7a4 4 0 0 0-4 4v2M9 10a4 4 0 1 0 0-8 4 4 0 0 0 0 8M19 8v6M16 11h6"/></svg>';
      add.textContent='@';add.onclick=()=>{field.focus();field.setRangeText((field.value&&!/\s$/.test(field.value)?' ':'')+'@',field.selectionStart,field.selectionEnd,'end');field.dispatchEvent(new Event('input',{bubbles:true}));};
      const shell=document.createElement('div');shell.className='chat-compose-shell';field.before(shell);shell.append(field,tools);tools.append(add,submit);
      const filename=tools.querySelector('[data-attachment-name]');if(filename){filename.setAttribute('aria-live','polite');filename.classList.add('chat-file-status');filename.hidden=true;tools.querySelector('input[type=file]')?.addEventListener('change',event=>filename.hidden=!event.target.files.length);}
      if(footer){footer.className='chat-compose-hint';footer.replaceChildren(document.createTextNode('Enter to send · Shift+Enter for a new line'));}
    });
    document.addEventListener('click',event=>document.querySelectorAll('.thread-assignment[open]').forEach(details=>{if(!details.contains(event.target))details.open=false;}));
    document.addEventListener('keydown',event=>{if(event.key==='Escape')document.querySelectorAll('.thread-assignment[open]').forEach(details=>{details.open=false;details.querySelector('summary').focus();event.stopPropagation();});},true);
  }
  document.readyState==='loading'?document.addEventListener('DOMContentLoaded',boot):boot();
})();
