/* Native modal behavior supplies focus trapping, inert background and Escape.
 * Enhance existing nodes, never clone forms or replace domain event handlers. */
(()=>{'use strict';
 if(window.NextAiDialogs)return;
 let serial=0;
 const enhanced=new WeakSet();
 function dismiss(dialog){
  // Honour domain cancellation guards (including confirmation promises).
  if(dialog.dispatchEvent(new Event('cancel',{cancelable:true})))dialog.close();
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
 }
 function scan(root=document){if(root.matches?.('dialog'))enhance(root);root.querySelectorAll?.('dialog').forEach(enhance);}
 window.NextAiDialogs={enhance:scan,close:dismiss};scan();
 new MutationObserver(records=>{for(const r of records){if(r.type==='attributes')enhance(r.target);else for(const n of r.addedNodes)if(n.nodeType===1)scan(n.closest('dialog')||n);}}).observe(document.body,{childList:true,subtree:true,attributes:true,attributeFilter:['open']});
})();
