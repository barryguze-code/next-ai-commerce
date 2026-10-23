/* One in-flow disclosure over the existing permission-rendered shell. No route copies. */
(()=>{
 'use strict';
 function enhance(){
  const sidebar=document.querySelector('.sidebar');
  if(!sidebar||sidebar.dataset.mobileNavigationReady)return;
  sidebar.dataset.mobileNavigationReady='true';
  const brand=sidebar.querySelector('.brand'),region=document.createElement('div');
  region.id='platform-navigation-region';region.className='platform-navigation-region';
  [...sidebar.childNodes].filter(node=>node!==brand).forEach(node=>region.append(node));
  const trigger=document.createElement('button');trigger.type='button';trigger.className='mobile-navigation-trigger';
  trigger.textContent='Menu';trigger.setAttribute('aria-controls',region.id);trigger.setAttribute('aria-expanded','false');
  const context=document.querySelector('.workspace .context-menu'),slot=document.createElement('div');
  slot.className='mobile-navigation-context';
  const marker=document.createComment('Workspace context desktop position');
  if(context)context.before(marker);
  sidebar.append(trigger,slot,region);
  sidebar.querySelectorAll('.product-nav a.active').forEach(link=>link.setAttribute('aria-current','page'));
  const mobile=matchMedia('(max-width:760px)');let open=false,lastFocused=document.activeElement;
  document.addEventListener('focusin',event=>{lastFocused=event.target});
  function close(restore=false){open=false;trigger.setAttribute('aria-expanded','false');region.hidden=mobile.matches;if(restore)trigger.focus();}
  function sync(){
   const focused=document.activeElement===document.body?lastFocused:document.activeElement;
   if(mobile.matches){
    if(context)slot.append(context);
    close(region.contains(focused)||focused===brand);
   }else{
    region.hidden=false;
    if(context)marker.after(context);
    if(focused===trigger)(sidebar.querySelector('.product-nav a.active')||sidebar.querySelector('.product-nav a'))?.focus();
    open=false;trigger.setAttribute('aria-expanded','false');
   }
  }
  trigger.addEventListener('click',()=>{open=!open;region.hidden=!open;trigger.setAttribute('aria-expanded',String(open));});
  sidebar.addEventListener('keydown',event=>{
   // Let nested disclosures/pickers dismiss first when they handle Escape.
   if(event.key==='Escape'&&!event.defaultPrevented&&mobile.matches&&open){
    if(event.target.closest('dialog[open],.text-size-popover,.sidebar-huddle-menu,.context-menu[open]'))return;
    event.preventDefault();close(true);
   }
  });
  region.addEventListener('click',event=>{if(mobile.matches&&event.target.closest('a[href]'))close();});
  mobile.addEventListener('change',sync);sync();
 }
 if(document.readyState==='loading')document.addEventListener('DOMContentLoaded',enhance);else enhance();
})();
