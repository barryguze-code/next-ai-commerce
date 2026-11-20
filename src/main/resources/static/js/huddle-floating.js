(()=>{
  if(window.LiveHuddleUI)return;
  const rooms=new Map(),windows=new Map();let self,people=[],connected=false,allowed=false,send=()=>false,menu,launcher,tray;
  const finalizeDrafts=new Map(),finalizePending=new Map(),leavePending=new Map();let finalizing=false;
  const selectedPeople=new Set();let startChat,starting=false;let directory=[],directoryLoadedAt=0,directoryLoading=false,directoryError=false,peopleTab='online',peopleQuery='',peopleList,peopleHint;
  async function loadDirectory(){
    if(directoryLoading||Date.now()-directoryLoadedAt<60000)return;
    directoryLoading=true;directoryLoadedAt=Date.now();directoryError=false;renderPeople();
    try{const response=await fetch('/app/collaboration/teammates',{headers:{Accept:'application/json'},cache:'no-store'});if(!response.ok)throw new Error();directory=await response.json();directoryLoadedAt=Date.now();}
    catch(_){directoryError=true;}finally{directoryLoading=false;renderPeople();}
  }
  const node=(tag,text,cls)=>{const n=document.createElement(tag);if(text)n.textContent=text;if(cls)n.className=cls;return n;};
  const button=(label,action)=>{const b=node('button',label);b.type='button';b.addEventListener('click',action);return b;};
  const icon=(b,label,path)=>{b.setAttribute('aria-label',label);b.title=label;b.innerHTML='<svg viewBox="0 0 24 24" aria-hidden="true"><path d="'+path+'"/></svg>';};
  function draggable(panel,head){
    let drag;
    head.addEventListener('pointerdown',e=>{if(e.target.closest('button')||e.button!==0)return;const box=panel.getBoundingClientRect();drag={x:e.clientX-box.left,y:e.clientY-box.top};panel.style.position='fixed';panel.style.left=box.left+'px';panel.style.top=box.top+'px';head.setPointerCapture(e.pointerId);e.preventDefault();});
    head.addEventListener('pointermove',e=>{if(!drag)return;panel.style.left=Math.max(8,Math.min(innerWidth-panel.offsetWidth-8,e.clientX-drag.x))+'px';panel.style.top=Math.max(8,Math.min(innerHeight-panel.offsetHeight-8,e.clientY-drag.y))+'px';});
    head.addEventListener('pointerup',()=>drag=null);head.addEventListener('pointercancel',()=>drag=null);
    window.addEventListener('resize',()=>{if(panel.style.position!=='fixed')return;panel.style.left=Math.max(8,Math.min(innerWidth-panel.offsetWidth-8,parseFloat(panel.style.left)))+'px';panel.style.top=Math.max(8,Math.min(innerHeight-panel.offsetHeight-8,parseFloat(panel.style.top)))+'px';});
  }
  function mount(){
    if(launcher)return;const header=document.querySelector('.workspace-header');if(!header)return;
    launcher=node('div',null,'huddle-launcher');const closePeople=(restore=false)=>{menu.hidden=true;trigger.setAttribute('aria-expanded','false');if(restore)trigger.focus();};
    function openPeople(focus=false){if(menu.hidden){peopleTab='online';peopleQuery='';menu.querySelector('input').value='';renderPeople();}menu.hidden=false;trigger.setAttribute('aria-expanded','true');loadDirectory();if(focus)menu.querySelector('input').focus();}
    const trigger=button('',()=>openPeople(true));
    const artwork=node('img');artwork.src='/images/platform/table/collaboration-online.png';artwork.alt='';trigger.append(artwork);
    trigger.className='huddle-top-icon';trigger.dataset.tooltip='Collaborate · online teammates';trigger.setAttribute('aria-label','Open huddle and online teammates');trigger.setAttribute('aria-expanded','false');trigger.setAttribute('aria-controls','huddle-teammates');
    menu=node('section',null,'huddle-people-panel');menu.id='huddle-teammates';menu.hidden=true;menu.setAttribute('aria-labelledby','huddle-people-title');
    const heading=node('header',null,'huddle-people-heading'),title=node('strong','Collaborate');title.id='huddle-people-title';
    const close=button('',()=>closePeople(true));close.className='huddle-people-close dialog-close';close.textContent='×';close.setAttribute('aria-label','Close teammate picker');heading.append(title,close);
    peopleHint=node('small');const tabs=node('div',null,'huddle-people-tabs');tabs.setAttribute('role','group');tabs.setAttribute('aria-label','Teammate status');
    for(const [value,label] of [['online','Online'],['all','All teammates']]){const tab=button(label,()=>{peopleTab=value;renderPeople();loadDirectory();});tab.dataset.peopleTab=value;tabs.append(tab);}
    const search=node('input');search.type='search';search.placeholder='Search teammates';search.setAttribute('aria-label','Search teammates');search.addEventListener('input',()=>{peopleQuery=search.value;renderPeople();});
    peopleList=node('div',null,'huddle-people-list');peopleList.setAttribute('role','region');peopleList.setAttribute('aria-label','Teammate results');
    startChat=button('Select teammate',startSelected);startChat.className='primary-button huddle-start-chat';const footer=node('div',null,'huddle-people-footer');
    if(document.querySelector('.sidebar-create-task[data-new-task]')){const createTask=button('Create a store task',()=>{closePeople();window.openGeneralTask?.(trigger);});createTask.className='secondary-button huddle-create-store-task';footer.append(createTask);}
    footer.append(startChat);menu.append(heading,peopleHint,tabs,search,peopleList,footer);
    launcher.append(trigger,menu);
    const picker=header.querySelector('.context-menu');
    if(picker)picker.before(launcher);else (header.querySelector('.header-actions')||header).append(launcher);
    let timer;launcher.addEventListener('pointerenter',()=>{clearTimeout(timer);openPeople();});
    launcher.addEventListener('pointerleave',()=>{timer=setTimeout(()=>{if(!launcher.contains(document.activeElement)){menu.hidden=true;trigger.setAttribute('aria-expanded','false');}},250);});
    document.addEventListener('keydown',e=>{if(e.key==='Escape'&&!menu.hidden){e.preventDefault();closePeople(launcher.contains(document.activeElement));}});
    launcher.addEventListener('focusout',()=>setTimeout(()=>{if(!launcher.contains(document.activeElement))closePeople();},0));
    document.addEventListener('click',e=>{for(const panel of windows.values())if(!panel.querySelector('form').contains(e.target))panel.dismissMembers?.();if(!e.composedPath().includes(launcher)){menu.hidden=true;trigger.setAttribute('aria-expanded','false');}});
    tray=node('div',null,'floating-huddle-tray');tray.setAttribute('popover','manual');document.body.append(tray);renderPeople();
  }
  function renderPeople(){
    if(!menu)return;const focusedPerson=document.activeElement?.dataset.personId;peopleHint.textContent=connected?'Across your shared accounts · Up to 5 people, including you':'Reconnecting… live status unavailable';
    menu.querySelectorAll('[data-people-tab]').forEach(tab=>tab.setAttribute('aria-pressed',String(tab.dataset.peopleTab===peopleTab)));
    peopleList.setAttribute('aria-busy',String(directoryLoading));
    const onlineIds=new Set(people.map(p=>p.id)),all=new Map(directory.map(p=>[p.id,p]));people.forEach(p=>all.set(p.id,p));
    const query=peopleQuery.trim().toLowerCase(),others=(peopleTab==='online'?people:[...all.values()]).filter(p=>p.id!==self?.id&&((p.name||'')+' '+(p.email||'')).toLowerCase().includes(query))
      .sort((a,b)=>Number(onlineIds.has(b.id))-Number(onlineIds.has(a.id))||(a.name||a.email).localeCompare(b.name||b.email));
    peopleList.replaceChildren();
    if(!others.length){const empty=node('p',directoryLoading&&peopleTab==='all'?'Loading teammates…':query?'No matching teammates.':peopleTab==='online'?(connected?'No teammates online right now.':'Connecting to live collaboration…'):directoryError?'Teammates could not be loaded.':'No teammates available.','huddle-people-empty');empty.setAttribute('role','status');peopleList.append(empty);if(directoryError&&peopleTab==='all')peopleList.append(button('Try again',()=>{directoryLoadedAt=0;loadDirectory();}));}
    for(const person of others){
      const online=connected&&onlineIds.has(person.id),name=person.name||person.email,status=online?'Online':connected?'Offline':'Status unavailable',b=button('',()=>{if(selectedPeople.has(person.id))selectedPeople.delete(person.id);else if(selectedPeople.size<4)selectedPeople.add(person.id);renderPeople();[...peopleList.querySelectorAll('[data-person-id]')].find(n=>n.dataset.personId===person.id)?.focus();});
      b.setAttribute('aria-label',name+' · '+status);b.disabled=(!online&&!selectedPeople.has(person.id))||!allowed;b.className='huddle-directory-person';b.dataset.online=String(online);b.dataset.personId=person.id;b.setAttribute('aria-pressed',String(selectedPeople.has(person.id)));b.disabled=b.disabled||(!selectedPeople.has(person.id)&&selectedPeople.size>=4);
      const identity=node('span',null,'huddle-person-identity'),avatar=node('span',initials(name),'huddle-person-avatar'),dot=node('span',null,'huddle-presence-dot');
      avatar.setAttribute('aria-hidden','true');avatar.append(dot);identity.append(avatar,node('span',name));b.append(identity,node('small',status));
      b.dataset.tooltip=online?(allowed?(selectedPeople.has(person.id)?'Remove from selection':'Select for collaboration'):'Live collaboration is unavailable for your role'):'Offline follow-up: create a saved task in Collaborate';peopleList.append(b);
    }
    if(peopleTab==='all'){const hint=node('small','Offline follow-up is available through saved tasks in '),link=node('a','Collaborate');link.href='/app/collaboration';hint.append(link);peopleList.append(hint);}
    startChat.textContent=selectedPeople.size?'Start Collaboration':'Select teammate';startChat.disabled=starting||!connected||!allowed||!selectedPeople.size||[...selectedPeople].some(id=>!onlineIds.has(id));
    for(const room of rooms.values())if(room.status==='ACTIVE')peopleList.append(button('↗ '+roomName(room),()=>open(room)));
    if(focusedPerson&&!menu.hidden)[...peopleList.querySelectorAll('[data-person-id]')].find(n=>n.dataset.personId===focusedPerson)?.focus();
  }
  const initials=name=>{const words=String(name||'').trim().split(/\s+/).filter(Boolean);return (words.length>1?Array.from(words[0])[0]+Array.from(words.at(-1))[0]:Array.from(words[0]||'?').slice(0,2).join('')).toUpperCase();};
  function roomName(room){return room.participants.filter(p=>p.id!==self?.id).map(p=>p.name||p.email).join(', ')||'Huddle';}
  function start(person){selectedPeople.clear();selectedPeople.add(person.id);startSelected();}
  function startSelected(){
    if(starting||!connected||!allowed||!selectedPeople.size)return;
    const ids=[...selectedPeople];if(send({type:'CREATE',participantIds:ids,subjectType:'PLATFORM',subjectKey:'DIRECT:'+ [self.id,...ids].sort().join(':'),subjectLabel:'Team huddle',parentUrl:'/app/collaboration',contextSnapshot:'{}'})){starting=true;renderPeople();}
  }
  function leaveRoom(room){return new Promise((resolve,reject)=>{const timer=setTimeout(()=>{leavePending.delete(room.id);reject(new Error('Leaving was not confirmed. Reconnect to check its status.'));},20000);leavePending.set(room.id,{resolve:()=>{clearTimeout(timer);resolve();},reject:error=>{clearTimeout(timer);reject(error);}});if(!send({type:'LEAVE',huddleId:room.id})){leavePending.get(room.id)?.reject(new Error('Connection unavailable. Your collaboration is still here.'));leavePending.delete(room.id);}});}
  function closeChoice(room,panel){
    const draft=panel.querySelector('textarea');
    if(!(room.messages||[]).length&&!draft.value.trim()&&!panel.querySelector('input[type=file]')?.files.length&&!panel.querySelector('button[aria-label^="Attach file"]')?.disabled){
      const close=panel.querySelector('.dialog-close');if(close.disabled)return;close.disabled=true;
      leaveRoom(room).catch(error=>{close.disabled=false;panel.querySelector('.floating-huddle-error').textContent=error.message;});return;
    }
    if(!window.openHuddleTask){panel.querySelector('.floating-huddle-error').textContent='Task controls are not ready. Refresh and try again.';return;}
    finalizing=true;if(tray.matches(':popover-open'))tray.hidePopover();
    window.openHuddleTask({id:room.id,canSave:room.startedBy?.id===self?.id,trigger:panel.querySelector('.dialog-close'),people:room.participants,history:room.messages,
      draft:finalizeDrafts.get(room.id)||{title:room.subjectKey?.startsWith('DIRECT:')?'':room.subjectLabel||'',people:[self.id],due:''},
      remember:draft=>finalizeDrafts.set(room.id,draft),
      returnToRoom:()=>{finalizing=false;const current=rooms.get(room.id);if(current?.status==='ACTIVE')open(current);else if(windows.size&&!tray.matches(':popover-open'))tray.showPopover();},
      leave:()=>leaveRoom(room),
      save:draft=>new Promise((resolve,reject)=>{const timer=setTimeout(()=>{finalizePending.delete(room.id);reject(new Error('Save confirmation timed out. Your draft is still here; retry safely.'));},20000);finalizePending.set(room.id,{resolve:()=>{clearTimeout(timer);resolve();},reject:error=>{clearTimeout(timer);reject(error);}});if(!send({type:'SAVE',huddleId:room.id,closeConversation:false,title:draft.title,assigneeIds:draft.people,dueDate:draft.due,timeZone:Intl.DateTimeFormat().resolvedOptions().timeZone||'America/Los_Angeles'})){finalizePending.get(room.id)?.reject(new Error('Connection unavailable. Your draft is still here.'));finalizePending.delete(room.id);}})
    });
  }
  function open(room,activate=true,focus=true){
    mount();if(!tray)return;rooms.set(room.id,room);let panel=windows.get(room.id);
    if(!panel){
      panel=node('section',null,'floating-huddle');panel.dataset.huddleId=room.id;panel.setAttribute('aria-label','Huddle with '+roomName(room));
      const head=node('header'),avatars=node('span',null,'huddle-header-avatars'),title=node('strong',roomName(room));
      const minimize=button('',()=>{if(panel.classList.contains('is-minimized'))open(rooms.get(room.id));else{panel.classList.add('is-minimized');icon(minimize,'Restore collaboration','M5 15 12 8l7 7');}});
      minimize.dataset.minimize='';const close=button('',()=>closeChoice(rooms.get(room.id),panel));close.className='dialog-close';close.textContent='×';close.setAttribute('aria-label','Close collaboration');
      icon(minimize,'Minimize collaboration','M5 12h14');const unread=node('span',null,'huddle-unread');unread.hidden=true;head.append(avatars,title,unread,minimize,close);draggable(panel,head);
      const messages=node('div',null,'floating-huddle-messages');messages.setAttribute('role','log');messages.setAttribute('aria-live','polite');
      const form=node('form'),field=node('textarea');field.rows=1;field.maxLength=2000;field.placeholder='Message…';field.setAttribute('aria-label','Huddle message');field.title='Enter to send · Shift+Enter for a new line · @ to add a teammate';
      const resize=()=>{field.style.height='auto';field.style.height=Math.min(150,field.scrollHeight)+'px';};field.addEventListener('input',resize);
      const submit=button('',()=>{});icon(submit,'Send','m3 3 18 9-18 9 4-9-4-9ZM7 12h14');submit.type='submit';
      const tools=node('div',null,'huddle-composer-tools');
      const file=node('input');file.type='file';file.accept='image/png,image/jpeg,application/pdf,text/plain';file.hidden=true;
      const attach=button('',()=>file.click());icon(attach,'Attach file · up to 2 MB','m8 12 6-6a3 3 0 0 1 4 4l-8 8a5 5 0 0 1-7-7l9-9M6 14l8-8');
      file.addEventListener('change',async()=>{const selected=file.files[0];if(!selected)return;const error=panel.querySelector('.floating-huddle-error');if(selected.size>2*1024*1024){error.textContent='Choose a file up to 2 MB.';file.value='';return;}attach.disabled=true;
        try{const data=new FormData();data.append('file',selected);document.querySelectorAll('input[type=hidden][name*="csrf"]').forEach(input=>data.set(input.name,input.value));const response=await fetch('/app/huddles/'+room.id+'/files',{method:'POST',body:data});if(!response.ok)throw new Error('File could not be shared. Use PNG, JPEG, PDF, or text, up to 2 MB (8 MB per room).');error.textContent='';}
        catch(failure){error.textContent=failure.message;}finally{attach.disabled=false;file.value='';}});
      const mentions=node('div',null,'huddle-mention-options');mentions.hidden=true;mentions.id='huddle-members-'+room.id;mentions.setAttribute('aria-label','Teammates');
      let adding=false;
      const addPerson=button('',()=>{adding=true;renderMembers();send({type:'PEOPLE',huddleId:room.id});if(adding)mentions.querySelector('button')?.focus();});icon(addPerson,'Add teammate','M15 20v-2a4 4 0 0 0-4-4H7a4 4 0 0 0-4 4v2M9 10a4 4 0 1 0 0-8 4 4 0 0 0 0 8M19 8v6M16 11h6');
      addPerson.setAttribute('aria-controls',mentions.id);addPerson.setAttribute('aria-expanded','false');
      const dismissMembers=()=>{adding=false;mentions.hidden=true;addPerson.setAttribute('aria-expanded','false');};
      function renderMembers(){
        const match=field.value.slice(0,field.selectionStart).match(/@([^@\n]*)$/),current=rooms.get(room.id);
        const focused=document.activeElement?.dataset.memberId,hadFocus=mentions.contains(document.activeElement);mentions.replaceChildren();mentions.hidden=!adding&&!match;addPerson.setAttribute('aria-expanded',String(adding));if(mentions.hidden)return;
        if(adding)mentions.append(node('small','Up to 5 people, including you'));
        const roster=new Map((current.onlinePeople||[]).map(p=>[p.id,p]));if(!adding)current.participants.forEach(p=>roster.set(p.id,p));
        const candidates=[...roster.values()].filter(p=>p.id!==self?.id&&(!adding||!current.participants.some(m=>m.id===p.id))&&(adding||(p.name||p.email).toLowerCase().includes(match[1].toLowerCase())));
        candidates.forEach(person=>{const member=current.participants.some(p=>p.id===person.id),option=button('',()=>{
          if(!adding){const end=field.selectionStart,start=end-match[0].length;field.value=field.value.slice(0,start)+'@'+(person.name||person.email)+' '+field.value.slice(end);}
          if(!member)send({type:'INVITE',huddleId:room.id,participantIds:[person.id]});dismissMembers();field.focus();
        });option.className='huddle-directory-person';option.dataset.memberId=person.id;const identity=node('span',null,'huddle-person-identity');identity.append(node('span',initials(person.name||person.email),'huddle-person-avatar'),node('span',person.name||person.email));option.append(identity);option.disabled=!connected||(!member&&current.participants.length>=5);if(connected&&!member&&current.participants.length>=5)option.append(node('small','5-person limit reached'));mentions.append(option);});
        if(!candidates.length)mentions.append(node('small','No additional online teammates in this account.'));if(hadFocus){const next=[...mentions.querySelectorAll('button')].find(b=>b.dataset.memberId===focused&&!b.disabled)||mentions.querySelector('button:not(:disabled)');if(next)next.focus();else addPerson.focus();}
      }
      panel.refreshMembers=renderMembers;
      tools.append(attach,file,addPerson,submit);form.append(mentions,field,tools);
      field.addEventListener('input',()=>{const wasHidden=mentions.hidden;adding=false;renderMembers();if(wasHidden&&!mentions.hidden)send({type:'PEOPLE',huddleId:room.id});});
      mentions.addEventListener('keydown',e=>{if(e.key==='Escape'){e.preventDefault();dismissMembers();addPerson.focus();}});
      panel.dismissMembers=dismissMembers;
      form.addEventListener('submit',e=>{e.preventDefault();if(field.value.trim()&&send({type:'MESSAGE',huddleId:room.id,body:field.value.trim()})){field.value='';mentions.hidden=true;resize();field.focus();}});
      field.addEventListener('keydown',e=>{if(e.key==='Escape'){dismissMembers();}if(e.key==='ArrowDown'&&!mentions.hidden){e.preventDefault();mentions.querySelector('button')?.focus();}if(e.key==='Enter'&&!e.shiftKey&&!e.isComposing){e.preventDefault();if(!mentions.hidden&&mentions.querySelector('button'))mentions.querySelector('button').click();else form.requestSubmit();}});
      panel.append(head,node('small','Private collaboration · Up to 5 people, including you'),messages,form,node('p','', 'floating-huddle-error'));
      windows.set(room.id,panel);tray.append(panel);send({type:'PEOPLE',huddleId:room.id});
    }
    panel.hidden=false;if(activate){panel.classList.remove('is-minimized');panel.dataset.unread='0';panel.querySelector('.huddle-unread').hidden=true;icon(panel.querySelector('[data-minimize]'),'Minimize collaboration','M5 12h14');if(tray.matches(':popover-open'))tray.hidePopover();if(!finalizing)tray.showPopover();if(panel.style.position==='fixed')panel.style.top=Math.max(8,Math.min(innerHeight-panel.offsetHeight-8,parseFloat(panel.style.top)))+'px';}else if(!finalizing&&!tray.matches(':popover-open'))tray.showPopover();panel.querySelector('header strong').textContent=roomName(room);
    const avatars=panel.querySelector('.huddle-header-avatars');avatars.replaceChildren();room.participants.filter(p=>p.id!==self?.id).forEach(p=>{const avatar=node('span',initials(p.name||p.email),'huddle-person-avatar');avatar.setAttribute('aria-hidden','true');avatars.append(avatar);});
    const list=panel.querySelector('.floating-huddle-messages'),nearBottom=list.scrollHeight-list.scrollTop-list.clientHeight<50;list.replaceChildren();
    if(!room.messages.length)list.append(node('p','You can message now. No answer step needed.'));
    room.messages.forEach(m=>{const entry=node('article',null,m.senderId===self?.id?'mine':'');entry.append(node('small',m.senderId===self?.id?'You':m.senderName));
      const file=m.body.match(/^Shared file: ([^\n]+)\n(\/app\/huddles\/[0-9a-f-]+\/files\/[0-9a-f-]+)$/i);
      if(file){const openFile=button('📎 '+file[1],async()=>{openFile.disabled=true;try{const response=await fetch(file[2]);if(!response.ok)throw new Error('This file has expired or is no longer available.');const blob=await response.blob();
        if(blob.type.startsWith('image/')){const preview=node('img');preview.alt=file[1];preview.className='huddle-file-preview';const url=URL.createObjectURL(blob);preview.onload=()=>URL.revokeObjectURL(url);preview.onerror=()=>URL.revokeObjectURL(url);preview.src=url;entry.append(preview);}
        else if(blob.type.startsWith('text/plain')){entry.append(node('pre',(await blob.text()).slice(0,12000),'huddle-file-text'));}
        else{const link=node('a');const url=URL.createObjectURL(blob);link.href=url;link.download=file[1];document.body.append(link);link.click();link.remove();setTimeout(()=>URL.revokeObjectURL(url),30000);openFile.disabled=false;}
      }catch(error){entry.append(node('small',error.message));openFile.disabled=false;}});entry.append(openFile,node('small','Retained with this collaboration'));}
      else entry.append(node('p',m.body));list.append(entry);});
    if(nearBottom)list.scrollTop=list.scrollHeight;
    panel.refreshMembers?.();
    panel.querySelector('textarea').disabled=!connected;panel.querySelector('button[type=submit]').disabled=!connected;if(activate&&focus)requestAnimationFrame(()=>panel.querySelector('textarea').focus({preventScroll:true}));renderPeople();
  }
  function event(e,sender){
    send=sender;mount();
    if(e.type==='WELCOME'){starting=false;self=e.self;allowed=e.canHuddle;people=e.online||[];connected=true;rooms.clear();(e.huddles||[]).forEach(r=>{rooms.set(r.id,r);open(r);});for(const [id,p]of windows)if(!rooms.has(id)){p.remove();windows.delete(id);}renderPeople();}
    else if(e.type==='PRESENCE'){people=e.online||[];renderPeople();for(const room of rooms.values())if(room.status==='ACTIVE')send({type:'PEOPLE',huddleId:room.id});}
    else if(e.type==='OFFLINE'){leavePending.forEach(p=>p.reject(new Error('Connection lost. Leaving was not confirmed.')));leavePending.clear();finalizePending.forEach(p=>p.reject(new Error('Connection lost. Your draft is still here.')));finalizePending.clear();starting=false;connected=false;people=[];renderPeople();windows.forEach(p=>{p.querySelector('textarea').disabled=true;p.querySelector('button[type=submit]').disabled=true;});}
    else if(e.type==='ROOM_PEOPLE'){const room=rooms.get(e.huddleId);if(room){room.onlinePeople=e.online||[];open(room,false);}}
    else if(e.type==='HUDDLE_STARTED'||e.type==='HUDDLE_UPDATED'){
      if(!e.huddle.participants.some(p=>p.id===self?.id)||(Array.isArray(e.huddle.joinedParticipantIds)&&!e.huddle.joinedParticipantIds.includes(self?.id))){leavePending.get(e.huddle.id)?.resolve();leavePending.delete(e.huddle.id);finalizing=false;finalizeDrafts.delete(e.huddle.id);rooms.delete(e.huddle.id);windows.get(e.huddle.id)?.remove();windows.delete(e.huddle.id);if(!windows.size&&tray.matches(':popover-open'))tray.hidePopover();renderPeople();return;}
      if((e.type==='HUDDLE_STARTED'&&e.huddle.startedBy?.id===self?.id)||(starting&&selectedPeople.size&&[...selectedPeople].every(id=>e.huddle.participants.some(p=>p.id===id)))){starting=false;selectedPeople.clear();}
      const previous=rooms.get(e.huddle.id),incoming=e.huddle.messages.length>(previous?.messages.length||0)&&e.huddle.messages.at(-1)?.senderId!==self?.id;
      const panel=windows.get(e.huddle.id),quiet=false;
      if(incoming&&quiet){const count=Number(panel.dataset.unread||0)+1;panel.dataset.unread=String(count);const badge=panel.querySelector('.huddle-unread');badge.textContent=String(count);badge.setAttribute('aria-label',count+' unread messages');badge.hidden=false;}
      e.huddle.onlinePeople=previous?.onlinePeople;open(e.huddle,!quiet&&(e.type==='HUDDLE_STARTED'||incoming),e.type==='HUDDLE_STARTED'&&e.huddle.participants[0]?.id===self?.id);
    }
    else if(e.type==='HUDDLE_ENDED'||e.type==='HUDDLE_SAVED'){leavePending.get(e.huddle.id)?.resolve();leavePending.delete(e.huddle.id);if(e.type==='HUDDLE_SAVED')finalizePending.get(e.huddle.id)?.resolve();else finalizePending.get(e.huddle.id)?.reject(new Error('The collaboration ended before saving was confirmed. Your draft is still here.'));finalizePending.delete(e.huddle.id);finalizing=false;finalizeDrafts.delete(e.huddle.id);rooms.delete(e.huddle.id);windows.get(e.huddle.id)?.remove();windows.delete(e.huddle.id);if(!windows.size&&tray.matches(':popover-open'))tray.hidePopover();renderPeople();if(e.type==='HUDDLE_SAVED'){const notice=node('div',null,'huddle-saved-notice');notice.setAttribute('role','status');const link=node('a','Messages saved · Open Collaboration');link.href='/app/collaboration/board';notice.append(link,button('×',()=>notice.remove()));document.body.append(notice);}}
    else if(e.type==='ERROR'){if(e.action==='LEAVE'&&e.huddleId){leavePending.get(e.huddleId)?.reject(new Error(e.message));leavePending.delete(e.huddleId);}if(e.action==='SAVE'&&e.huddleId){finalizePending.get(e.huddleId)?.reject(new Error(e.message));finalizePending.delete(e.huddleId);}starting=false;renderPeople();peopleHint.textContent=e.message;peopleHint.setAttribute('role','alert');windows.forEach(p=>{p.querySelector('.floating-huddle-error').textContent=e.message;});}
  }
  window.LiveHuddleUI={event,open,start,showPeople:()=>{mount();menu.hidden=false;launcher.querySelector('button').setAttribute('aria-expanded','true');loadDirectory();}};document.readyState==='loading'?document.addEventListener('DOMContentLoaded',mount):mount();
  // Only an explicit minimize action collapses a chat; incoming messages stay visible.
})();
