(() => {
  const notice=document.querySelector('[data-release-notice]');
  const workspace=document.querySelector('main.workspace');
  const header=workspace?.querySelector('.workspace-header');
  if(!notice || !header || notice.dataset.initialized) return;
  const version=notice.dataset.noticeVersion;
  const expires=Date.parse(notice.dataset.validUntil);
  if(!/^[A-Za-z0-9._-]{1,80}$/.test(version||'') || !Number.isFinite(expires) || expires<=Date.now()) { notice.remove(); return; }
  notice.dataset.initialized='true';
  header.after(notice);
  const key='nextai.release-notice.'+version;
  const summary=document.createElement('button');
  summary.type='button';
  summary.className='release-notice-summary';
  summary.setAttribute('aria-controls','release-notice-details');
  summary.setAttribute('aria-expanded','false');
  const label=document.createElement('strong');
  label.textContent='Update planned · Timing to be confirmed';
  const action=document.createElement('span'); action.textContent='View details';
  summary.append(label,action);
  const heading=header.querySelector('h1');
  (heading?.parentElement || header).append(summary);
  let acknowledged=false;
  try { acknowledged=sessionStorage.getItem(key)==='acknowledged'; } catch(unavailable) { /* Memory fallback. */ }
  function display(expanded,moveFocus=false) {
    notice.hidden=!expanded;
    summary.hidden=expanded;
    summary.setAttribute('aria-expanded',String(expanded));
    if(moveFocus) (expanded?notice.querySelector('h2'):summary).focus();
  }
  notice.querySelector('[data-notice-ack]').addEventListener('click',()=>{
    acknowledged=true;
    try { sessionStorage.setItem(key,'acknowledged'); } catch(unavailable) { /* Current page still collapses. */ }
    display(false,true);
  });
  summary.addEventListener('click',()=>display(true,true));
  display(!acknowledged);
  function withdrawWhenExpired() {
    if(Date.now()<expires) { setTimeout(withdrawWhenExpired,Math.min(expires-Date.now(),60000)); return; }
    const hadFocus=notice.contains(document.activeElement)||summary.contains(document.activeElement);
    notice.remove();summary.remove();
    if(hadFocus && heading) { const old=heading.getAttribute('tabindex');heading.tabIndex=-1;heading.focus();if(old===null)heading.removeAttribute('tabindex');else heading.setAttribute('tabindex',old); }
  }
  document.addEventListener('visibilitychange',withdrawWhenExpired);
  setTimeout(withdrawWhenExpired,Math.min(expires-Date.now(),60000));
})();
