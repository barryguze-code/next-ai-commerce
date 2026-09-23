async function loadRelatedSkus(itemId) {
  const target = document.getElementById("related-skus");
  const count = document.getElementById("related-sku-count");
  if (!target || !count) return;
  target.innerHTML = '<div class="history-loading">Loading marketplace SKUs…</div>';
  count.textContent = "Loading mappings…";
  const requestedItem=String(itemId);target.dataset.item=requestedItem;
  try {
    const response = await fetch(`/app/catalog/products/${itemId}/marketplace-skus`);
    if (!response.ok) throw new Error("SKU lookup failed");
    const rows = await response.json();
    if(target.dataset.item!==requestedItem)return;
    count.textContent = `${rows.length} ${rows.length === 1 ? "mapping" : "mappings"}`;
    target.innerHTML = rows.length ? rows.map(row => {
      const rawStatus=(row.status || "").toUpperCase();
      const statusLabel=rawStatus === "VISIBLE" ? "Active on Amazon" : rawStatus === "OUT_OF_STOCK" ? "Out of stock" : rawStatus === "AMAZON_PROBLEM" ? "Amazon attention" : rawStatus ? rawStatus.replaceAll("_", " ").toLowerCase() : "Mapped";
      const sku=encodeURIComponent(row.sku || "");
      const amazonUrl=row.asin ? `https://www.amazon.com/dp/${encodeURIComponent(row.asin)}` : "";
      return `
      <article>
        <div><strong title="${escapeHistory(row.sku)}">${escapeHistory(row.sku)}</strong><small>${escapeHistory(row.channel || "Marketplace")}${row.asin ? ` · ASIN ${escapeHistory(row.asin)}` : ""}</small></div>
        <span class="related-sku-status ${escapeHistory(rawStatus.toLowerCase())}">${escapeHistory(statusLabel)}</span>
        <dl>
          <div><dt>Eaches per SKU</dt><dd>${Number(row.quantity).toLocaleString()}</dd></div>
          <div><dt>${escapeHistory(row.channel || "Marketplace")} listed</dt><dd>${row.marketplaceQuantity == null ? "—" : row.marketplaceQuantity}</dd></div>
        </dl>
        <nav class="marketplace-links related-sku-actions" data-marketplace-shortcuts data-sku="${escapeHistory(row.sku)}" data-asin="${escapeHistory(row.asin || "")}" data-channel="${escapeHistory(row.channel || "")}" data-amazon-domain="${escapeHistory(row.amazonDomain || "amazon.com")}"></nav>
      </article>`}).join("") : '<div class="related-sku-empty">No marketplace SKU is mapped to this product yet.</div>';
    window.NextAiMarketplaceShortcuts?.enhance(target);
  } catch (error) {
    if(target.dataset.item!==requestedItem)return;
    count.textContent = "Unavailable";
    target.innerHTML = '<div class="related-sku-empty">Marketplace mappings could not be loaded.</div>';
  }
}

// The movement badge already names the transaction. Keep the adjacent copy useful
// by promoting its plain-language explanation and leaving the date/reference below.
const movementTarget=document.getElementById("history-movements");
if(movementTarget)new MutationObserver(()=>movementTarget.querySelectorAll(".history-movement").forEach(row=>{
  if(row.dataset.clarified)return;
  const detail=row.querySelector("div"),title=detail?.querySelector("strong"),meta=detail?.querySelector("small");
  if(!title||!meta)return;
  const parts=meta.textContent.split(" · ");
  if(parts.length>1){const reference=title.textContent.trim();title.textContent=parts.slice(1).join(" · ");meta.textContent=parts[0]+(reference&&!/^Physical count$/i.test(reference)?" · "+reference:"");}
  row.dataset.clarified="true";
})).observe(movementTarget,{childList:true});


(() => {
  const summary=document.querySelector(".history-summary"),title=document.querySelector(".position-section-title");
  if(!summary||!title)return;
  const total=document.createElement("div");total.className="history-product-total";
  total.innerHTML='<small>Total available</small><strong id="history-total-available">0 each</strong>';summary.append(total);
  const scope=document.createElement("nav");scope.className="history-scope-switch";scope.setAttribute("aria-label","Movement scope");
  scope.innerHTML='<button type="button" data-history-scope="batch" aria-pressed="false">Selected batch</button><button class="active" type="button" data-history-scope="item" aria-pressed="true">All item movements</button>';title.append(scope);
  let currentRow,selectedScope="item",request;
  const originalOpen=openInventoryHistory;
  openInventoryHistory=async function(row,focusLocation=false){
    currentRow=row;
    const opened=originalOpen(row,focusLocation);
    const hasCurrentPosition=Boolean(row.dataset.location);
    const batchButton=scope.querySelector('[data-history-scope="batch"]');
    batchButton.disabled=!hasCurrentPosition;
    batchButton.title=hasCurrentPosition?'Show movements for this location and expiration batch':'No current inventory batch; showing all item movements';
    if(!hasCurrentPosition)document.getElementById('history-subtitle').textContent=(row.dataset.sku||'No catalogue code')+' · No inventory currently on hand';
    const source=row.querySelector('.product-image-upload');
    let upload=document.getElementById('history-image-upload');
    if(!upload&&source){
      upload=document.createElement('div');upload.id='history-image-upload';
      document.getElementById('history-picture').append(upload);
    }
    if(upload){
      upload.replaceChildren();upload.hidden=!source;
      if(source){
        const form=source.cloneNode(true);form.querySelectorAll('.standard-picture-edit').forEach(el=>el.remove());form.className='history-image-upload-form';form.removeAttribute('onclick');
        const input=form.querySelector('input[type="file"]');
        const label=form.querySelector('label');label.replaceChildren();
        const button=document.createElement('button');button.type='button';
        button.textContent=row.dataset.image?'✎':'+';
        button.setAttribute('aria-label',row.dataset.image?'Change picture':'Upload picture');
        button.title=row.dataset.image?'Change picture':'Upload picture';
        button.onclick=()=>input.click();
        input.setAttribute('aria-label','Choose product picture');
        label.append(input);form.append(button);
        upload.append(form);
      }
    }
    document.getElementById("history-total-available").textContent=[...document.querySelectorAll('.inventory-row')].filter(x=>x.dataset.item===row.dataset.item).reduce((sum,x)=>sum+(Number(x.dataset.available)||0),0).toLocaleString()+" each";
    return opened;
  };
  window.loadInventoryMovements=async function(row,mode="item",page=0){
    request?.abort();request=new AbortController();const controller=request;
    const target=document.getElementById("history-movements");selectedScope=mode;
    scope.querySelectorAll("button").forEach(button=>{const active=button.dataset.historyScope===mode;button.classList.toggle("active",active);button.setAttribute("aria-pressed",String(active));});
    target.querySelector('[data-history-more]')?.remove();
    const feedback=document.createElement("div");feedback.className="history-loading";feedback.textContent="Loading inventory movements…";
    if(page===0)target.replaceChildren();target.append(feedback);
    const params=new URLSearchParams({scope:mode,page});
    if(mode==="batch"){params.set("locationId",row.dataset.location);if(row.dataset.expiration)params.set("expirationDate",row.dataset.expiration);}
    try{
      const response=await fetch('/app/inventory/'+encodeURIComponent(row.dataset.item)+'/movements?'+params,{signal:controller.signal,headers:{Accept:"application/json"}});
      if(!response.ok)throw new Error();
      const rows=await response.json();if(controller.signal.aborted)return;feedback.remove();
      for(const m of rows){
        const article=document.createElement("article");article.className="history-movement "+(Number(m.quantity)>0?"incoming":"outgoing");article.dataset.clarified="true";
        const batch=m.expirationDate?' · Batch '+escapeHistory(formatCalendarDate(m.expirationDate)):' · FIFO batch';
        const reference=m.sourceReference&&m.sourceType!=="PHYSICAL_COUNT"?' · '+escapeHistory(m.sourceReference):'';
        article.innerHTML='<span>'+escapeHistory(m.movementLabel)+'</span><div><strong>'+escapeHistory(m.description||m.sourceLabel)+'</strong><small>'+formatHistoryDate(m.occurredAt)+batch+reference+'</small></div><b>'+(Number(m.quantity)>0?'+':'')+Math.round(Number(m.quantity))+' each</b><em>'+(m.currency&&m.unitCost!=null?escapeHistory(m.currency)+' '+Number(m.unitCost).toFixed(2):'Not valued')+'</em>';
        target.append(article);
      }
      if(!target.children.length){feedback.textContent="No movements recorded for "+(mode==="item"?"this item.":"this batch.");target.append(feedback);}
      if(rows.length===100){const more=document.createElement("button");more.type="button";more.className="secondary-button history-load-more";more.dataset.historyMore="";more.textContent="Load more movements";more.onclick=()=>window.loadInventoryMovements(row,mode,page+1);target.append(more);}
    }catch(error){
      if(error.name==="AbortError")return;
      feedback.className="history-error";feedback.textContent="Movements could not be loaded. ";
      const retry=document.createElement("button");retry.type="button";retry.className="secondary-button";retry.textContent="Try again";retry.onclick=()=>{feedback.remove();window.loadInventoryMovements(row,mode,page);};feedback.append(retry);
    }
  };
  scope.addEventListener("click",event=>{const button=event.target.closest("button");if(button&&currentRow)window.loadInventoryMovements(currentRow,button.dataset.historyScope);});
  document.getElementById("inventory-history-dialog").addEventListener("close",()=>request?.abort());
})();
