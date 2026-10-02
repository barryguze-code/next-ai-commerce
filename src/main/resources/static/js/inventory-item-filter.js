(()=>{
  const table=document.querySelector('.available-inventory-table'),search=document.getElementById('inventory-search');
  if(!table||!search)return;
  function refresh(){table.querySelectorAll('[data-item-filter]').forEach(button=>{
    const locked=table.dataset.lockedItem===button.dataset.itemFilter;
    button.setAttribute('aria-pressed',String(locked));button.title=locked?'Filter locked — click to show all items':'Click to show all batches of this item';
    button.setAttribute('aria-label',(locked?'Clear item filter for ':'Show all inventory for ')+button.dataset.filterCode);
    button.querySelector('img').src='/images/platform/'+(locked?'06-locked':'05-unlocked')+'.svg';
  });}
  search.addEventListener('input',()=>{delete table.dataset.lockedItem;window.applyInventoryFilters?.();refresh();});
  table.addEventListener('click',event=>{
    const button=event.target.closest('[data-item-filter]');if(!button)return;
    event.stopPropagation();const selected=table.dataset.lockedItem===button.dataset.itemFilter?'':button.dataset.itemFilter;
    search.value=selected?button.dataset.filterCode:'';search.dispatchEvent(new Event('input',{bubbles:true}));
    table.dataset.lockedItem=selected;
    if(selected){const all=document.querySelector('.inventory-filter-bar [data-filter="ALL"]');if(all)window.setInventoryStatus?.(all);}
    window.applyInventoryFilters?.();refresh();table.dispatchEvent(new Event('table:filter'));
  },true);
  refresh();
})();
