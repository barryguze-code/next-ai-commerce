(()=>{
let pendingForm=null,pendingResolve=null;
function finish(accepted){const form=pendingForm,resolve=pendingResolve;pendingForm=null;pendingResolve=null;const dialog=document.getElementById('platform-confirm-dialog');if(dialog?.open)dialog.close();if(accepted&&form){form.dataset.confirmed='true';form.requestSubmit()}if(resolve)resolve(accepted)}
function build(){
  if(document.getElementById('platform-confirm-dialog'))return;
  document.body.insertAdjacentHTML('beforeend','<dialog id="platform-confirm-dialog" class="platform-confirm-dialog"><div class="platform-confirm-shell"><div class="platform-confirm-icon" aria-hidden="true">!</div><div><span class="platform-confirm-eyebrow">Please confirm</span><h2 id="platform-confirm-title">Confirm action</h2><p id="platform-confirm-message"></p></div><div class="platform-confirm-actions"><button class="secondary-button" type="button" data-confirm-cancel>Cancel</button><button class="primary-button compact-button" type="button" data-confirm-accept>Continue</button></div></div></dialog>');
  const dialog=document.getElementById('platform-confirm-dialog');
  dialog.querySelector('[data-confirm-cancel]').onclick=()=>finish(false);
  dialog.querySelector('[data-confirm-accept]').onclick=()=>finish(true);
  dialog.addEventListener('cancel',event=>{event.preventDefault();finish(false)});
  dialog.addEventListener('click',event=>{if(event.target===dialog)finish(false)});
}
document.querySelectorAll('form[onsubmit*="confirm("]').forEach(form=>{
  const legacy=form.getAttribute('onsubmit')||'',match=legacy.match(/confirm\(['\"](.+?)['\"]\)/);
  if(match){form.dataset.confirmTitle='Confirm action';form.dataset.confirmMessage=match[1];form.dataset.confirmAccept='Continue';form.removeAttribute('onsubmit')}
});
document.addEventListener('submit',event=>{
  const form=event.target.closest('form[data-confirm-message]');if(!form)return;
  if(form.dataset.confirmed==='true'){delete form.dataset.confirmed;return}
  event.preventDefault();build();pendingForm=form;
  const dialog=document.getElementById('platform-confirm-dialog');
  dialog.querySelector('#platform-confirm-title').textContent=form.dataset.confirmTitle||'Confirm action';
  dialog.querySelector('#platform-confirm-message').textContent=form.dataset.confirmMessage;
  dialog.querySelector('[data-confirm-accept]').textContent=form.dataset.confirmAccept||'Continue';
  dialog.showModal();
},true);
window.NextAiConfirm=({title='Confirm action',message='Continue with this action?',accept='Continue'}={})=>new Promise(resolve=>{
  build();pendingForm=null;pendingResolve=resolve;const dialog=document.getElementById('platform-confirm-dialog');
  dialog.querySelector('#platform-confirm-title').textContent=title;dialog.querySelector('#platform-confirm-message').textContent=message;
  dialog.querySelector('[data-confirm-accept]').textContent=accept;dialog.showModal();
});
})();
