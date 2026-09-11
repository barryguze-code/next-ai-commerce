(()=>{
  const types={
    physicalCount:{query:'physicalCountJob',storage:'nextai.physicalCountJob',endpoint:id=>'/app/inventory/physical-counts/'+id+'/progress',running:'Applying physical count',preparing:'Preparing uploaded file',defaultPhase:'Preparing your count',complete:'Physical count complete',summary:j=>(j.appliedRows||j.totalRows||0).toLocaleString()+' count rows applied',summaryNote:'Inventory positions and order reservations were refreshed.',primaryLabel:'View inventory',primaryUrl:'/app/inventory',failureLabel:'Review count',failureUrl:id=>'/app/inventory/physical-counts/'+id},
    catalogImport:{query:'catalogImportJob',storage:'nextai.catalogImportJob',endpoint:id=>'/app/catalog/imports/'+id+'/progress',running:'Building account catalogue',preparing:'Preparing catalogue file',defaultPhase:'Preparing catalogue rows',complete:'Catalogue setup complete',summary:j=>(j.totalRows||0).toLocaleString()+' products imported',summaryNote:'Products were added and marketplace SKU mapping was refreshed.',primaryLabel:'View catalogue',primaryUrl:'/app/catalog',failureLabel:'Review import',failureUrl:id=>'/app/catalog/imports/'+id},
    orderSync:{query:'orderSyncJob',storage:'nextai.orderSyncJob',endpoint:id=>'/app/orders/sync/'+id+'/progress',running:'Checking Amazon orders',preparing:'Selected Amazon store',defaultPhase:'Preparing the order check',complete:'Amazon orders refreshed',summary:j=>(j.changedRows||0).toLocaleString()+' order item rows refreshed',summaryNote:'Amazon changes were imported, then eligible Pending and Unshipped merchant orders were reconciled with local inventory.',primaryLabel:'View orders',primaryUrl:'/app/orders',failureLabel:'Return to orders',failureUrl:()=>'/app/orders'}
  };
  const query=new URLSearchParams(location.search),jobs=[];
  Object.entries(types).forEach(([type,config])=>{const fromUrl=query.get(config.query);if(fromUrl){localStorage.setItem(config.storage,fromUrl);query.delete(config.query)}const id=fromUrl||localStorage.getItem(config.storage);if(id)jobs.push({type,id,config})});
  if(jobs.length){const clean=location.pathname+(query.size?'?'+query:'')+location.hash;history.replaceState(null,'',clean)}
  const host=document.createElement('div');host.className='background-job-stack';if(jobs.length)document.body.appendChild(host);jobs.forEach(startJob);
  // Client tasks share the same tray as durable server jobs, without inventing percentages.
  window.NextAiBackgroundJobs={start(title,message){
    if(!host.isConnected)document.body.append(host);
    const card=document.createElement('aside');card.className='background-job-card local-job running';
    card.setAttribute('role','status');card.setAttribute('aria-live','polite');

    const head=document.createElement('div');head.className='background-job-head';
    const icon=document.createElement('span');icon.className='background-job-icon';icon.setAttribute('aria-hidden','true');
    icon.innerHTML='<svg viewBox="0 0 24 24"><path d="M12 3v12m-4-4 4 4 4-4M4 15v5h16v-5"/></svg>';
    const identity=document.createElement('div');identity.className='background-job-title';
    const heading=document.createElement('strong');heading.textContent=title.replaceAll('-',' ');
    const status=document.createElement('span');status.textContent='In progress';identity.append(heading,status);
    const close=document.createElement('button');close.type='button';close.className='background-job-close';close.textContent='×';close.setAttribute('aria-label','Dismiss progress');
    close.onclick=()=>card.remove();head.append(icon,identity,close);
    const phase=document.createElement('p');phase.className='background-job-phase';phase.textContent=message;
    const track=document.createElement('div');track.className='background-job-progress indeterminate';track.setAttribute('role','progressbar');track.setAttribute('aria-label',title);
    track.innerHTML='<i></i>';
    const note=document.createElement('p');note.className='background-job-note';note.textContent='We’ll let you know when it’s ready.';
    const body=document.createElement('div');body.className='background-job-body';body.append(phase,track,note);card.append(head,body);host.append(card);
    const finish=(state,text)=>{
      card.className='background-job-card local-job '+state;phase.textContent=text;status.textContent=state==='completed'?'Ready':'Needs attention';track.remove();note.remove();
      icon.innerHTML=state==='completed'?'<svg viewBox="0 0 24 24"><path d="m5 12 4 4L19 6"/></svg>':'<svg viewBox="0 0 24 24"><path d="M12 5v8m0 4v1"/></svg>';
    };
    return {update(text){phase.textContent=text},complete(text){finish('completed',text)},fail(text){finish('failed',text)}};
  }};
  document.addEventListener('submit',event=>{
    const form=event.target;if(event.defaultPrevented||!form.matches('form[enctype="multipart/form-data"]')||!form.querySelector('input[type=file]')?.files.length)return;
    const path=new URL(form.action,location.href).pathname;
    if(!/receiving|physical-count|catalog\/imports/.test(path))return;
    const title=path.includes('receiving')?'Uploading receiving documents':path.includes('physical-count')?'Uploading physical count':'Uploading catalogue';
    window.NextAiBackgroundJobs.start(title,'Uploading and validating your file. Keep this page open; it will continue when validation finishes.');
  });
  const initialization=document.querySelector('.background-sync-notice');
  if(initialization){
    const progress=window.NextAiBackgroundJobs.start('Marketplace initialization',initialization.textContent.trim());
    initialization.hidden=true;
  }
  function startJob({type,id,config}){
    const card=document.createElement('aside');card.className='background-job-card running';card.dataset.jobType=type;card.setAttribute('role','status');card.setAttribute('aria-live','polite');
    card.innerHTML='<div class="background-job-head"><span class="background-job-icon"><svg viewBox="0 0 24 24"><path d="M12 3v4m0 10v4M3 12h4m10 0h4M5.6 5.6l2.8 2.8m7.2 7.2 2.8 2.8m0-12.8-2.8 2.8m-7.2 7.2-2.8 2.8"/></svg></span><div class="background-job-title"><strong></strong><span data-job-file></span></div><button class="background-job-close" type="button" aria-label="Dismiss">×</button></div><div class="background-job-body"><p class="background-job-phase" data-job-phase></p><div class="background-job-progress"><i data-job-bar></i><span data-job-percent>3%</span></div><p class="background-job-note">Processing safely in the background. You can continue working.</p><div class="background-job-summary"><div><strong data-job-summary></strong><span data-job-summary-note></span></div><b>✓</b></div><div class="background-job-shortage"><b>!</b><span data-job-shortage></span></div><div class="background-job-actions"><a class="job-secondary" data-job-review hidden></a><a class="job-primary" data-job-primary></a></div></div>';
    card.querySelector('.background-job-title strong').textContent=config.running;card.querySelector('[data-job-file]').textContent=config.preparing;card.querySelector('[data-job-phase]').textContent=config.defaultPhase;card.querySelector('[data-job-summary-note]').textContent=config.summaryNote;card.querySelector('[data-job-primary]').textContent=config.primaryLabel;card.querySelector('[data-job-primary]').href=config.primaryUrl;
    host.appendChild(card);const clear=()=>{localStorage.removeItem(config.storage);card.remove();if(!host.children.length)host.remove()};card.querySelector('.background-job-close').addEventListener('click',clear);card.querySelector('[data-job-primary]').addEventListener('click',()=>localStorage.removeItem(config.storage));let stopped=false;const review=card.querySelector('[data-job-review]');
    const showReview=(label,url)=>{review.hidden=false;review.textContent=label;review.href=url;review.addEventListener('click',()=>localStorage.removeItem(config.storage),{once:true})};
    const update=data=>{card.querySelector('[data-job-file]').textContent=(data.filename||config.preparing)+(data.vendorName?' · '+data.vendorName:'')+(data.totalRows?' · '+data.totalRows.toLocaleString()+' rows':'');card.querySelector('[data-job-phase]').textContent=data.phase||config.defaultPhase;const percent=Math.max(0,Math.min(100,data.percent||0));card.querySelector('[data-job-bar]').style.width=Math.max(3,percent)+'%';card.querySelector('[data-job-percent]').textContent=percent+'%';
      if(data.state==='COMPLETED'||data.state==='COMPLETED_WITH_WARNINGS'){stopped=true;card.className='background-job-card completed';card.querySelector('.background-job-title strong').textContent=config.complete;card.querySelector('[data-job-summary]').textContent=config.summary(data);if(type==='physicalCount'&&data.shortageOrders>0){card.classList.add('has-shortage');card.querySelector('[data-job-shortage]').textContent=data.shortageOrders.toLocaleString()+' pending order(s) need stock attention.';showReview('Review shortages','/app/orders?status=INVENTORY_SHORTAGE')}if(type==='catalogImport'&&data.unmappedSkus>0){card.classList.add('has-shortage');card.querySelector('[data-job-shortage]').textContent=data.unmappedSkus.toLocaleString()+' marketplace SKU(s) still need mapping.';showReview('Review unmapped SKUs','/app/marketplace-skus?status=UNMAPPED')}}
      else if(data.state==='FAILED'){stopped=true;card.className='background-job-card failed';card.querySelector('.background-job-title strong').textContent=type==='physicalCount'?'Physical count needs attention':'Catalogue import needs attention';card.querySelector('[data-job-phase]').textContent=data.error||'Review the staged file and try again.';showReview(config.failureLabel,config.failureUrl(id))}};
    const poll=async()=>{try{const response=await fetch(config.endpoint(id),{headers:{Accept:'application/json'},cache:'no-store'});if(response.status===409){card.querySelector('[data-job-phase]').textContent='Return to the account that started this job to see progress.';return}if(response.status===404){clear();return}if(!response.ok)throw new Error();update(await response.json())}catch(_){card.querySelector('[data-job-phase]').textContent='Reconnecting to background processing…'}if(!stopped)setTimeout(poll,1200)};poll();
  }
})();
