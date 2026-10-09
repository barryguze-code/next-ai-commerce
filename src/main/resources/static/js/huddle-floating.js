(()=>{
  if(window.LiveHuddleUI)return;
  const rooms=new Map(),windows=new Map();let self,people=[],connected=false,allowed=false,send=()=>false,menu,launcher,tray;
  let directory=[],directoryLoadedAt=0,directoryLoading=false,directoryError=false,peopleTab='online',peopleQuery='',peopleList,peopleHint;
  async function loadDirectory(){
    if(directoryLoading||Date.now()-directoryLoadedAt<60000)return;
    directoryLoading=true;directoryError=false;renderPeople();
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
    const trigger=button('ϟ',()=>{menu.hidden=false;trigger.setAttribute('aria-expanded','true');loadDirectory();menu.querySelector('input').focus();});
    trigger.innerHTML='<svg viewBox="0 0 24 24" aria-hidden="true"><path d="m13 2-8 12h7l-1 8 8-12h-7l1-8Z"/></svg>';
    trigger.className='huddle-top-icon';trigger.dataset.tooltip='Collaborate · online teammates';trigger.setAttribute('aria-label','Open huddle and online teammates');trigger.setAttribute('aria-expanded','false');trigger.setAttribute('aria-controls','huddle-teammates');
    menu=node('section',null,'huddle-people-panel');menu.id='huddle-teammates';menu.hidden=true;menu.setAttribute('aria-labelledby','huddle-people-title');
    const heading=node('header',null,'huddle-people-heading'),title=node('strong','Collaborate');title.id='huddle-people-title';
    const close=button('×',()=>closePeople(true));close.className='huddle-people-close';close.setAttribute('aria-label','Close teammate picker');heading.append(title,close);
    peopleHint=node('small');const tabs=node('div',null,'huddle-people-tabs');tabs.setAttribute('role','group');tabs.setAttribute('aria-label','Teammate status');
    for(const [value,label] of [['online','Online'],['all','All teammates']]){const tab=button(label,()=>{peopleTab=value;renderPeople();loadDirectory();});tab.dataset.peopleTab=value;tabs.append(tab);}
    const search=node('input');search.type='search';search.placeholder='Search teammates';search.setAttribute('aria-label','Search teammates');search.addEventListener('input',()=>{peopleQuery=search.value;renderPeople();});
    peopleList=node('div',null,'huddle-people-list');peopleList.setAttribute('role','region');peopleList.setAttribute('aria-label','Teammate results');
    menu.append(heading,peopleHint,tabs,search,peopleList);
    launcher.append(trigger,menu);
    const picker=header.querySelector('.context-menu');
    if(picker)picker.before(launcher);else (header.querySelector('.header-actions')||header).append(launcher);
    let timer;launcher.addEventListener('pointerenter',()=>{clearTimeout(timer);menu.hidden=false;trigger.setAttribute('aria-expanded','true');loadDirectory();});
    launcher.addEventListener('pointerleave',()=>{timer=setTimeout(()=>{if(!launcher.contains(document.activeElement)){menu.hidden=true;trigger.setAttribute('aria-expanded','false');}},250);});
    document.addEventListener('keydown',e=>{if(e.key==='Escape'&&!menu.hidden){e.preventDefault();closePeople(launcher.contains(document.activeElement));}});
    launcher.addEventListener('focusout',()=>setTimeout(()=>{if(!launcher.contains(document.activeElement))closePeople();},0));
    document.addEventListener('click',e=>{if(!launcher.contains(e.target)){menu.hidden=true;trigger.setAttribute('aria-expanded','false');}});
    tray=node('div',null,'floating-huddle-tray');tray.setAttribute('popover','manual');document.body.append(tray);renderPeople();
  }
  function renderPeople(){
    if(!menu)return;peopleHint.textContent=connected?'Across your shared accounts':'Reconnecting… live status unavailable';
    menu.querySelectorAll('[data-people-tab]').forEach(tab=>tab.setAttribute('aria-pressed',String(tab.dataset.peopleTab===peopleTab)));
    peopleList.setAttribute('aria-busy',String(directoryLoading));
    const onlineIds=new Set(people.map(p=>p.id)),all=new Map(directory.map(p=>[p.id,p]));people.forEach(p=>all.set(p.id,p));
    const query=peopleQuery.trim().toLowerCase(),others=(peopleTab==='online'?people:[...all.values()]).filter(p=>p.id!==self?.id&&((p.name||'')+' '+(p.email||'')).toLowerCase().includes(query))
      .sort((a,b)=>Number(onlineIds.has(b.id))-Number(onlineIds.has(a.id))||(a.name||a.email).localeCompare(b.name||b.email));
    peopleList.replaceChildren();
    if(!others.length){const empty=node('p',directoryLoading&&peopleTab==='all'?'Loading teammates…':query?'No matching teammates.':peopleTab==='online'?(connected?'No teammates online right now.':'Connecting to live chat…'):directoryError?'Teammates could not be loaded.':'No teammates available.','huddle-people-empty');empty.setAttribute('role','status');peopleList.append(empty);if(directoryError&&peopleTab==='all')peopleList.append(button('Try again',loadDirectory));}
    for(const person of others){
      const online=connected&&onlineIds.has(person.id),name=person.name||person.email,status=online?'Online':connected?'Offline':'Status unavailable',b=button('',()=>start(person));
      b.setAttribute('aria-label',name+' · '+status);b.disabled=!online||!allowed;b.className='huddle-directory-person';b.dataset.online=String(online);
      const identity=node('span',null,'huddle-person-identity'),avatar=node('span',name.trim().slice(0,1).toUpperCase(),'huddle-person-avatar'),dot=node('span',null,'huddle-presence-dot');
      avatar.setAttribute('aria-hidden','true');avatar.append(dot);identity.append(avatar,node('span',name));b.append(identity,node('small',status));
      b.dataset.tooltip=online?(allowed?'Start a live chat':'Live chat is unavailable for your role'):'Offline follow-up: create a saved task in Collaborate';peopleList.append(b);
    }
    if(peopleTab==='all'){const hint=node('small','Offline follow-up is available through saved tasks in '),link=node('a','Collaborate');link.href='/app/collaboration';hint.append(link);peopleList.append(hint);}
    for(const room of rooms.values())if(room.status==='ACTIVE')peopleList.append(button('↗ '+roomName(room),()=>open(room)));
  }
  function roomName(room){return room.participants.filter(p=>p.id!==self?.id).map(p=>p.name||p.email).join(', ')||'Huddle';}
  function start(person){
    if(!connected||!allowed)return;
    send({type:'CREATE',participantIds:[person.id],subjectType:'PLATFORM',subjectKey:'DIRECT:'+ [self.id,person.id].sort().join(':'),subjectLabel:'Team huddle',parentUrl:'/app/collaboration',contextSnapshot:'{}'});
    if(menu)menu.hidden=true;
  }
  function closeChoice(room,panel){
    const dialog=node('dialog',null,'huddle-close-dialog'),title=node('h2','Keep this conversation?'),description=node('p','Save the messages as a task for follow-up and end this huddle for everyone, or leave without saving. Temporary attachments are not retained in a saved task.');
    title.id='huddle-close-title';dialog.setAttribute('aria-labelledby',title.id);
    const actions=node('div',null,'huddle-close-actions'),cancel=button('Keep chatting',()=>dialog.close());
    actions.append(cancel,button('Close without saving',()=>{if(send({type:'LEAVE',huddleId:room.id})){dialog.close();panel.remove();windows.delete(room.id);rooms.delete(room.id);renderPeople();}}),button('Save as a task',()=>{if(send({type:'SAVE',huddleId:room.id,closeConversation:false})){dialog.close();}}));
    dialog.append(title,description,actions);dialog.addEventListener('close',()=>dialog.remove());document.body.append(dialog);dialog.showModal();cancel.focus();
  }
  function open(room,activate=true,focus=true){
    mount();if(!tray)return;rooms.set(room.id,room);let panel=windows.get(room.id);
    if(!panel){
      panel=node('section',null,'floating-huddle');panel.dataset.huddleId=room.id;panel.setAttribute('aria-label','Huddle with '+roomName(room));
      const head=node('header'),title=node('strong',roomName(room)),pin=button('',()=>{const pinned=panel.classList.toggle('is-pinned');pin.setAttribute('aria-pressed',String(pinned));pin.setAttribute('aria-label',pinned?'Unpin chat':'Pin chat · stay minimized on new messages');pin.title=pinned?'Pinned · new messages will not restore a minimized chat':'Pin chat · stay minimized on new messages';});
      icon(pin,'Pin','m8 3 8 0-2 6 4 4v2H6v-2l4-4-2-6M12 15v7');
      const minimize=button('',()=>{if(panel.classList.contains('is-minimized'))open(rooms.get(room.id));else{panel.classList.add('is-minimized');icon(minimize,'Restore chat','M5 15 12 8l7 7');}});
      minimize.dataset.minimize='';const close=button('',()=>closeChoice(rooms.get(room.id),panel));icon(close,'Close huddle','M6 6l12 12M18 6 6 18');
      icon(minimize,'Minimize chat','M5 12h14');pin.setAttribute('aria-pressed','false');const unread=node('span',null,'huddle-unread');unread.hidden=true;head.append(title,unread,pin,minimize,close);draggable(panel,head);
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
      const addPerson=button('',()=>{const picker=panel.querySelector('footer');picker.hidden=!picker.hidden;if(!picker.hidden){send({type:'PEOPLE',huddleId:room.id});picker.querySelector('select').focus();}});icon(addPerson,'Add teammate','M15 20v-2a4 4 0 0 0-4-4H7a4 4 0 0 0-4 4v2M9 10a4 4 0 1 0 0-8 4 4 0 0 0 0 8M19 8v6M16 11h6');
      tools.append(attach,file,addPerson,submit);
      const mentions=node('div',null,'huddle-mention-options');mentions.hidden=true;mentions.setAttribute('aria-label','Mention a teammate');
      form.append(mentions,field,tools);
      field.addEventListener('input',()=>{
        const match=field.value.slice(0,field.selectionStart).match(/@([^@\n]*)$/);mentions.replaceChildren();mentions.hidden=!match;if(!match)return;
        const current=rooms.get(room.id),candidates=(current?.onlinePeople||people).filter(p=>p.id!==self?.id&&(p.name||p.email).toLowerCase().includes(match[1].toLowerCase()));
        candidates.forEach(person=>{const option=button(person.name||person.email,()=>{const end=field.selectionStart,start=end-match[0].length;field.value=field.value.slice(0,start)+'@'+(person.name||person.email)+' '+field.value.slice(end);mentions.hidden=true;field.focus();if(!current.participants.some(p=>p.id===person.id))send({type:'INVITE',huddleId:room.id,participantIds:[person.id]});});mentions.append(option);});
        if(!candidates.length)mentions.append(node('small','No matching online teammate in this account.'));
      });
      form.addEventListener('submit',e=>{e.preventDefault();if(field.value.trim()&&send({type:'MESSAGE',huddleId:room.id,body:field.value.trim()})){field.value='';mentions.hidden=true;resize();field.focus();}});
      field.addEventListener('keydown',e=>{if(e.key==='Escape'){mentions.hidden=true;}if(e.key==='ArrowDown'&&!mentions.hidden){e.preventDefault();mentions.querySelector('button')?.focus();}if(e.key==='Enter'&&!e.shiftKey&&!e.isComposing){e.preventDefault();if(!mentions.hidden&&mentions.querySelector('button'))mentions.querySelector('button').click();else form.requestSubmit();}});
      const foot=node('footer'),add=node('select');add.setAttribute('aria-label','Add online teammate');add.addEventListener('focus',()=>send({type:'PEOPLE',huddleId:room.id}));add.addEventListener('change',()=>{if(add.value)send({type:'INVITE',huddleId:room.id,participantIds:[add.value]});add.value='';});
      foot.hidden=true;add.addEventListener('change',()=>{foot.hidden=true;field.focus();});foot.append(add);panel.append(head,node('small','Temporary chat · 45 minutes idle expiry'),messages,form,foot,node('p','', 'floating-huddle-error'));
      windows.set(room.id,panel);tray.append(panel);send({type:'PEOPLE',huddleId:room.id});
    }
    panel.hidden=false;if(activate){panel.classList.remove('is-minimized');panel.dataset.unread='0';panel.querySelector('.huddle-unread').hidden=true;icon(panel.querySelector('[data-minimize]'),'Minimize chat','M5 12h14');if(tray.matches(':popover-open'))tray.hidePopover();tray.showPopover();if(panel.style.position==='fixed')panel.style.top=Math.max(8,Math.min(innerHeight-panel.offsetHeight-8,parseFloat(panel.style.top)))+'px';}else if(!tray.matches(':popover-open'))tray.showPopover();panel.querySelector('header strong').textContent=roomName(room);
    const list=panel.querySelector('.floating-huddle-messages'),nearBottom=list.scrollHeight-list.scrollTop-list.clientHeight<50;list.replaceChildren();
    if(!room.messages.length)list.append(node('p','You can message now. No answer step needed.'));
    room.messages.forEach(m=>{const entry=node('article',null,m.senderId===self?.id?'mine':'');entry.append(node('small',m.senderId===self?.id?'You':m.senderName));
      const file=m.body.match(/^Shared file: ([^\n]+)\n(\/app\/huddles\/[0-9a-f-]+\/files\/[0-9a-f-]+)$/i);
      if(file){const openFile=button('📎 '+file[1],async()=>{openFile.disabled=true;try{const response=await fetch(file[2]);if(!response.ok)throw new Error('This file has expired or is no longer available.');const blob=await response.blob();
        if(blob.type.startsWith('image/')){const preview=node('img');preview.alt=file[1];preview.className='huddle-file-preview';const url=URL.createObjectURL(blob);preview.onload=()=>URL.revokeObjectURL(url);preview.onerror=()=>URL.revokeObjectURL(url);preview.src=url;entry.append(preview);}
        else if(blob.type.startsWith('text/plain')){entry.append(node('pre',(await blob.text()).slice(0,12000),'huddle-file-text'));}
        else{const link=node('a');const url=URL.createObjectURL(blob);link.href=url;link.download=file[1];document.body.append(link);link.click();link.remove();setTimeout(()=>URL.revokeObjectURL(url),30000);openFile.disabled=false;}
      }catch(error){entry.append(node('small',error.message));openFile.disabled=false;}});entry.append(openFile,node('small','Temporary · expires after 45 minutes'));}
      else entry.append(node('p',m.body));list.append(entry);});
    if(nearBottom)list.scrollTop=list.scrollHeight;
    const add=panel.querySelector('select');add.replaceChildren(new Option('＋ Add teammate',''));(room.onlinePeople||[]).filter(p=>!room.participants.some(member=>member.id===p.id)).forEach(p=>add.add(new Option(p.name||p.email,p.id)));add.disabled=room.participants.length>=3||!connected;
    panel.querySelector('textarea').disabled=!connected;panel.querySelector('button[type=submit]').disabled=!connected;if(activate&&focus)requestAnimationFrame(()=>panel.querySelector('textarea').focus({preventScroll:true}));renderPeople();
  }
  function event(e,sender){
    send=sender;mount();
    if(e.type==='WELCOME'){self=e.self;allowed=e.canHuddle;people=e.online||[];connected=true;rooms.clear();(e.huddles||[]).forEach(r=>{rooms.set(r.id,r);open(r);});for(const [id,p]of windows)if(!rooms.has(id)){p.remove();windows.delete(id);}renderPeople();}
    else if(e.type==='PRESENCE'){people=e.online||[];renderPeople();}
    else if(e.type==='OFFLINE'){connected=false;people=[];renderPeople();windows.forEach(p=>{p.querySelector('textarea').disabled=true;p.querySelector('button[type=submit]').disabled=true;});}
    else if(e.type==='ROOM_PEOPLE'){const room=rooms.get(e.huddleId);if(room){room.onlinePeople=e.online||[];open(room,false);}}
    else if(e.type==='HUDDLE_STARTED'||e.type==='HUDDLE_UPDATED'){
      if(!e.huddle.participants.some(p=>p.id===self?.id)){rooms.delete(e.huddle.id);windows.get(e.huddle.id)?.remove();windows.delete(e.huddle.id);renderPeople();return;}
      const previous=rooms.get(e.huddle.id),incoming=e.huddle.messages.length>(previous?.messages.length||0)&&e.huddle.messages.at(-1)?.senderId!==self?.id;
      const panel=windows.get(e.huddle.id),quiet=panel?.classList.contains('is-pinned')&&panel.classList.contains('is-minimized');
      if(incoming&&quiet){const count=Number(panel.dataset.unread||0)+1;panel.dataset.unread=String(count);const badge=panel.querySelector('.huddle-unread');badge.textContent=String(count);badge.setAttribute('aria-label',count+' unread messages');badge.hidden=false;}
      e.huddle.onlinePeople=previous?.onlinePeople;open(e.huddle,!quiet&&(e.type==='HUDDLE_STARTED'||incoming),e.type==='HUDDLE_STARTED'&&e.huddle.participants[0]?.id===self?.id);
    }
    else if(e.type==='HUDDLE_ENDED'||e.type==='HUDDLE_SAVED'){rooms.delete(e.huddle.id);windows.get(e.huddle.id)?.remove();windows.delete(e.huddle.id);renderPeople();if(e.type==='HUDDLE_SAVED'){const notice=node('div',null,'huddle-saved-notice');notice.setAttribute('role','status');const link=node('a','Messages saved · Open Collaboration');link.href='/app/collaboration';notice.append(link,button('×',()=>notice.remove()));document.body.append(notice);}}
    else if(e.type==='ERROR'){windows.forEach(p=>{p.querySelector('.floating-huddle-error').textContent=e.message;});}
  }
  window.LiveHuddleUI={event,open,start,showPeople:()=>{mount();menu.hidden=false;launcher.querySelector('button').setAttribute('aria-expanded','true');loadDirectory();}};document.readyState==='loading'?document.addEventListener('DOMContentLoaded',mount):mount();
  // Only an explicit minimize action collapses a chat; incoming messages stay visible.
})();
