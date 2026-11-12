/* One review entry point for catalogue, inventory and receiving. No automatic writes. */
(()=>{'use strict';
 const make=(tag,cls,text)=>{const e=document.createElement(tag);e.className=cls||'';if(text)e.textContent=text;return e};
 window.openProductMappingReview=async trigger=>{
  const scope={item:trigger.dataset.mappingItem||'',document:trigger.dataset.mappingDocument||''};
  const dialog=make('dialog','mapping-widget'),form=make('form','dialog-shell');
  const header=make('header'),copy=make('div'),title=make('h2','','Map product'),close=make('button','mw-close','×');title.id='product-mapping-review-title';dialog.setAttribute('aria-labelledby',title.id);close.type='button';close.setAttribute('aria-label','Close mapping review');copy.append(title);header.append(copy,close);
  const body=make('section','mw-body'),label=make('label'),input=make('input'),list=make('div','mw-results product-mapping-results'),status=make('p','mw-feedback');status.setAttribute('role','status');input.type='search';input.placeholder='Search account products';input.setAttribute('aria-label','Search account products');label.append(input);body.append(label,status,list);form.append(header,body);dialog.append(form);form.onsubmit=e=>e.preventDefault();document.body.append(dialog);
  let controller,sequence=0,timer,product=null;
  const finish=()=>{controller?.abort();clearTimeout(timer);dialog.close();dialog.remove();trigger.focus({preventScroll:true})};close.onclick=finish;dialog.addEventListener('cancel',e=>{e.preventDefault();finish()});
  async function load(){controller?.abort();controller=new AbortController();const version=++sequence;list.replaceChildren();status.textContent='Loading…';
   const params=new URLSearchParams({q:input.value});if(product)params.set('item',product.id);if(!product)for(const [key,value]of Object.entries(scope))if(value)params.set(key,value);
   try{const response=await fetch('/app/catalog/mapping-review/'+(product?'listings':'products')+'?'+params,{signal:controller.signal,cache:'no-store'});if(!response.ok)throw Error(response.status===403?'You do not have permission to map this account.':'Choose a marketplace store and try again.');const rows=await response.json();if(version!==sequence||!dialog.open)return;
    if(!product&&scope.item&&rows.length===1){selectProduct(rows[0]);return;}status.textContent=rows.length?(product?'Choose a SKU to review its mapping.':'Choose a product to map.'):(product?'No matching SKUs. Search by seller SKU, ASIN or product title.':'No products match this view.');
    rows.forEach(row=>{const button=make('button','secondary-button');button.type='button';button.className='mw-option';const words=make('span');words.append(make('strong','',product?row.sellerSku:row.name),make('small','',product?row.productTitle:row.code));if(row.image){const image=make('img');image.src=row.image;image.alt='';image.onerror=()=>image.hidden=true;button.append(image);}button.append(words);button.onclick=()=>{
     if(!product){selectProduct(row);return;}
     const source=make('button');Object.entries(row).forEach(([key,value])=>{if(value!=null)source.dataset[key]=value});source.focus=()=>trigger.focus({preventScroll:true});source.dataset.canEdit='true';source.dataset.reviewProduct=JSON.stringify({id:product.id,name:product.name});finish();window.NextAiMappingWidget.open(source);
    };list.append(button)});
   }catch(error){if(error.name!=='AbortError'&&version===sequence)status.textContent=error.message;}
  }
  const change=make('button','secondary-button','Change product');change.type='button';change.hidden=true;header.insertBefore(change,close);change.onclick=()=>{product=null;title.textContent='Map product';change.hidden=true;input.value='';input.placeholder='Search account products';load();input.focus()};
  function selectProduct(row){product=row;title.textContent=row.name;change.hidden=!!scope.item;input.placeholder='Search marketplace SKU, ASIN or title';input.setAttribute('aria-label','Search marketplace SKUs');input.value='';load();}
  input.oninput=()=>{clearTimeout(timer);timer=setTimeout(load,180)};dialog.showModal();load();input.focus();
 };
})();
