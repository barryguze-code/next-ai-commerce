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
    article.dataset.locationId=result.id;
    details.append(name,help);article.append(code,details,status);
    document.querySelectorAll('.location-directory-list').forEach(directory=>{
      directory.querySelector('.location-directory-empty')?.remove();
      const row=article.cloneNode(true);addLocationActions(row);directory.append(row);
      const count=directory.closest('.location-directory').querySelector('.location-directory-count');
      if(count)count.textContent=directory.querySelectorAll('article').length+' in this account';
    });
    const destination=document.getElementById('location-destination');
    if(destination&&form.closest('#inventory-location-dialog')){destination.value=result.id;destination.dispatchEvent(new Event('change',{bubbles:true}));const safety=document.getElementById('location-safety');if(safety)safety.textContent=label+' was added to this account and selected for this batch.'}
    form.reset();feedback.textContent=label+' added. You can add another location or choose Done.';feedback.hidden=false;
    list.scrollTo({top:list.scrollHeight,behavior:'smooth'});form.elements.code.focus();
  }catch(problem){feedback.textContent=problem.message;feedback.classList.add('error');feedback.hidden=false}
  finally{submit.disabled=false;submit.textContent=originalLabel}
  return false;
}

function addLocationActions(article){
  const actions=document.createElement('div');actions.className='location-actions';
  for(const [label,remove] of [['Edit',false],['Delete',true]]){
    const button=document.createElement('button');button.type='button';button.textContent=label;
    button.addEventListener('click',()=>editManagedLocation(button,remove));actions.append(button);
  }
  article.append(actions);
}

function editManagedLocation(button,remove){
  const article=button.closest('article'),form=article.closest('form');
  article.querySelector('.location-edit-panel')?.remove();
  const panel=document.createElement('div');panel.className='location-edit-panel';
  const explanation=document.createElement('p');
  explanation.textContent=remove?'Delete this unused location? Locations linked to stock, catalogue assignments or historical records cannot be deleted.':'Edit an unused location. Locations with stock or historical records are protected; any restrictions will be explained when you save.';
  panel.append(explanation);
  const code=document.createElement('input'),name=document.createElement('input');
  code.value=article.querySelector('.location-code').textContent;name.value=article.querySelector('strong').textContent;
  code.maxLength=80;name.maxLength=160;
  if(!remove){for(const [title,input] of [['Location code',code],['Location name',name]]){const label=document.createElement('label');label.textContent=title;label.append(input);panel.append(label);}}
  const feedback=document.createElement('p');feedback.setAttribute('role','status');feedback.hidden=true;
  const save=document.createElement('button');save.type='button';save.textContent=remove?'Delete location':'Save changes';
  const cancel=document.createElement('button');cancel.type='button';cancel.textContent='Cancel';cancel.onclick=()=>panel.remove();
  panel.addEventListener('keydown',event=>{if(event.key==='Enter'&&event.target.tagName==='INPUT'){event.preventDefault();if(!save.disabled)save.click();}});
  save.onclick=async()=>{
    save.disabled=true;cancel.disabled=true;feedback.hidden=true;
    try{
      const data=new FormData(form);data.set('code',code.value);data.set('name',name.value);data.set('delete',String(remove));
      const response=await fetch('/app/catalog/location-options/'+encodeURIComponent(article.dataset.locationId)+'/change',{method:'POST',body:data});
      const result=await response.json();if(!response.ok)throw new Error(result.error||'The location could not be changed.');
      document.querySelectorAll('[data-location-id]').forEach(row=>{
        if(row.dataset.locationId!==result.id)return;
        if(remove)row.remove();else{row.querySelector('.location-code').textContent=result.code;row.querySelector('strong').textContent=result.name;row.querySelector('.location-edit-panel')?.remove();}
      });
      document.querySelectorAll('select[name="destinationLocationId"],select[name="locationId"]').forEach(select=>{
        Array.from(select.options).filter(option=>option.value===result.id).forEach(option=>{if(remove)option.remove();else option.textContent=result.code+' · '+result.name;});
      });
      document.querySelectorAll('.location-directory').forEach(directory=>{directory.querySelector('.location-directory-count').textContent=directory.querySelectorAll('article').length+' in this account';});
      const message=form.querySelector('[data-location-feedback]');if(message){message.textContent=remove?'Location deleted.':'Location updated.';message.hidden=false;message.classList.remove('error');}
    }catch(error){feedback.textContent=error.message;feedback.hidden=false;}
    finally{save.disabled=false;cancel.disabled=false;}
  };
  panel.append(feedback,save,cancel);article.append(panel);panel.scrollIntoView({block:'nearest'});(remove?cancel:code).focus();
}
