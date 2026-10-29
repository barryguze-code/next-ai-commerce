(()=>{
  const amazonFedEx=document.body.dataset.fedexAmazonShipping==='true';
  const sandboxRateOnly=document.body.dataset.fedexRateOnly==='true';
  const sandboxLabelTest=document.body.dataset.fedexLabelTest==='true'||sandboxRateOnly;
  const packingClicksKey='nextai.packing-slip-clicked.v1';
  let packingClicks=[];
  try{const saved=JSON.parse(localStorage.getItem(packingClicksKey)||'[]');if(Array.isArray(saved))packingClicks=saved.filter(id=>typeof id==='string').slice(-1000);}catch(_){}
  function showPackingClicked(button){
    button.dataset.packingClicked='true';
    if(window.NextAiIcons)button.replaceChildren(window.NextAiIcons.create('printClicked'));
    button.title='Packing slip opened — click to reprint';
    button.setAttribute('aria-label',button.title);
    button.closest('.order-action-tip')?.setAttribute('data-tooltip',button.title);
  }
  function addPackingSlipActions(){
    document.querySelectorAll('[data-packing-slip-order]').forEach(shipping=>{
      if(shipping.closest('[data-tooltip]'))shipping.removeAttribute('title');
      if(shipping.parentElement?.querySelector('.order-icon-action.packing-slip'))return;
      const orderId=shipping.dataset.packingSlipOrder,orderRow=shipping.closest('.order-row'),provider=shipping.dataset.provider==='SHOPIFY'?'Shopify':'Seller Central';
      if(!orderId||!orderRow)return;
      const tip=document.createElement('span');tip.className='order-action-tip';tip.dataset.tooltip=sandboxLabelTest?('Buy shipping'):'Open packing slip';
      const button=document.createElement('button');button.type='button';button.className='order-icon-action shipping packing-slip';
      button.setAttribute('aria-label',sandboxLabelTest?('Buy shipping'):'Open packing slip and '+provider+' order');
      button.innerHTML='<span class="action-icon" aria-hidden="true"><svg viewBox="0 0 24 24"><path d="M9 4H5v17h10M15 4h3v7M9 2h5v4H9zM8 10l1.5 1.5L12 9M8 15h3"/><path d="m15 14 3-1.5 3 1.5v5l-3 1.5-3-1.5zM15 14l3 1.5 3-1.5M18 15.5v5"/></svg></span>';
      if(!sandboxLabelTest&&packingClicks.includes(orderId))showPackingClicked(button);
      const openFedEx=()=>{button.dataset.orderId=orderId;button.dataset.orderName=orderRow.querySelector('.order-number')?.textContent?.trim()||orderId;window.openFedExShipping?.(button);};
      const openPacking=()=>{
        packingClicks=[...packingClicks.filter(id=>id!==orderId),orderId].slice(-1000);
        try{localStorage.setItem(packingClicksKey,JSON.stringify(packingClicks));}catch(_){}
        showPackingClicked(button);
        const labelUrl='/app/orders/'+encodeURIComponent(orderId)+'/packing-slip';
        const sellerUrl=orderRow.querySelector('.order-number')?.href;
        const label=window.open('about:blank','_blank');
        if(label)label.opener=null;
        const seller=sellerUrl?window.open('about:blank','_blank'):null;
        if(seller){seller.opener=null;seller.location.href=sellerUrl;seller.focus();}
        if(label)label.location.href=labelUrl+(!seller&&sellerUrl?'?sellerCentralBlocked=true':'');
        if(!label||!seller){
          let notice=document.querySelector('[data-packing-tabs-notice]');
          if(!notice){notice=document.createElement('div');notice.className='stream-toast show';notice.dataset.packingTabsNotice='';notice.setAttribute('role','status');document.body.append(notice);}
          notice.replaceChildren(document.createTextNode('Your browser blocked a tab. '));
          const addLink=(url,text)=>{const link=document.createElement('a');link.href=url;link.target='_blank';link.rel='noopener noreferrer';link.textContent=text;link.style.cssText='color:inherit;text-decoration:underline;margin-left:10px';notice.append(link);};
          if(!label)addLink(labelUrl,'Open packing slip');if(!seller&&sellerUrl)addLink(sellerUrl,'Open '+provider);
          const close=document.createElement('button');close.type='button';close.textContent='×';close.setAttribute('aria-label','Dismiss');close.onclick=()=>notice.remove();notice.append(close);
        }
      };
      button.addEventListener('click',()=>{
        if(provider==='Seller Central'&&amazonFedEx&&window.openOrderChoiceMenu){
          window.openOrderChoiceMenu(button,'Buy shipping',[
            {label:'Amazon Seller Central',description:'Open packing slip and Seller Central',image:'/images/channels/amazon-seller.png',onSelect:openPacking},
            {label:'FedEx Buy Shipping',description:shipping.dataset.fedexEligible==='true'?'Compare rates and buy a label':'Available for eligible unshipped FBM orders',image:'/images/channels/fedex-official.png',disabled:shipping.dataset.fedexEligible!=='true',onSelect:openFedEx}
          ]);return;
        }
        if(provider==='Shopify'&&sandboxLabelTest&&orderRow.querySelector('.amazon-order-status')?.textContent?.trim().toLowerCase()!=='shipped'){openFedEx();return;}
        openPacking();
      });
      tip.append(button);shipping.parentElement.append(tip);
    });
  }
  addPackingSlipActions();
  new MutationObserver(records=>{if(records.some(r=>[...r.addedNodes].some(n=>n.nodeType===1&&(n.matches('.order-row,[data-order-stream]')||n.querySelector('.order-row')))))addPackingSlipActions();}).observe(document.body,{childList:true,subtree:true});
})();
