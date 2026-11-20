(()=>{
  let menu;
  function close(){menu?.remove();menu=null;}
  function open(form,x,y){
    close();menu=document.createElement('div');menu.className='catalog-image-sync-menu';menu.setAttribute('role','menu');
    Object.assign(menu.style,{position:'fixed',left:Math.max(8,Math.min(x,innerWidth-290))+'px',top:Math.max(8,Math.min(y,innerHeight-150))+'px',zIndex:'10000',width:'280px',padding:'12px',border:'1px solid var(--line)',borderRadius:'12px',background:'var(--white)',boxShadow:'0 12px 30px #15243a30'});
    const button=document.createElement('button');button.type='button';button.className='primary-button';button.textContent='Sync image from Amazon';button.setAttribute('role','menuitem');
    const status=document.createElement('p');status.setAttribute('role','status');status.style.fontSize='12px';status.textContent='Updates Account and Global Catalogue pictures.';
    button.onclick=async()=>{button.disabled=true;status.textContent='Getting Amazon’s main image…';
      try{const response=await fetch(form.action+'/amazon',{method:'POST',body:new FormData(form)});const result=await response.json();if(!response.ok)throw new Error(result.error||'Image sync failed.');
        status.textContent=result.message;const image=form.querySelector('img')||document.createElement('img');image.src=result.imageUrl+'?v='+Date.now();image.hidden=false;image.alt='Product image';
        const avatar=form.querySelector('.product-avatar');if(!image.parentElement)avatar.prepend(image);const fallback=avatar.querySelector('b');if(fallback)fallback.hidden=true;
      }catch(error){status.textContent=error.message;}finally{button.disabled=false;}
    };
    menu.append(button,status);document.body.append(menu);button.focus();
  }
  document.addEventListener('contextmenu',event=>{const form=event.target.closest('.product-image-upload');if(!form)return;event.preventDefault();open(form,event.clientX,event.clientY);});
  document.addEventListener('keydown',event=>{if(event.key==='Escape')close();});
  document.addEventListener('pointerdown',event=>{if(menu&&!menu.contains(event.target))close();});
})();
