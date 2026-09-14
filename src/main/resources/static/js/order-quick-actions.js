(()=>{
  let timer;
  function notify(message){
    let notice=document.querySelector('[data-order-action-notice]');
    if(!notice){notice=document.createElement('div');notice.className='stream-toast';notice.dataset.orderActionNotice='';notice.setAttribute('role','status');document.body.append(notice);}
    notice.textContent=message;notice.classList.add('show');clearTimeout(timer);timer=setTimeout(()=>notice.classList.remove('show'),3500);
  }
  document.addEventListener('click',async event=>{
    const button=event.target.closest('[data-copy-sku]');if(!button)return;
    try{await navigator.clipboard.writeText(button.dataset.copySku);notify('SKU copied');}
    catch(_){notify('Could not copy SKU. Allow clipboard access and try again.');}
  });
})();
