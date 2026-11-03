// All task creation uses the existing contextual conversation dialog and composer.
(()=>{
 function boot(){
  document.querySelectorAll('[data-new-task]').forEach(button=>button.onclick=()=>window.openGeneralTask?.(button));
 }
 document.readyState==='loading'?document.addEventListener('DOMContentLoaded',boot):boot();
})();
