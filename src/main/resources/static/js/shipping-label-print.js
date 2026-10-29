/* Print only an already archived, authorized label. This path never purchases. */
(()=>{'use strict';
 window.prepareShippingLabelPrint=async(preview,path)=>{
  if(!preview||preview.closed)return;
  preview.opener=null;const doc=preview.document;doc.title='Shipping label';
  const status=doc.createElement('p');status.textContent='Loading shipping label…';doc.body.replaceChildren(status);
  const url=new URL(path,window.location.origin);
  if(url.origin!==window.location.origin||!/^\/app\/orders\/[^/]+\/buy-shipping\/fedex\/labels\/[0-9a-f-]{36}$/.test(url.pathname))throw Error('Invalid archived label address.');
  try{
   const response=await fetch(url.href,{headers:{Accept:'application/pdf'},cache:'no-store'});
   if(!response.ok||!response.headers.get('content-type')?.includes('application/pdf'))throw Error('The saved label could not be loaded. Return to Orders and reprint it.');
   const bytes=await response.blob();if(preview.closed)return;const blob=URL.createObjectURL(bytes),frame=doc.createElement('iframe');frame.title='Shipping label';
   const style=doc.createElement('style');style.textContent='body{margin:0;font:14px system-ui}header{display:flex;align-items:center;gap:16px;padding:12px}iframe{display:block;width:100%;height:calc(100vh - 65px);border:0}@media print{header{display:none}iframe{height:100vh}}';doc.head.append(style);
   const header=doc.createElement('header'),print=doc.createElement('button'),open=doc.createElement('a');print.type='button';print.textContent='Print label';open.textContent='Open saved PDF';open.href=url.href;open.target='_blank';open.rel='noopener';
   status.textContent='Label ready';status.setAttribute('role','status');header.append(print,open,status);doc.body.replaceChildren(header,frame);
   let loaded=false,automatic=false,timer;
   const printLabel=()=>{clearTimeout(timer);if(!loaded)return;try{preview.focus();frame.contentWindow.focus();frame.contentWindow.print();}catch(_){status.textContent='Use Open saved PDF and the browser’s Print control.';}};
   print.onclick=printLabel;frame.addEventListener('load',()=>{loaded=true;if(!automatic){automatic=true;timer=setTimeout(printLabel,500);}},{once:true});
   // A local Blob keeps the protected PDF response's anti-framing headers intact.
   // Its bytes came through the existing tenant/store/order-authorized label endpoint.
   preview.addEventListener('pagehide',()=>{clearTimeout(timer);URL.revokeObjectURL(blob);},{once:true});frame.src=blob;
  }catch(error){status.textContent=error.message;throw error;}
 };
})();
