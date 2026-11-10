/* Restore only the same view after an existing action requires a document reload. */
(()=>{'use strict';
 const key='nextai.table.return',view=()=>location.pathname+location.search;
 function capture(){const positions=[...document.querySelectorAll('main,.main-content,.table-wrap,.table-data-scroll')].map(e=>[e,e.scrollTop,e.scrollLeft]),x=scrollX,y=scrollY;return()=>{positions.forEach(([e,t,l])=>{if(e.isConnected){e.scrollTop=t;e.scrollLeft=l}});window.scrollTo(x,y)}}
 function remember(){try{sessionStorage.setItem(key,JSON.stringify({view:view(),account:document.querySelector('#general-task-context')?.dataset.account,queries:[...document.querySelectorAll('.table-standard-search input')].map(e=>e.value),x:scrollX,y:scrollY,page:document.querySelector('[aria-label="Page number"]')?.value,positions:[...document.querySelectorAll('main,.main-content,.table-wrap,.table-data-scroll')].map(e=>[e.scrollTop,e.scrollLeft])}));}catch{}}
 function reload(){remember();location.reload()}
 function restore(){let saved;try{saved=JSON.parse(sessionStorage.getItem(key)||'null');sessionStorage.removeItem(key)}catch{}if(!saved||saved.view!==view()||saved.account!==document.querySelector('#general-task-context')?.dataset.account)return;requestAnimationFrame(()=>{[...document.querySelectorAll('.table-standard-search input')].forEach((e,i)=>{if(saved.queries?.[i]!=null){e.value=saved.queries[i];e.dispatchEvent(new Event('input',{bubbles:true}))}});const page=document.querySelector('[aria-label="Page number"]');if(page&&saved.page){page.value=saved.page;page.dispatchEvent(new Event('change',{bubbles:true}))}requestAnimationFrame(()=>{[...document.querySelectorAll('main,.main-content,.table-wrap,.table-data-scroll')].forEach((e,i)=>{if(saved.positions[i]){e.scrollTop=saved.positions[i][0];e.scrollLeft=saved.positions[i][1]}});window.scrollTo(saved.x,saved.y)})})}
 document.addEventListener('submit',event=>{if(event.target.method?.toLowerCase()==='post')queueMicrotask(()=>{if(!event.defaultPrevented)remember()})});
 async function saveMapping(form,submitter){
  const restorePosition=capture(),data=new FormData(form),url=submitter?.hasAttribute('formaction')?submitter.formAction:form.action;
  const context=()=>{const c=document.querySelector('#general-task-context');return [c?.dataset.account||'',c?.dataset.store||''].join('|')},origin=context(),originView=view();
  data.set('_mappingInline','true');data.set('originAccount',origin.split('|')[0]);data.set('originStore',origin.split('|')[1]);
  if(location.pathname==='/app/marketplace-skus'){
   const source=[...document.querySelectorAll('.sku-table tr[data-sku]')].find(row=>row.dataset.sku===data.get('sellerSku'));
   const asin=source?.querySelector('a[title="Open Amazon product page"]')?.href.split('/dp/')[1];
   const target=new URL(location.href);target.searchParams.set('q',asin?decodeURIComponent(asin):data.get('sellerSku')||'');target.searchParams.set('status','ALL');target.searchParams.set('page','0');target.searchParams.delete('goToPage');target.searchParams.set('size','100');data.set('returnTo',target.pathname+target.search);
  }
  const response=await fetch(url,{method:'POST',body:data});
  if(!response.ok)throw Error('Mapping save was not confirmed. Review before retrying.');
  const doc=new DOMParser().parseFromString(await response.text(),'text/html');
  const returned=doc.querySelector('#general-task-context');if(view()!==originView||context()!==origin||!returned||([returned.dataset.account||'',returned.dataset.store||''].join('|')!==origin))throw Error('Workspace changed. Mapping response was not applied to this table. Refresh to review.');
  const error=doc.querySelector('.notice.error');if(error)throw Error(error.textContent.trim());
  if(!doc.querySelector('.notice:not(.error)'))throw Error('Mapping save was not confirmed. Review before retrying.');
  const fresh=[...doc.querySelectorAll('.sku-table tr[data-sku]')];
  let updated=0;
  document.querySelectorAll('.sku-table tr[data-sku]').forEach(row=>{
   const replacement=fresh.find(r=>row.dataset.listingId?r.dataset.listingId===row.dataset.listingId:r.dataset.sku===row.dataset.sku);if(!replacement)return;
   [...row.cells].forEach(cell=>{const label=cell.dataset.label;if(!label)return;const freshCell=[...replacement.cells].find(c=>c.dataset.label===label);if(!freshCell)return;const clone=freshCell.cloneNode(true);if(label==='Product details'){const id=row.querySelector('span[hidden][data-seller-sku]')?.id;clone.querySelectorAll('.sku-image,.product-thumbnail-slot,[data-context-menu],.collaboration-row-button').forEach(n=>n.remove());if(id){clone.querySelectorAll('[data-target]').forEach(n=>n.dataset.target=id);clone.querySelectorAll('[data-mapping-dialog]').forEach(n=>n.dataset.mappingDialog=id);const marker=clone.querySelector('span[hidden][data-seller-sku]');if(marker)marker.id=id;}}cell.replaceChildren(...[...clone.childNodes].map(n=>document.importNode(n,true)));});
   row.classList.toggle('sku-unmapped',replacement.classList.contains('sku-unmapped'));updated++;
  });
  if(location.pathname==='/app/orders'){
   const freshItems=[...doc.querySelectorAll('.order-item')];
   document.querySelectorAll('.order-item').forEach(item=>{const sku=item.querySelector('[data-seller-sku]')?.dataset.sellerSku,order=item.dataset.exportOrder;const replacement=freshItems.find(r=>r.querySelector('[data-seller-sku]')?.dataset.sellerSku===sku&&r.dataset.exportOrder===order);if(replacement&&sku){const mapping=item.querySelector('.item-map'),freshMapping=replacement.querySelector('.item-map');if(mapping&&freshMapping){item.querySelector('.order-mapping-inline')?.remove();mapping.replaceWith(document.importNode(freshMapping,true));}for(const name of ['exportMapping','unmapped','stockShortage'])if(replacement.dataset[name]!=null)item.dataset[name]=replacement.dataset[name];const stage=item.querySelector('[data-picture-actions]'),freshStage=replacement.querySelector('[data-picture-actions]');if(stage&&freshStage)stage.dataset.mapping=freshStage.dataset.mapping;const available=item.querySelector('[data-column="available"]'),freshAvailable=[...replacement.querySelectorAll('.item-number')].find(n=>n.querySelector('small')?.textContent.trim()==='Available');if(available&&freshAvailable&&!available.querySelector('.fba-stock'))available.replaceChildren(...[...freshAvailable.childNodes].map(n=>document.importNode(n,true)));updated++;}});
  }
  window.NextAiMarketplaceRows?.enhance(document.querySelector('.sku-table'));window.NextAiTableDataTools?.refresh();
  restorePosition();requestAnimationFrame(restorePosition);
  const message=updated?'Mapping saved. Current view kept.':'Mapping saved. Refresh this view to see its updated details.';let notice=document.querySelector('[data-mapping-save-status]');if(!notice){notice=document.createElement('p');notice.className='notice';notice.dataset.mappingSaveStatus='';notice.setAttribute('role','status');document.querySelector('main')?.prepend(notice);}notice.textContent=message;restorePosition();requestAnimationFrame(restorePosition);return message;
 }
 window.NextAiTableContinuity={capture,reload,saveMapping};if(document.readyState==='loading')document.addEventListener('DOMContentLoaded',restore);else restore();
})();
