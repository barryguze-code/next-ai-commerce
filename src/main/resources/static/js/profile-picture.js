(()=>{
 'use strict';
 const trigger=document.querySelector('.profile-picture-trigger');if(!trigger)return;
 const portrait=trigger.querySelector('img');portrait.onerror=()=>portrait.hidden=true;portrait.onload=()=>portrait.hidden=false;
 if(portrait.complete&&!portrait.naturalWidth)portrait.hidden=true;
 window.addEventListener('user-profile-picture-updated',()=>portrait.src='/app/profile/picture?v='+Date.now());
 trigger.onclick=()=>{
  const dialog=document.createElement('dialog');dialog.className='profile-picture-dialog';
  dialog.innerHTML='<form><span class="eyebrow dark">Your profile</span><h2>Update profile picture</h2><input type="file" accept="image/png,image/jpeg" required aria-label="Profile picture"><p>JPG or PNG up to 5 MB. Your picture follows you across accounts.</p><p role="status"></p><footer><button type="button" class="secondary-button">Cancel</button><button type="submit" class="primary-button">Save picture</button></footer></form>';
  dialog.querySelector('[type=button]').onclick=()=>dialog.close();dialog.onclose=()=>{dialog.remove();trigger.focus()};
  dialog.querySelector('form').onsubmit=async event=>{
   event.preventDefault();const file=dialog.querySelector('[type=file]').files[0],status=dialog.querySelector('[role=status]'),save=dialog.querySelector('[type=submit]');
   if(!file||file.size>5000000||!['image/png','image/jpeg'].includes(file.type)){status.textContent='Choose a JPG or PNG up to 5 MB.';return}
   const data=new FormData();data.append('image',file);const csrf=document.querySelector('input[name=_csrf]');if(csrf)data.append('_csrf',csrf.value);
   save.disabled=true;status.textContent='Uploading…';
   try{const response=await fetch('/app/profile/picture',{method:'POST',body:data,headers:{Accept:'application/json'}});if(!response.ok)throw Error('Picture could not be saved. Please try again.');portrait.src='/app/profile/picture?v='+Date.now();dialog.close();}
   catch(error){status.textContent=error.message;save.disabled=false}
  };
  document.body.append(dialog);dialog.showModal();
 };
})();
