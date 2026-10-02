(()=>{
  const zone=()=>Intl.DateTimeFormat().resolvedOptions().timeZone||'America/Los_Angeles';
  const label=value=>value?value.slice(5,7)+'/'+value.slice(8,10)+'/'+value.slice(2,4):'No due date';
  function prepare(review){
    const button=document.querySelector('[data-thread-due]');if(!button)return;
    Object.assign(button.dataset,{reviewId:String(review.id),dueDate:review.dueDate||'',timeZone:review.dueTimeZone||zone(),reviewTitle:review.subjectLabel||review.title});
    button.querySelector('span').textContent=review.dueDate?'Due '+label(review.dueDate):'Add due date';
    button.classList.toggle('is-overdue',review.status==='ACTIVE'&&!!review.dueAt&&Date.parse(review.dueAt)<=Date.now());
  }
  window.CollaborationTaskDates={prepare};
  function overdueRows(){
    document.querySelectorAll('tr[data-review-id][data-due-at]').forEach(row=>{
      const overdue=row.dataset.status==='ACTIVE'&&!!row.dataset.dueAt&&Date.parse(row.dataset.dueAt)<=Date.now();
      row.classList.toggle('task-overdue',overdue);const badge=row.querySelector('[data-overdue-label]');if(badge)badge.hidden=!overdue;
    });
  }
  function boot(){
    const editor=document.getElementById('collaboration-due-editor');if(!editor)return;
    const home=editor.parentElement,form=editor.querySelector('form'),error=editor.querySelector('[data-date-error]'),submit=form.querySelector('[type=submit]');let busy=false,active=null;
    function close(){if(busy)return;editor.hidden=true;home.append(editor);if(active){active.hidden=false;active.setAttribute('aria-expanded','false');active.focus();active=null;}}
    const start=document.querySelector('#thread-conversation-start [name=timeZone]');if(start)start.value=zone();
    document.addEventListener('click',event=>{
      const trigger=event.target.closest('[data-edit-due]');if(!trigger||trigger.disabled||!trigger.dataset.reviewId)return;
      event.preventDefault();event.stopPropagation();if(busy)return;if(active)close();active=trigger;form.action='/app/collaboration/reviews/'+encodeURIComponent(trigger.dataset.reviewId)+'/due-date';
      form.elements.dueDate.value=trigger.dataset.dueDate||'';form.elements.timeZone.value=trigger.dataset.timeZone||zone();
      error.hidden=true;trigger.after(editor);trigger.hidden=true;trigger.setAttribute('aria-expanded','true');editor.hidden=false;(form.elements.dueDate.closest('.short-date-control')?.querySelector('.short-date-text')||form.elements.dueDate).focus();
    });
    editor.querySelector('[data-date-cancel]').onclick=close;
    editor.addEventListener('keydown',event=>{if(event.key==='Escape'){event.preventDefault();event.stopPropagation();close();}});
    editor.addEventListener('click',event=>event.stopPropagation());
    document.getElementById('contextual-thread-dialog')?.addEventListener('close',close);
    form.addEventListener('submit',async event=>{
      event.preventDefault();if(busy||!form.reportValidity())return;busy=true;submit.disabled=true;error.hidden=true;
      try{
        const response=await fetch(form.action,{method:'POST',body:new FormData(form),headers:{Accept:'application/json'}}),result=await response.json().catch(()=>({}));
        if(!response.ok)throw new Error(response.status<500&&result.error?result.error:'The date could not be saved. Please try again.');
        document.querySelectorAll('tr[data-review-id]').forEach(row=>{if(row.dataset.reviewId!==String(result.id))return;row.dataset.dueAt=result.dueAt||'';const button=row.querySelector('[data-edit-due]');if(button){button.dataset.dueDate=result.dueDate||'';button.dataset.timeZone=result.dueTimeZone||zone();button.querySelector('span').textContent=label(result.dueDate);}});
        prepare(result);overdueRows();busy=false;close();
      }catch(ex){error.textContent=ex.message;error.hidden=false;}finally{busy=false;submit.disabled=false;}
    });
    overdueRows();setInterval(overdueRows,60000);
  }
  document.readyState==='loading'?document.addEventListener('DOMContentLoaded',boot):boot();
})();
