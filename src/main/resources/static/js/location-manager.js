async function createManagedLocation(event,form){
  event.preventDefault();
  const submit=form.querySelector('[type="submit"]');
  const feedback=form.querySelector('[data-location-feedback]');
  const originalLabel=submit.textContent;
  submit.disabled=true;submit.textContent='Adding…';feedback.hidden=true;feedback.classList.remove('error');
  try{
    const response=await fetch(form.action,{method:'POST',body:new FormData(form)});
    const result=await response.json();
    if(!response.ok)throw new Error(result.error||'The location could not be added.');
    const label=result.code+' · '+result.name;
    document.querySelectorAll('select[name="destinationLocationId"],select[name="locationId"]').forEach(select=>{
      if(!Array.from(select.options).some(option=>option.value===result.id))select.add(new Option(label,result.id));
    });
    const list=form.querySelector('.location-directory-list');
    list.querySelector('.location-directory-empty')?.remove();
    const article=document.createElement('article');
    const code=document.createElement('span');code.className='location-code';code.textContent=result.code;
    const details=document.createElement('div');
    const name=document.createElement('strong');name.textContent=result.name;
    const help=document.createElement('small');help.textContent='Available for receipts, physical counts, and product defaults';
    const status=document.createElement('b');status.className='location-status';status.textContent='active';
    details.append(name,help);article.append(code,details,status);list.append(article);
    const count=form.querySelector('.location-directory-count');
    if(count)count.textContent=list.querySelectorAll('article').length+' in this account';
    const destination=document.getElementById('location-destination');
    if(destination){destination.value=result.id;destination.dispatchEvent(new Event('change',{bubbles:true}));const safety=document.getElementById('location-safety');if(safety)safety.textContent=label+' was added to this account and selected for this batch.'}
    form.reset();feedback.textContent=label+' added. You can add another location or choose Done.';feedback.hidden=false;
    list.scrollTo({top:list.scrollHeight,behavior:'smooth'});form.elements.code.focus();
  }catch(problem){feedback.textContent=problem.message;feedback.classList.add('error');feedback.hidden=false}
  finally{submit.disabled=false;submit.textContent=originalLabel}
  return false;
}
