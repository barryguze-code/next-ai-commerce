(()=>{'use strict';
 const main=document.querySelector('.sku-table');if(!main)return;
 const keys=['record-context','product-details','status','price-shipping','available','4w-sales','shipping-template','condition','fnsku','inbound','reserved','unfulfillable','profit'];
 function align(details){
  const table=document.getElementById(details.dataset.familyPanel)?.querySelector('.sku-family-table');if(!table)return;
  const columns=[...main.tHead.rows[0].cells].filter(h=>keys.includes(h.dataset.column));
  table.tHead.rows[0].replaceChildren(...columns.map(h=>{const n=document.createElement('th');n.textContent=h.dataset.title||h.textContent.trim();n.scope='col';n.dataset.column=h.dataset.column;n.hidden=h.hidden||getComputedStyle(h).display==='none';return n;}));
  for(const row of table.tBodies[0].rows){
   const cells=[...row.cells];cells.forEach((c,i)=>{if(!c.dataset.column)c.dataset.column=keys[i];});
   const byKey=new Map(cells.map(c=>[c.dataset.column,c]));
   for(const h of columns){const c=byKey.get(h.dataset.column);if(c){c.hidden=h.hidden||getComputedStyle(h).display==='none';row.append(c);}}
  }
 }
 document.querySelectorAll('.sku-family').forEach((details,index)=>{
  const id='sku-family-panel-'+index;details.dataset.familyPanel=id;details.querySelector('summary').setAttribute('aria-controls',id);
  details.addEventListener('toggle',()=>{
   let panel=document.getElementById(id);
   if(!details.open){if(panel)panel.hidden=true;return;}
   if(!panel){
    const source=details.querySelector('template');panel=document.createElement('tr');panel.id=id;panel.dataset.familyDisclosure='true';
    const cell=document.createElement('td');cell.colSpan=main.tHead.rows[0].cells.length;cell.append(source.content.cloneNode(true));panel.append(cell);details.closest('tr').after(panel);
    for(const row of panel.querySelectorAll('.sku-family-table>tbody>tr')){
     const cell=document.createElement('td');cell.dataset.column='record-context';cell.dataset.label='Record';
     const layout=document.createElement('div');layout.className='table-overview-layout';
     const picture=row.querySelector('.product-avatar');if(picture)picture.classList.add('table-overview-picture');
     const rail=document.createElement('div');rail.className='table-overview-rail';
     const chat=row.querySelector('.collaboration-row-button'),action=row.querySelector('.sku-open-actions');
     if(chat){rail.append(chat);window.prepareRecordCollaboration?.(chat);}if(action)rail.append(action);
     if(picture)layout.append(picture);layout.append(rail);cell.append(layout);row.prepend(cell);
    }
    const region=panel.querySelector('.sku-family-body');region.tabIndex=0;region.setAttribute('role','region');region.setAttribute('aria-label','Associated child variations');
    window.NextAiMarketplaceRows?.enhance(panel);window.NextAiPlatformControls?.enhance(panel);
   }
   panel.hidden=false;align(details);
  });
 });
 new MutationObserver(()=>document.querySelectorAll('.sku-family[open]').forEach(align)).observe(main.tHead,{attributes:true,subtree:true,childList:true,attributeFilter:['hidden','style','class']});
})();
