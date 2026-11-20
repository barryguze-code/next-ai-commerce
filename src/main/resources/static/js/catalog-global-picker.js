(()=>{
 const dialog=document.getElementById('product-dialog');if(!dialog)return;
 const form=dialog.querySelector('form'),query=dialog.querySelector('#catalog-global-query'),list=dialog.querySelector('#catalog-global-results');
 const feedback=dialog.querySelector('#catalog-global-feedback'),preview=dialog.querySelector('[data-global-selected]'),createFields=dialog.querySelector('[data-create-product]');
 const account=dialog.querySelector('[data-account-details]'),add=dialog.querySelector('[data-add-to-account]'),existing=dialog.querySelector('[data-global-existing]');
 const create=dialog.querySelector('[data-global-create]'),retry=dialog.querySelector('[data-global-retry]');
 const globalId=form.elements.globalProductId,revision=form.elements.globalRevision,conflict=dialog.querySelector('[data-global-conflict]');
 let duplicate=false;
 let context=null,saving=false,defaultsSequence=0,entryHints={},dialogGeneration=0;
 let selected=null,creating=false,options=[],active=-1,sequence=0,timer=null,pending=null;
 const text=(tag,value)=>{const node=document.createElement(tag);node.textContent=value||'';return node;};
 function finish(item,created){const done=context;context=null;dialog.close();done?.onSelect(item,created);}
 function accountChoice(id,identifier,created){return {id,name:selected?.name||form.elements.name.value,brand:selected?.brand||form.elements.brand.value,identifier,accountSku:created?form.elements.accountSku.value:null,imageUrl:created?selected?.imageUrl||null:null};}
 function setFeedback(message,error=false){feedback.textContent=message;feedback.classList.toggle('catalog-feedback-error',error);feedback.setAttribute('role',error?'alert':'status');feedback.setAttribute('aria-live',error?'assertive':'polite');}
 function clearConflict(){duplicate=false;conflict.replaceChildren();conflict.hidden=true;form.elements.identifier.removeAttribute('aria-invalid');setFeedback('');}
 function visibility(){dialog.querySelector('[data-global-search]').hidden=!!selected;createFields.hidden=!creating;createFields.disabled=!creating;account.hidden=(!selected&&!creating)||!!selected?.accountItemId;add.hidden=!!selected?.accountItemId;add.disabled=saving||duplicate||(!selected&&!creating);existing.hidden=!selected?.accountItemId;}
 function closeResults(){list.hidden=true;query.setAttribute('aria-expanded','false');query.removeAttribute('aria-activedescendant');active=-1;}
 function clearSelection(){clearConflict();if(selected){form.elements.vendorItemCode.value='';form.elements.unitCost.value='';form.elements.accountSku.value='';dialog.querySelector('[data-vendor-code-source]').textContent='';}selected=null;creating=true;globalId.value='';revision.value='';globalId.disabled=revision.disabled=true;preview.replaceChildren();preview.hidden=true;existing.removeAttribute('href');visibility();}
 function image(item){if(!item.imageUrl||!new RegExp('^/app/catalog/global-products/'+item.id+'/image$').test(item.imageUrl))return null;const img=document.createElement('img');img.className='catalog-global-picture';img.src=item.imageUrl;img.alt='';img.addEventListener('error',()=>img.remove());return img;}
 function summary(target,item,label,action){
  target.replaceChildren();const picture=image(item);if(picture)target.append(picture);const details=document.createElement('div');
  details.className='catalog-product-copy';details.append(text('strong',item.name));
  const metadata=document.createElement('div');metadata.className='catalog-product-meta';
  if(item.brand)metadata.append(text('span',item.brand));const identifier=text('span',`${item.identifierType}: ${item.identifier}`);identifier.className='catalog-product-identifier';metadata.append(identifier,text('span',`${item.size||'Size not supplied'} ${item.unitOfMeasure||'EA'}`));details.append(metadata);
  if(item.expirationRequired)details.append(text('small','Expiration dates tracked'));
  const button=text('button',label);button.type='button';button.className='secondary-button compact-button catalog-change-product';button.onclick=action;target.append(details,button);target.hidden=false;return button;
 }
 function select(item,preserveDraft=false){
  sequence++;defaultsSequence++;pending?.abort();clearConflict();selected=item;creating=false;closeResults();create.hidden=retry.hidden=true;
  globalId.value=item.id;revision.value=item.revision;globalId.disabled=revision.disabled=false;
  summary(preview,item,'Change product',()=>{clearSelection();query.focus();search();});
  setFeedback(item.accountItemId?'Already in your account. Open the existing item.':'');
  if(item.accountItemId)existing.href='/app/catalog?q='+encodeURIComponent(item.identifier)+'#catalog-item-'+item.accountItemId;
  existing.textContent=context?'Use existing product':'Open existing item';
  visibility();if(!preserveDraft)loadVendorDefaults(false);
 }
 function showDuplicate(result){
  sequence++;defaultsSequence++;clearTimeout(timer);pending?.abort();closeResults();options=[];clearConflict();duplicate=true;setFeedback(result.error,true);form.elements.identifier.setAttribute('aria-invalid','true');
  const item=result.product;
  if(item?.id&&item.revision){
   const label=item.accountItemId?(context?'Use existing product':'Open existing item'):'Use this product';
   const choose=summary(conflict,item,label,()=>{select(item,true);(item.accountItemId?existing:add).focus();});choose.focus();
  }else{setFeedback('A product with this UPC, EAN or GTIN already exists. Search by brand, product name or UPC to find an available product.',true);form.elements.identifier.focus();}
  visibility();
 }
 for(const field of [form.elements.identifier,form.elements.identifierType])field.addEventListener('input',()=>{clearConflict();visibility();});
 function highlight(index){if(!options.length)return;active=(index+options.length)%options.length;[...list.children].forEach((el,i)=>el.setAttribute('aria-selected',String(i===active)));query.setAttribute('aria-activedescendant',list.children[active].id);list.children[active].scrollIntoView({block:'nearest'});}
 async function search(){
  const value=query.value.trim(),request=++sequence;pending?.abort();pending=new AbortController();closeResults();create.hidden=retry.hidden=true;
  if(value.length<2){feedback.textContent='Enter at least two characters to search.';return;}
  feedback.textContent='Searching Global Catalogue…';
  try{
   const response=await fetch('/app/catalog/product-search?scope=global&q='+encodeURIComponent(value),{headers:{Accept:'application/json'},signal:pending.signal});
   if(!response.ok)throw new Error('search');const result=await response.json();if(!Array.isArray(result))throw new Error('format');
   if(request!==sequence||query.value.trim()!==value)return;
   options=result;list.replaceChildren();active=-1;
   options.forEach((item,index)=>{const button=document.createElement('button');button.type='button';button.className='catalog-global-option';button.id='catalog-global-option-'+index;button.setAttribute('role','option');button.setAttribute('aria-selected','false');button.tabIndex=-1;
    const picture=image(item);if(picture)button.append(picture);const label=document.createElement('span');label.append(text('strong',item.name),text('small',[item.brand,`${item.identifierType}: ${item.identifier}`,item.size].filter(Boolean).join(' · ')));if(item.accountItemId)label.append(text('small','Already in your account'));button.append(label);button.onclick=()=>select(item);list.append(button);});
   list.hidden=!options.length;query.setAttribute('aria-expanded',String(!!options.length));create.hidden=false;feedback.textContent=options.length?'Choose a global product to review.':'No matching global product. You can create a new product with a valid identifier.';
  }catch(error){if(request!==sequence||error.name==='AbortError')return;options=[];closeResults();feedback.textContent='Global Catalogue search failed. Retry the search; no product has been selected.';retry.hidden=false;}
 }
 query.addEventListener('input',()=>{clearTimeout(timer);sequence++;pending?.abort();options=[];closeResults();clearSelection();create.hidden=retry.hidden=true;feedback.textContent='Searching Global Catalogue…';timer=setTimeout(search,200);});
 query.addEventListener('keydown',event=>{if(event.key==='ArrowDown'&&options.length&&!list.hidden){event.preventDefault();highlight(active+1);}else if(event.key==='ArrowUp'&&options.length&&!list.hidden){event.preventDefault();highlight(active<0?options.length-1:active-1);}else if(event.key==='Enter'){event.preventDefault();if(active>=0&&!list.hidden)select(options[active]);else search();}else if(event.key==='Escape'&&!list.hidden){event.preventDefault();event.stopPropagation();closeResults();}});
 retry.onclick=search;create.onclick=()=>{sequence++;pending?.abort();clearSelection();creating=true;closeResults();create.hidden=retry.hidden=true;feedback.textContent='Create a new global product using its valid UPC, EAN, or GTIN.';
  const value=query.value.trim();if(/^(?:\d{8}|\d{12}|\d{13}|\d{14})$/.test(value)){form.elements.identifier.value=value;form.elements.identifierType.value=value.length===12?'UPC':value.length===13?'EAN':'GTIN';}visibility();form.elements.name.focus();};
 function applyDraftVendorCode(){
  const field=form.elements.vendorItemCode,hint=dialog.querySelector('[data-vendor-code-source]');if(field.value||!form.elements.vendorId.value)return;
  const sku=String(entryHints.vendorItemCode||'').trim(),search=String(entryHints.lastQuery||'').trim(),value=sku||search;
  if(!value||value.length>160||/[\r\n]/.test(value))return;field.value=value;hint.textContent=sku?'Detected from SKU — review':'From your last search — review';
 }
 async function loadVendorDefaults(reset){
  const vendor=form.elements.vendorId,code=form.elements.vendorItemCode,cost=form.elements.unitCost,chosen=!!vendor.value,version=++defaultsSequence;
  code.disabled=cost.disabled=!chosen;cost.required=chosen;if(reset){code.value='';cost.value='';dialog.querySelector('[data-vendor-code-source]').textContent='';}
  dialog.querySelector('[data-cost-currency]').textContent=chosen?'Currency: '+(vendor.selectedOptions[0]?.dataset.currency||''):'In the selected vendor’s currency.';
  if(!chosen)return;if(!selected){applyDraftVendorCode();return;}const product=selected.id,vendorId=vendor.value;
  try{const response=await fetch('/app/catalog/product-defaults?globalProductId='+encodeURIComponent(product)+'&vendorId='+encodeURIComponent(vendorId),{headers:{Accept:'application/json'}});if(!response.ok)throw Error();const defaults=await response.json();if(version!==defaultsSequence||selected?.id!==product||vendor.value!==vendorId)return;
   if(!code.value&&defaults.vendorItemCode)code.value=defaults.vendorItemCode;
   if(!cost.value&&defaults.unitCost!=null)cost.value=defaults.unitCost;
   if(!form.elements.accountSku.value)form.elements.accountSku.value=defaults.accountSku||defaults.vendorItemCode||'';applyDraftVendorCode();
  }catch{if(version===defaultsSequence){applyDraftVendorCode();feedback.textContent='Saved vendor defaults could not be loaded. Review the code and enter the cost; nothing was saved.';}}
 }
 form.elements.vendorId.addEventListener('change',()=>loadVendorDefaults(true));
 form.elements.vendorItemCode.addEventListener('input',()=>dialog.querySelector('[data-vendor-code-source]').textContent='');
 existing.addEventListener('click',event=>{if(context&&selected?.accountItemId){event.preventDefault();finish(accountChoice(selected.accountItemId,selected.identifier,false),false);}});
 form.addEventListener('submit',async event=>{
  if(!creating&&!selected){event.preventDefault();feedback.textContent='Choose a global product or create a new product first.';return;}
  if(selected?.accountItemId){event.preventDefault();existing.focus();return;}
  event.preventDefault();if(saving||duplicate)return;saving=true;add.disabled=true;setFeedback('Adding product…');
  const submission=context,generation=dialogGeneration,choice=accountChoice(null,null,true),submittedGlobalId=globalId.value,submittedRevision=revision.value;
  const data=new FormData(form),csrf=document.getElementById('buy-shipping-csrf'),headers={Accept:'application/json'};if(csrf)headers[csrf.dataset.header]=csrf.value;
  try{
   const response=await fetch(form.action,{method:'POST',body:data,headers});
   if(!response.headers.get('content-type')?.includes('application/json'))throw Error('Your session may have expired. Sign in again; your entries are unchanged.');
   const result=await response.json();if(dialogGeneration!==generation||context!==submission||!dialog.open)return;
   if(!response.ok){
    if(result.code==='GLOBAL_PRODUCT_EXISTS'){
     if(globalId.value!==submittedGlobalId||revision.value!==submittedRevision||form.elements.identifier.value!==data.get('identifier')||form.elements.identifierType.value!==data.get('identifierType')){setFeedback('Your product details changed while saving. Review them and try again.',true);return;}
     showDuplicate(result);return;
    }
    throw Error(result.error||'The product could not be added. Review your entries and try again.');
   }
   if(!result.id)throw Error('The product could not be confirmed. Your entries are unchanged; please try again.');
   if(submission)finish({...choice,id:result.id,identifier:result.identifier,accountSku:result.created?choice.accountSku:null},result.created);
   else location.assign('/app/catalog?q='+encodeURIComponent(result.identifier)+'#catalog-item-'+result.id);
  }catch(error){if(dialogGeneration===generation&&context===submission&&dialog.open)setFeedback(error.name==='TypeError'?'We couldn’t reach the catalogue. Your entries are unchanged; please try again.':error.message,true);}
  finally{if(dialogGeneration===generation){saving=false;if(context===submission&&dialog.open)visibility();}}
 });
 dialog.addEventListener('cancel',event=>{if(saving)event.preventDefault();});
 dialog.addEventListener('close',()=>{dialogGeneration++;saving=false;const previous=context;context=null;previous?.onClose?.();sequence++;defaultsSequence++;clearTimeout(timer);pending?.abort();closeResults();form.reset();clearSelection();options=[];create.hidden=retry.hidden=true;form.elements.vendorItemCode.disabled=form.elements.unitCost.disabled=true;form.elements.unitCost.required=false;setFeedback('Search by UPC, product name or brand.');});
 dialog.querySelector('[data-global-search]').hidden=false;visibility();
 window.openCatalogAddProduct=(options=null)=>{entryHints={vendorItemCode:options?.vendorItemCode||'',lastQuery:options?.lastQuery??options?.query??''};context=options?.onSelect?options:null;if(!dialog.open){dialogGeneration++;creating=true;visibility();dialog.showModal();}query.focus();if(options?.query||options?.mode==='create'){query.value=options.query||'';if(options.mode==='create')create.click();else search();}};
 if(new URLSearchParams(location.search).get('addProduct')==='true')window.openCatalogAddProduct();
})();
