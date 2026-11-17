/* User-authored, exact product-quantity matches. Never fulfills or changes stock. */
(()=>{'use strict';
 const dialog=document.getElementById('profit-dialog'),section=dialog?.querySelector('[data-packaging-match]');if(!section)return;
 const field=s=>section.querySelector(s),editable=dialog.dataset.canEdit==='true',base='/app/inventory/profit';
 const drafts=new Map(),contextKey=c=>dialog.dataset.account+':'+(c.connection||dialog.dataset.store)+':'+c.view.kind+':'+c.view.key;
 let context,match,choices=[],sequence=0,loading=false,saving=false,dirty=false,loadedUnits=1;
 const el=(tag,text)=>{const n=document.createElement(tag);if(text!=null)n.textContent=text;return n};
 const status=text=>field('[data-match-status]').textContent=text;
 const controls=()=>{field('[data-match-save]').disabled=!editable||loading||saving||!match;field('[data-match-reload]').disabled=loading||saving;field('[data-match-add]').disabled=!editable||loading||saving||!match;field('[data-match-units]').disabled=loading||saving;section.querySelectorAll('[data-match-packages] select,[data-match-packages] button').forEach(n=>{n.disabled=!editable||loading||saving;if(n.tagName==='SELECT')n.dispatchEvent(new Event('platform-choice-sync'));});};
 async function json(url,options){const response=await fetch(url,options),data=await response.json();if(!response.ok)throw Error(data.message||data.detail||'Packaging match could not be loaded.');return data;}
 function changed(){dirty=true;status('Unsaved packaging match.');}
 function add(name=''){
  const row=el('div');row.className='profit-package';const label=el('label','Shipping package'),select=el('select');select.setAttribute('aria-label','Match shipping package');select.dataset.standardChoice='';select.append(new Option('Select account package',''));for(const choice of choices)select.append(new Option(choice.name,choice.name));select.value=name;
  if(name&&!choices.some(c=>c.name===name)){const unavailable=new Option(name+' · no longer available',name);unavailable.disabled=true;select.append(unavailable);select.value=name;label.append(el('small','Choose an available account package.'));}
  select.onchange=changed;label.append(select);const remove=el('button','×');remove.type='button';remove.setAttribute('aria-label','Remove match package');remove.onclick=()=>{row.remove();changed();};row.append(label,remove);field('[data-match-packages]').append(row);window.NextAiPlatformControls?.enhance(row);controls();
 }
 async function load(){
  const current=context,request=++sequence;match=null;loading=true;field('[data-match-contents]').textContent='';status('Loading packaging match…');controls();
  const units=Number(field('[data-match-units]').value);if(!Number.isInteger(units)||units<1||units>10000){loading=false;status('Enter 1–10000 whole SKU units.');controls();return;}
  const query=new URLSearchParams({kind:current.view.kind,key:current.view.key,units});if(current.connection)query.set('connection',current.connection);
  try{
   const [saved,available]=await Promise.all([json(base+'/packaging-match?'+query),json(base+'/package-choices?date='+encodeURIComponent(current.view.date))]);
   if(request!==sequence||context!==current)return;match=saved;loadedUnits=units;choices=available;dirty=false;field('[data-match-contents]').textContent=saved.contents.map(c=>c.eaches+' each · '+c.name).join(' + ');
   field('[data-match-packages]').replaceChildren();const draft=drafts.get(contextKey(current));if(draft&&draft.units===units){draft.packages.forEach(add);dirty=true;status(draft.compositionKey===saved.compositionKey?'Unsaved packaging match restored.': 'Product mapping changed. Review these retained package selections before saving.');}else{saved.packages.forEach(add);if(!saved.packages.length)add();status(saved.message);}
  }catch(error){if(request===sequence){field('[data-match-contents]').textContent='';status(error.message);}}
  finally{if(request===sequence){loading=false;controls();}}
 }
 document.addEventListener('profit:view',event=>{
  sequence++;context=event.detail;match=null;dirty=false;saving=false;section.hidden=!context||context.view.fba;field('[data-match-packages]').replaceChildren();field('[data-match-contents]').textContent='';if(section.hidden)return;
  const listing=['SKU','SHOPIFY_SKU'].includes(context.view.kind);field('[data-match-units-label]').hidden=!listing;field('[data-match-units]').value=String(drafts.get(contextKey(context))?.units||1);load();
 });
 field('[data-match-units]').addEventListener('change',()=>{if(dirty){field('[data-match-units]').value=String(loadedUnits);status('Save the current packaging match or use Reload saved match before changing SKU units.');return;}load();});
 field('[data-match-reload]').onclick=()=>{drafts.delete(contextKey(context));dirty=false;load();};
 field('[data-match-add]').onclick=()=>{if(field('[data-match-packages]').children.length>=20){status('Use up to 20 packages.');return;}add();changed();};
 field('[data-match-save]').onclick=async()=>{
  if(!match||saving)return;const current=context,revision=sequence,snapshot=match,units=Number(field('[data-match-units]').value);
  const packages=[...field('[data-match-packages]').querySelectorAll('select')].map(n=>n.value);
  if(!packages.length||packages.some(name=>!choices.some(c=>c.name===name))){status('Choose an account package for every row.');return;}
  saving=true;controls();status('Saving packaging match…');
  try{
   const token=dialog.querySelector('[data-profit-csrf]');const saved=await json(base+'/packaging-match',{method:'POST',headers:{'Content-Type':'application/json',...(token?{[token.dataset.header]:token.value}:{})},body:JSON.stringify({kind:current.view.kind,key:current.view.key,connection:current.connection,units,compositionKey:snapshot.compositionKey,revision:snapshot.revision,packages})});
   if(context!==current||revision!==sequence)return;match=saved;dirty=false;drafts.delete(contextKey(current));status('Saved packaging match. Other listings and new order estimates use this exact combination.');document.dispatchEvent(new CustomEvent('packaging-match:updated'));
  }catch(error){if(context===current&&revision===sequence)status(error.message+' Your selections are kept.');}
  finally{if(context===current&&revision===sequence){saving=false;controls();}}
 };
 dialog.addEventListener('close',()=>{if(dirty&&context&&match)drafts.set(contextKey(context),{units:loadedUnits,compositionKey:match.compositionKey,packages:[...field('[data-match-packages]').querySelectorAll('select')].map(n=>n.value)});sequence++;context=null;match=null;});
})();
