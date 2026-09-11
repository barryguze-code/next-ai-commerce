(()=>{
  const dialog=document.createElement('dialog');
  dialog.className='reservation-dialog';dialog.id='reservation-dialog';
  dialog.innerHTML='<div class="reservation-shell"><header><div><span>Reserved inventory</span><h2 id="reservation-title">Reserved orders</h2><p id="reservation-context"></p></div><button type="button" aria-label="Close reserved orders">×</button></header><section class="reservation-summary"><div><small>This batch</small><strong id="reservation-batch-total">0 each</strong></div><div><small>All batches</small><strong id="reservation-item-total">0 each</strong></div><div><small>Available here</small><strong id="reservation-available">0 each</strong></div></section><nav class="reservation-scope"><button class="active" type="button" data-scope="batch">Selected batch</button><button type="button" data-scope="item">All product reservations</button></nav><div id="reservation-orders" class="reservation-orders"><p>Loading reserved orders…</p></div></div>';
  document.body.append(dialog);
  const close=()=>dialog.close();dialog.querySelector('header button').onclick=close;
  dialog.addEventListener('click',event=>{const box=dialog.getBoundingClientRect();if(event.clientX<box.left||event.clientX>box.right||event.clientY<box.top||event.clientY>box.bottom)close()});
  let activeRow,batchRows=[],itemRows=[];
  const units=value=>Number(value||0).toLocaleString(undefined,{maximumFractionDigits:2});
  const total=rows=>rows.reduce((sum,row)=>sum+Number(row.reservedEaches||0),0);
  const expiration=value=>value?'Inventory expires '+new Intl.DateTimeFormat(undefined,{dateStyle:'medium'}).format(new Date(value+'T00:00:00')):'No expiration date';
  function draw(rows){
    const target=document.getElementById('reservation-orders');
    target.innerHTML=rows.length?rows.map(row=>{
      const shortage=Number(row.shortageEaches||0),status=(row.orderStatus||'Pending').replace(/([a-z])([A-Z])/g,'$1 $2');
      return '<article><div class="reservation-order-head"><span class="amazon-reservation-mark"><img src="/images/channels/amazon-round-transparent.png" alt="Amazon"></span><div class="reservation-order-copy"><a href="/app/orders?q='+encodeURIComponent(row.orderId)+'">'+escapeHistory(row.orderId)+'</a><small><strong>Amazon</strong><i>·</i><strong>'+escapeHistory(status)+'</strong><i>·</i><strong>Qty '+units(row.orderedSkus)+'</strong></small></div><code class="reservation-sku">'+escapeHistory(row.sellerSku||'SKU not provided')+'</code><b><strong>'+units(row.reservedEaches)+' each</strong><small>reserved</small></b></div><footer><span>⌖ '+escapeHistory(row.locationCode)+' · '+escapeHistory(row.locationName)+'</span><span>'+expiration(row.expirationDate)+'</span></footer>'+(shortage>0?'<p class="reservation-shortage">Short '+units(shortage)+' each for this order</p>':'')+'</article>';
    }).join(''):'<div class="reservation-empty">No active reservations were found. An order sync may have released or shipped them.</div>';
  }
  async function getRows(scope){
    const params=new URLSearchParams();
    if(scope==='batch'){params.set('locationId',activeRow.dataset.location);if(activeRow.dataset.expiration)params.set('expirationDate',activeRow.dataset.expiration)}
    const response=await fetch('/app/inventory/'+activeRow.dataset.item+'/reservations?'+params);
    if(!response.ok)throw new Error();return response.json();
  }
  window.openInventoryReservations=async row=>{
    activeRow=row;document.getElementById('reservation-title').textContent=row.dataset.product;
    document.getElementById('reservation-context').textContent=row.dataset.locationCode+(row.dataset.expiration?' · Expires '+row.dataset.expiration:' · FIFO inventory');
    document.getElementById('reservation-available').textContent=units(row.dataset.available)+' each';
    dialog.querySelectorAll('.reservation-scope button').forEach(button=>button.classList.toggle('active',button.dataset.scope==='batch'));
    document.getElementById('reservation-orders').innerHTML='<p>Loading reserved orders…</p>';dialog.showModal();
    try{[batchRows,itemRows]=await Promise.all([getRows('batch'),getRows('item')]);document.getElementById('reservation-batch-total').textContent=units(total(batchRows))+' each';document.getElementById('reservation-item-total').textContent=units(total(itemRows))+' each';draw(batchRows)}
    catch(_){document.getElementById('reservation-orders').innerHTML='<div class="reservation-empty error">Reserved orders could not be loaded. Please try again.</div>'}
  };
  dialog.querySelector('.reservation-scope').onclick=event=>{const button=event.target.closest('button');if(!button)return;dialog.querySelectorAll('.reservation-scope button').forEach(item=>item.classList.toggle('active',item===button));draw(button.dataset.scope==='batch'?batchRows:itemRows)};
})();
