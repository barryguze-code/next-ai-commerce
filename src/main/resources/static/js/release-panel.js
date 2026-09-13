(()=>{
  const trigger=document.querySelector('[data-release-version]');
  if(!trigger||window.nextaiReleasePanel)return;
  window.nextaiReleasePanel=true;
  const current=trigger.dataset.releaseVersion;
  const key='nextai.release.seen:'+trigger.dataset.releaseUser;
  const dot=trigger.querySelector('.release-unread-dot');
  const readSeen=()=>{try{return localStorage.getItem(key);}catch(_){return null;}};
  const updateDot=()=>{dot.hidden=trigger.dataset.releaseNotification!=='true'||readSeen()===current;};
  updateDot();window.addEventListener('storage',event=>{if(event.key===key)updateDot();});
  const el=(tag,className,text)=>{const node=document.createElement(tag);if(className)node.className=className;if(text!==undefined)node.textContent=text;return node;};
  const panel=el('dialog','release-panel');panel.setAttribute('aria-labelledby','release-panel-title');
  const header=el('header','release-panel-header');
  const back=el('button','release-panel-back','← Back to workspace');back.type='button';
  const close=el('button','release-panel-close','×');close.type='button';close.setAttribute('aria-label','Close release notes');
  header.append(back,close);
  const body=el('div','release-panel-body');body.append(el('h1','','What’s new'));body.firstChild.id='release-panel-title';
  const status=el('p','release-panel-status');status.setAttribute('role','status');
  const content=el('div','release-panel-content');body.append(status,content);panel.append(header,body);document.body.append(panel);
  const dismiss=()=>{panel.close();trigger.focus();};back.addEventListener('click',dismiss);close.addEventListener('click',dismiss);
  panel.addEventListener('click',event=>{if(event.target===panel){const r=panel.getBoundingClientRect();if(event.clientX<r.left||event.clientX>r.right)dismiss();}});
  panel.addEventListener('close',()=>trigger.focus());
  let history=null;
  function show(version){
    const selected=history.find(release=>release.version===version)||history[0];
    content.replaceChildren();
    const picker=el('div','release-panel-picker');const label=el('label','','Recent releases');label.htmlFor='release-panel-version';
    const select=el('select');select.id='release-panel-version';
    const recent=history.slice(0,5);if(!recent.includes(selected))recent.push(selected);
    for(const release of recent){const option=el('option','',`v${release.version} · ${release.title}`);option.value=release.version;option.selected=release===selected;select.append(option);}
    select.addEventListener('change',()=>{show(select.value);body.scrollTop=0;content.querySelector('select').focus();});picker.append(label,select);
    const article=el('article','release-panel-article');article.append(el('span','release-panel-kicker',`RELEASE ${selected.version}`),el('h2','',selected.title));
    for(const section of selected.sections){const sectionNode=el('section');sectionNode.append(el('h3','',section.title));const list=el('ul');for(const item of section.items)list.append(el('li','',item));sectionNode.append(list);article.append(sectionNode);}
    const footer=el('footer','release-panel-history');footer.append(el('h2','','Deployment history'),el('p','','Browse the notes from every documented production release.'));
    for(const release of history){const button=el('button','release-history-item');button.type='button';button.append(el('strong','',`v${release.version}`),el('span','',release.title));if(release===selected)button.setAttribute('aria-current','page');button.addEventListener('click',()=>{show(release.version);body.scrollTop=0;content.querySelector('select').focus();});footer.append(button);}
    content.append(picker,article,footer);
  }
  trigger.addEventListener('click',async event=>{
    if(event.ctrlKey||event.metaKey||event.shiftKey||event.altKey)return;
    event.preventDefault();if(!panel.open)panel.showModal();status.textContent='Loading release notes…';
    try{
      if(!history){const response=await fetch('/app/releases/history',{headers:{Accept:'application/json'}});if(!response.ok)throw new Error();history=await response.json();if(!Array.isArray(history)||!history.length)throw new Error();}
      show(current);status.textContent='';body.scrollTop=0;
      try{localStorage.setItem(key,current);}catch(_){}dot.hidden=true;
    }catch(_){history=null;status.textContent='Release notes could not be loaded. Close this panel and try again.';}
  });
})();
