async function repairPhysicalCount(button){
  const panel=button.closest('[data-count-repair]');
  const reviewForm=panel.closest('form');
  const feedback=panel.querySelector('[data-repair-feedback]');
  const original=button.textContent;
  const data=new FormData();
  const csrf=reviewForm.querySelector('input[name="_csrf"]');
  if(csrf)data.append('_csrf',csrf.value);
  if(panel.dataset.endpoint.endsWith('location-options')){
    data.append('code',panel.querySelector('[name="repairCode"]').value);
    data.append('name',panel.querySelector('[name="repairName"]').value);
  }else{
    data.append('accountSku',panel.querySelector('[name="repairAccountSku"]').value);
    data.append('name',panel.querySelector('[name="repairProductName"]').value);
    data.append('brand',panel.querySelector('[name="repairBrand"]').value);
    data.append('identifier',panel.querySelector('[name="repairIdentifier"]').value);
    data.append('identifierType','UPC');
    data.append('vendorId',panel.querySelector('[name="repairVendor"]').value);
    data.append('unitCost',panel.querySelector('[name="repairUnitCost"]').value);
    data.append('expirationRequired',panel.querySelector('[name="repairExpiration"]').checked?'true':'false');
  }
  if(!Array.from(panel.querySelectorAll('input[required]')).every(input=>input.reportValidity()))return;
  button.disabled=true;button.textContent='Saving…';feedback.hidden=true;feedback.classList.remove('error');
  try{
    const response=await fetch(panel.dataset.endpoint,{method:'POST',body:data,headers:{'Accept':'application/json'}});
    const result=await response.json();
    if(!response.ok)throw new Error(result.error||'This fix could not be saved.');
    feedback.textContent=(result.code||result.name)+' added. Continuing your physical count…';feedback.hidden=false;
    reviewForm.dataset.confirmed='true';
    window.setTimeout(()=>reviewForm.requestSubmit(),250);
  }catch(problem){feedback.textContent=problem.message;feedback.classList.add('error');feedback.hidden=false;button.disabled=false;button.textContent=original}
}
