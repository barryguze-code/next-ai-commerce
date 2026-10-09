/* Only acknowledge visible team messages; GET requests never mark anything read. */
(()=>{'use strict';
 if(window.NextAiReadReceipts)return;window.NextAiReadReceipts=true;
 let context=null,timer,observer;
 function stop(){clearTimeout(timer);observer?.disconnect();context=null;}
 function eligible(c){return context===c&&document.visibilityState==='visible'&&document.hasFocus()&&c.dialog.open&&c.dialog.dataset.visibility==='TEAM_CHAT';}
 function visible(node,root){const a=node.getBoundingClientRect(),b=root.getBoundingClientRect();return Math.min(a.bottom,b.bottom,innerHeight)-Math.max(a.top,b.top,0)>=Math.min(40,a.height/2)&&a.width>0;}
 function schedule(){clearTimeout(timer);timer=setTimeout(flush,600);}
 function updateIcon(c){if(context!==c||!c.icon)return;c.icon.src=c.unread.size?window.NextAiIcons.source('collaboration-assigned-red'):c.originalIcon;c.icon.alt=c.unread.size?'Unread mention for you':c.originalAlt;}
 async function flush(){
  const c=context;if(!c||c.busy||!eligible(c))return;
  const ids=c.nodes.filter(node=>!c.read.has(node.dataset.readMessageId)&&visible(node,c.list)).map(node=>node.dataset.readMessageId).slice(0,250);
  if(!ids.length)return;
  const body=new FormData();c.dialog.querySelectorAll('input[type=hidden]').forEach(input=>{if(input.name.includes('csrf'))body.set(input.name,input.value);});ids.forEach(id=>body.append('messageId',id));
  c.busy=true;let saved=false;
  try{const response=await fetch('/app/collaboration/reviews/'+encodeURIComponent(c.reviewId)+'/read',{method:'POST',body,headers:{Accept:'application/json'}});if(!response.ok)throw new Error('Read receipt not saved');saved=true;ids.forEach(id=>{c.read.add(id);c.unread.delete(id);});updateIcon(c);document.dispatchEvent(new Event('collaboration-messages-read'));}
  catch(_){/* Leave unread on failure; retry after the next visibility/scroll event. */}
  finally{c.busy=false;if(saved&&eligible(c)&&ids.length===250)schedule();}
 }
 document.addEventListener('collaboration-thread-loading',stop);
 document.addEventListener('collaboration-thread-rendered',event=>{
  stop();if(event.detail.messageType!=='TEAM_CHAT')return;
  const dialog=document.querySelector('#contextual-thread-dialog'),list=dialog.querySelector('#thread-conversation-list');
  const nodes=[...list.querySelectorAll('.conversation-messages article')];
  nodes.forEach((node,index)=>node.dataset.readMessageId=event.detail.messageIds[index]);
  const icon=dialog.querySelector('#thread-status-icon');
  context={dialog,list,nodes:nodes.filter(node=>node.dataset.readMessageId),reviewId:event.detail.reviewId,read:new Set(),unread:new Set(event.detail.unreadMentionMessageIds||[]),icon,originalIcon:icon?.src,originalAlt:icon?.alt,busy:false};updateIcon(context);
  observer=new IntersectionObserver(schedule,{root:list,threshold:[0,.5,1]});nodes.forEach(node=>observer.observe(node));schedule();
 });
 document.addEventListener('visibilitychange',schedule);window.addEventListener('focus',schedule);window.addEventListener('online',schedule);
 document.addEventListener('scroll',schedule,true);document.addEventListener('close',event=>{if(event.target.id==='contextual-thread-dialog')stop();},true);
})();
