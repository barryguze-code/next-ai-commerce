(()=>{
const dialog=document.getElementById('session-dialog'),handle=dialog?.querySelector('.dialog-header');
if(!handle)return;
const mobile=matchMedia('(max-width: 760px)');
let x=0,y=0,paintedX=0,paintedY=0,drag=null,frame=0;
handle.tabIndex=0;handle.setAttribute('aria-label','Move upload dialog');
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
function reset(){finish();x=0;y=0;paintedX=0;paintedY=0;if(frame)cancelAnimationFrame(frame);frame=0;dialog.style.removeProperty('translate');}
handle.addEventListener('pointerdown',event=>{
 if(!dialog.open||mobile.matches||event.button!==0||event.target.closest('button,a,input,select,textarea,[role="button"]'))return;
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
window.addEventListener('blur',()=>finish(true));
dialog.addEventListener('cancel',event=>{if(drag){event.preventDefault();finish(true);}});
dialog.addEventListener('close',reset);
handle.addEventListener('keydown',event=>{if(event.target!==handle||mobile.matches||!event.altKey)return;
 const move={ArrowLeft:[-20,0],ArrowRight:[20,0],ArrowUp:[0,-20],ArrowDown:[0,20]}[event.key];if(!move)return;
 event.preventDefault();x+=move[0];y+=move[1];clamp();schedule();
});
window.addEventListener('resize',()=>{finish();if(mobile.matches)reset();else if(dialog.open){clamp();schedule();}});
new MutationObserver(()=>{if(!dialog.open)reset();}).observe(dialog,{attributes:true,attributeFilter:['open']});
})();
