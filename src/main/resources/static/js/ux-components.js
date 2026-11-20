/* Native modal behavior supplies focus trapping, inert background and Escape.
 * Enhance existing nodes, never clone forms or replace domain event handlers. */
(()=>{'use strict';
 if(window.NextAiDialogs)return;
 let serial=0;
 const enhanced=new WeakSet(), dragCleanups=new Map();
 function dismiss(dialog){
  // Honour domain cancellation guards (including confirmation promises).
  if(dialog.dispatchEvent(new Event('cancel',{cancelable:true})))dialog.close();
 }
function installDialogDrag(dialog,handle){
if(dialog.matches('[data-dialog-drag=off],.drawer,.slide-panel,.inventory-drawer')||/drawer|slide|fullscreen/.test(dialog.className))return;
dialog.classList.add('ux-dialog-draggable');
const originalTranslate=dialog.style.translate;
const mobile=matchMedia('(max-width: 760px)');
let x=0,y=0,paintedX=0,paintedY=0,drag=null,frame=0;
handle.tabIndex=0;handle.setAttribute('aria-label','Move dialog');
function paint(){frame=0;paintedX=x;paintedY=y;dialog.style.translate=`${x}px ${y}px`;}
function clamp(){const rect=dialog.getBoundingClientRect(),left=rect.left-paintedX,top=rect.top-paintedY;
 x=Math.max(12-left,Math.min(x,innerWidth-12-left-rect.width));
 y=Math.max(12-top,Math.min(y,innerHeight-12-top-rect.height));
 if(rect.width>innerWidth-24)x=-left+12;
 if(rect.height>innerHeight-24)y=-top+12;
}
function schedule(){if(!frame)frame=requestAnimationFrame(paint);}
function finish(cancel=false){const previous=drag;drag=null;if(cancel&&previous){x=previous.x;y=previous.y;schedule();}
 dialog.classList.remove('is-dragging');if(previous&&handle.hasPointerCapture(previous.id))handle.releasePointerCapture(previous.id);
}
function reset(){finish();x=0;y=0;paintedX=0;paintedY=0;if(frame)cancelAnimationFrame(frame);frame=0;if(originalTranslate)dialog.style.translate=originalTranslate;else dialog.style.removeProperty('translate');}
handle.addEventListener('pointerdown',event=>{
 if(!dialog.open||mobile.matches||event.button!==0||event.target.closest('button,a,input,select,textarea,label,summary,[contenteditable],[role="button"]'))return;
 drag={id:event.pointerId,startX:event.clientX,startY:event.clientY,x,y,moved:false};handle.setPointerCapture(event.pointerId);
});
handle.addEventListener('pointermove',event=>{if(!drag||drag.id!==event.pointerId)return;
 const dx=event.clientX-drag.startX,dy=event.clientY-drag.startY;
 if(!drag.moved&&Math.hypot(dx,dy)<4)return;
 drag.moved=true;dialog.classList.add('is-dragging');x=drag.x+dx;y=drag.y+dy;clamp();schedule();event.preventDefault();
});
handle.addEventListener('pointerup',()=>finish());
handle.addEventListener('pointercancel',()=>finish(true));
handle.addEventListener('lostpointercapture',()=>finish());
const onBlur=()=>finish(true);window.addEventListener('blur',onBlur);
dialog.addEventListener('cancel',event=>{if(drag){event.preventDefault();finish(true);}});
dialog.addEventListener('close',reset);
handle.addEventListener('keydown',event=>{if(event.target!==handle||mobile.matches||!event.altKey)return;
 const move={ArrowLeft:[-20,0],ArrowRight:[20,0],ArrowUp:[0,-20],ArrowDown:[0,20]}[event.key];if(!move)return;
 event.preventDefault();x+=move[0];y+=move[1];clamp();schedule();
});
const onResize=()=>{finish();if(mobile.matches)reset();else if(dialog.open&&(x||y)){clamp();schedule();}};
window.addEventListener('resize',onResize);
const openObserver=new MutationObserver(()=>{if(!dialog.open)reset();});openObserver.observe(dialog,{attributes:true,attributeFilter:['open']});
dragCleanups.set(dialog,()=>{reset();openObserver.disconnect();window.removeEventListener('blur',onBlur);window.removeEventListener('resize',onResize);});
}

 function enhance(dialog){
  if(!dialog.matches('dialog')||enhanced.has(dialog))return;
  let title=dialog.querySelector('h1,h2,h3');if(!title)return;
  enhanced.add(dialog);dialog.classList.add('ux-dialog');
  let shell=dialog.children.length===1&&dialog.firstElementChild.matches('form,section,div')?dialog.firstElementChild:dialog;
  if(shell===dialog){shell=document.createElement('div');while(dialog.firstChild)shell.append(dialog.firstChild);dialog.append(shell);}
  shell.classList.add('ux-dialog-shell');
  let header=[...shell.children].find(n=>n.matches('header,.dialog-header,[class$="-header"]'));
  if(!header){header=document.createElement('header');title.before(header);header.append(title);shell.prepend(header);}
  else if(!header.contains(title))header.prepend(title);
  header.classList.add('ux-dialog-header');
  if(!title.id)title.id='platform-dialog-title-'+(++serial);
  if(!dialog.hasAttribute('aria-labelledby'))dialog.setAttribute('aria-labelledby',title.id);
  let close=header.querySelector('button[aria-label*="Close"],button[class*="close"],button[data-close]');
  if(!close){close=document.createElement('button');close.type='button';close.textContent='×';close.onclick=()=>dismiss(dialog);header.append(close);}
  close.classList.add('ux-dialog-close');if(!close.getAttribute('aria-label'))close.setAttribute('aria-label','Close '+title.textContent.trim());
  let footer=[...shell.children].find(n=>n.matches('footer,.dialog-actions,[class$="-footer"],.platform-confirm-actions'));
  if(!footer){footer=document.createElement('footer');const done=document.createElement('button');done.type='button';done.className='secondary-button';done.textContent='Done';done.onclick=()=>dismiss(dialog);footer.append(done);shell.append(footer);}
  footer.classList.add('ux-dialog-footer');
  const content=[...shell.children].filter(n=>n!==header&&n!==footer&&!n.matches('input[type=hidden],script,style'));
  let body=content.length===1?content[0]:null;
  if(!body){body=document.createElement('div');header.after(body);content.forEach(n=>body.append(n));}
  body.classList.add('ux-dialog-body');
  installDialogDrag(dialog,header);
 }
 function scan(root=document){if(root.matches?.('dialog'))enhance(root);root.querySelectorAll?.('dialog').forEach(enhance);}
 window.NextAiDialogs={enhance:scan,close:dismiss};scan();
 new MutationObserver(records=>{for(const [dialog,cleanup] of dragCleanups){if(!dialog.isConnected){cleanup();dragCleanups.delete(dialog);}}for(const r of records){if(r.type==='attributes')enhance(r.target);else for(const n of r.addedNodes)if(n.nodeType===1)scan(n.closest('dialog')||n);}}).observe(document.body,{childList:true,subtree:true,attributes:true,attributeFilter:['open']});
})();
