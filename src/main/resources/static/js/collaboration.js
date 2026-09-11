(()=>{
  let membersPromise,state=null,huddleSocket=null,huddleSelf=null,huddleCanChat=false,huddleOnline=[],huddleReconnect=null,huddleUnread=0,huddleSoundReady=false,huddleRingTimer=null;
  const liveHuddles=new Map();
  const $=(selector,scope=document)=>scope.querySelector(selector);
  const $$=(selector,scope=document)=>[...scope.querySelectorAll(selector)];
  const escape=value=>{const node=document.createElement('span');node.textContent=value??'';return node.innerHTML};
  const formattedBody=value=>escape(value??'').replace(/(^|\s)(@[A-Za-z0-9._-]+)/g,'$1<strong class="message-mention">$2</strong>').replaceAll('\n','<br>');
  const formatDate=value=>value?new Intl.DateTimeFormat(undefined,{dateStyle:'medium',timeStyle:'short'}).format(new Date(value)):'';
  const formatBytes=value=>Number(value)<1024?value+' B':Number(value)<1048576?Math.ceil(Number(value)/1024)+' KB':(Number(value)/1048576).toFixed(1)+' MB';
  const dialog=()=>$('#contextual-thread-dialog');
  const view=()=>({dialog:dialog(),list:$('#thread-conversation-list'),start:$('#thread-conversation-start'),reply:$('#thread-conversation-reply')});
  const historyUrl=(type,key)=>'/app/collaboration?view=ALL&entityType='+encodeURIComponent(type)+'&subjectKey='+encodeURIComponent(key);
  const socketOpen=()=>huddleSocket?.readyState===WebSocket.OPEN;
  const sendHuddle=payload=>{if(!socketOpen())return false;huddleSocket.send(JSON.stringify(payload));return true;};
  async function members(){if(!membersPromise)membersPromise=fetch('/app/collaboration/members',{headers:{Accept:'application/json'}}).then(response=>response.ok?response.json():[]).catch(()=>[]);return membersPromise;}
  function mentionState(field){const before=field.value.slice(0,field.selectionStart??field.value.length),match=before.match(/(^|\s)@([A-Za-z0-9._-]*)$/);return match?{query:match[2].toLowerCase(),start:before.length-match[2].length-1,end:before.length}:null;}
  const composerFor=field=>field.closest('.collaboration-composer,.huddle-composer');
  function closeMentionMenu(field){const menu=composerFor(field)?.querySelector('.mention-menu');if(menu)menu.hidden=true;}
  function positionMentionMenu(field,menu){
    const computed=getComputedStyle(field),mirror=document.createElement('div'),marker=document.createElement('span');
    Object.assign(mirror.style,{position:'fixed',left:'0',top:'0',visibility:'hidden',pointerEvents:'none',boxSizing:'border-box',whiteSpace:'pre-wrap',overflowWrap:'break-word',width:field.offsetWidth+'px',font:computed.font,lineHeight:computed.lineHeight,padding:computed.padding,border:computed.border,letterSpacing:computed.letterSpacing});
    mirror.textContent=field.value.slice(0,field.selectionStart??field.value.length);marker.textContent='\u200b';mirror.append(marker);document.body.append(mirror);
    const composer=composerFor(field),fieldRect=field.getBoundingClientRect(),composerRect=composer.getBoundingClientRect(),lineHeight=parseFloat(computed.lineHeight)||18;
    const caretLeft=fieldRect.left-composerRect.left+marker.offsetLeft-field.scrollLeft,caretTop=fieldRect.top-composerRect.top+marker.offsetTop-field.scrollTop;mirror.remove();
    menu.style.width=Math.min(300,Math.max(230,field.offsetWidth*.58))+'px';const left=Math.max(8,Math.min(caretLeft,composer.clientWidth-menu.offsetWidth-8));menu.style.left=left+'px';menu.style.right='auto';menu.style.bottom='auto';
    const roomBelow=composer.clientHeight-caretTop-lineHeight,top=roomBelow>menu.offsetHeight+8?caretTop+lineHeight+5:Math.max(5,caretTop-menu.offsetHeight-7);menu.style.top=top+'px';
  }
  async function showMentions(field){
    if(state?.messageType==='PRIVATE_NOTE')return closeMentionMenu(field);
    const current=mentionState(field),menu=composerFor(field)?.querySelector('.mention-menu');if(!current||!menu)return closeMentionMenu(field);
    let available=await members();if(field.id==='huddle-message-input'){const huddle=liveHuddles.get(state?.currentHuddleId),emails=new Set((huddle?.participants||[]).map(person=>(person.email||'').toLowerCase()));available=available.filter(member=>emails.has((member.email||'').toLowerCase()));}
    const choices=available.filter(member=>[member.handle,member.name,member.email].some(value=>(value||'').toLowerCase().includes(current.query))).slice(0,6);
    if(!choices.length){menu.hidden=true;return;}
    menu.innerHTML=choices.map(member=>'<button type="button" role="option" data-handle="'+escape(member.handle)+'"><span>'+escape((member.name||'?').trim().charAt(0).toUpperCase())+'</span><div><strong>'+escape(member.name)+'</strong><small><b>@'+escape(member.handle)+'</b> · '+escape(member.email)+'</small></div></button>').join('');menu.hidden=false;positionMentionMenu(field,menu);
    $$('button',menu).forEach(button=>button.addEventListener('mousedown',event=>{event.preventDefault();const match=mentionState(field);if(!match)return;field.setRangeText('@'+button.dataset.handle+' ',match.start,match.end,'end');menu.hidden=true;field.focus();}));
  }
  function wireComposer(field){if(field.dataset.mentionReady)return;field.dataset.mentionReady='true';field.addEventListener('input',()=>showMentions(field));field.addEventListener('click',()=>showMentions(field));field.addEventListener('scroll',()=>showMentions(field));field.addEventListener('keydown',event=>{const menu=composerFor(field)?.querySelector('.mention-menu'),buttons=menu&&!menu.hidden?$$('button',menu):[];if(event.key==='Escape')closeMentionMenu(field);if(!buttons.length||!['ArrowDown','ArrowUp','Enter'].includes(event.key))return;event.preventDefault();let index=buttons.findIndex(button=>button.classList.contains('is-highlighted'));if(event.key==='Enter'&&index>=0){buttons[index].dispatchEvent(new MouseEvent('mousedown',{bubbles:true}));return;}index=event.key==='ArrowUp'?(index<=0?buttons.length-1:index-1):(index+1)%buttons.length;buttons.forEach((button,i)=>button.classList.toggle('is-highlighted',i===index));});field.addEventListener('blur',()=>setTimeout(()=>closeMentionMenu(field),120));}
  function wireEnterToSend(field,form){if(field.dataset.enterSendReady)return;field.dataset.enterSendReady='true';field.addEventListener('keydown',event=>{if(event.defaultPrevented||event.isComposing||event.key!=='Enter'||event.shiftKey)return;event.preventDefault();const submit=$('.primary-button[type="submit"]',form)||$('button[type="submit"]',form);form.requestSubmit(submit);});}
  function wireAttachments(root=document){$$('.thread-attachment-button input',root).forEach(input=>{if(input.dataset.fileReady)return;input.dataset.fileReady='true';input.addEventListener('change',()=>{const label=input.closest('.thread-compose-tools').querySelector('[data-attachment-name]');label.textContent=input.files.length?input.files.length===1?input.files[0].name:input.files.length+' files selected':'No files selected';});});}
  function snapshotFrom(button){
    if(button.dataset.contextSnapshot){try{return JSON.parse(button.dataset.contextSnapshot)}catch(_){}}
    const pairs={identifier:button.dataset.identifier,location:button.dataset.locationLabel,expiration_date:button.dataset.expiration,
      status:button.dataset.status,stock_level:button.dataset.onHand,available:button.dataset.available,price:button.dataset.price,
      marketplace:button.dataset.marketplace,tracking:button.dataset.tracking,package:button.dataset.package};
    return Object.fromEntries(Object.entries(pairs).filter(([,value])=>value!=null&&value!==''));
  }
  function prettyKey(key){return key.replaceAll('_',' ').replace(/\b\w/g,letter=>letter.toUpperCase());}
  function renderSnapshot(review){
    let snapshot={};try{snapshot=JSON.parse(review.contextSnapshot||'{}')}catch(_){}
    const entries=Object.entries(snapshot).filter(([,value])=>value!=null&&value!==''&&typeof value!=='object');if(!entries.length)return '';
    return '<section class="thread-snapshot"><header><span>Snapshot</span><div><strong>When this conversation started</strong><small>Historical record · '+escape(formatDate(review.createdAt))+'</small></div></header><div>'+entries.slice(0,8).map(([key,value])=>'<span><small>'+escape(prettyKey(key))+'</small><strong>'+escape(String(value))+'</strong></span>').join('')+'</div></section>';
  }
  function renderAttachments(attachments){if(!attachments?.length)return '';return '<div class="thread-attachments">'+attachments.map(file=>'<a href="/app/collaboration/attachments/'+encodeURIComponent(file.id)+'"><svg viewBox="0 0 24 24" aria-hidden="true"><path d="m9 12 5.5-5.5a3 3 0 014.2 4.2l-7.4 7.4a5 5 0 01-7.1-7.1l7.1-7.1"/></svg><span><strong>'+escape(file.fileName)+'</strong><small>'+escape(formatBytes(file.sizeBytes))+'</small></span></a>').join('')+'</div>';}
  function renderMessages(conversation,showBack){
    const review=conversation.review,messages=conversation.messages||[],privateMode=conversation.messageType==='PRIVATE_NOTE';
    const empty='<div class="thread-feed-empty"><strong>'+(privateMode?'No private notes yet':'No team messages yet')+'</strong><span>'+(privateMode?'Only you will be able to read notes added here.':'Send the first team reply in this thread.')+'</span></div>';
    return (showBack?'<button class="conversation-back" type="button" data-conversation-back>‹ All conversations for this record</button>':'')+
      renderSnapshot(review)+(privateMode?'<div class="private-note-safety"><svg viewBox="0 0 24 24"><rect x="6" y="10" width="12" height="10" rx="2"/><path d="M9 10V7a3 3 0 016 0v3"/></svg><span><strong>Private to you</strong><small>Teammates, administrators, and mentioned names cannot see these notes.</small></span></div>':'')+
      (messages.length?'<section class="conversation-thread"><div class="conversation-thread-meta"><span>Started '+escape(formatDate(review.createdAt))+' by '+escape(review.requester)+'</span><span>'+escape(review.participants||review.requester)+'</span></div><div class="conversation-messages">'+messages.map(message=>'<article><div class="conversation-avatar">'+escape((message.senderName||message.authorEmail||'?').charAt(0).toUpperCase())+'</div><div><header><strong>'+escape(message.senderName||message.authorEmail)+'</strong><time>'+escape(formatDate(message.createdAt))+'</time></header><p>'+formattedBody(message.body)+'</p>'+renderAttachments(message.attachments)+'</div></article>').join('')+'</div></section>':empty);
  }
  function configureForms(){
    const elements=view(),snapshot=JSON.stringify(state.snapshot||{});if(elements.start){const form=elements.start;form.elements.subjectType.value=state.type;form.elements.subjectKey.value=state.key;form.elements.subjectLabel.value=state.label;form.elements.title.value='Conversation about '+state.label;form.elements.messageType.value=state.messageType;form.elements.contextSnapshot.value=snapshot;form.elements.parentUrl.value=state.parentUrl||location.pathname+location.search;}
    if(elements.reply)elements.reply.elements.messageType.value=state.messageType;
    $$('.collaboration-composer textarea',elements.dialog).forEach(field=>{field.placeholder=state.messageType==='PRIVATE_NOTE'?'Write a private note…':'Write a message… Type @ to mention a teammate';});
    $$('[data-thread-composer-hint]',elements.dialog).forEach(hint=>hint.textContent=state.messageType==='PRIVATE_NOTE'?'Visible only to your signed-in account.':'Type @ to notify a teammate by email.');
  }
  function updateHuddlePresence(){
    const others=huddleOnline.filter(person=>person.id!==huddleSelf?.id),count=others.length,label=count?count+' teammate'+(count===1?'':'s')+' online':'No teammates online';
    $$('[data-huddle-online-count]').forEach(node=>node.textContent=socketOpen()?label:'Reconnect to huddles');
    $$('[data-huddle-live-dot]').forEach(node=>node.classList.toggle('online',socketOpen()&&count>0));
    const presence=$('[data-huddle-presence-label]');if(presence)presence.textContent=socketOpen()?label:'Live huddles are reconnecting…';
    renderSidebarCollaboration();
  }
  function sidebarPeople(){return huddleOnline.filter(person=>person.id!==huddleSelf?.id);}
  function closeSidebarHuddleMenu(){const menu=$('[data-sidebar-huddle-menu]'),trigger=$('[data-sidebar-huddle-trigger]');if(menu)menu.hidden=true;if(trigger)trigger.setAttribute('aria-expanded','false');}
  function playHuddleChime(){
    if(!huddleSoundReady||!window.AudioContext)return;try{const audio=new AudioContext(),gain=audio.createGain();gain.gain.setValueAtTime(.0001,audio.currentTime);gain.gain.exponentialRampToValueAtTime(.035,audio.currentTime+.025);gain.gain.exponentialRampToValueAtTime(.0001,audio.currentTime+.72);gain.connect(audio.destination);[659.25,783.99].forEach((frequency,index)=>{const tone=audio.createOscillator();tone.type='sine';tone.frequency.value=frequency;tone.connect(gain);tone.start(audio.currentTime+index*.16);tone.stop(audio.currentTime+.66);});setTimeout(()=>audio.close(),900);}catch(_){}
  }
  function startHuddleRing(){const root=$('[data-sidebar-collaboration]');root?.classList.add('is-ringing');clearInterval(huddleRingTimer);playHuddleChime();let repeats=0;huddleRingTimer=setInterval(()=>{if(++repeats>=3){clearInterval(huddleRingTimer);huddleRingTimer=null;return;}playHuddleChime();},2600);}
  function stopHuddleRing(){clearInterval(huddleRingTimer);huddleRingTimer=null;$('[data-sidebar-collaboration]')?.classList.remove('is-ringing');}
  function startSidebarHuddle(person){
    closeSidebarHuddleMenu();
    state={type:'PLATFORM',key:'GENERAL',label:'General team huddle',parentUrl:'/app/collaboration',snapshot:{area:'Team workspace'},canCollaborate:true,messageType:'TEAM_CHAT',mode:'QUICK_HUDDLE',currentReviewId:null,currentHuddleId:null};
    setHeader({dataset:{entityType:'PLATFORM',title:'General team huddle',identifier:'Next AI Commerce team',parentUrl:'/app/collaboration'}});
    if(dialog().open)dialog().close();dialog().showModal();selectTab('QUICK_HUDDLE',false);huddleStatus('Calling '+(person.name||person.email)+'…');
    if(!sendHuddle({type:'CREATE',participantIds:[person.id],subjectType:'PLATFORM',subjectKey:'GENERAL',subjectLabel:'General team huddle',parentUrl:'/app/collaboration',contextSnapshot:JSON.stringify({area:'Team workspace'})}))huddleStatus('The live connection was interrupted. Please try again.',true);
  }
  function renderSidebarCollaboration(){
    const root=$('[data-sidebar-collaboration]');if(!root)return;const people=sidebarPeople(),connected=socketOpen(),allowed=connected&&huddleCanChat;
    const count=$('[data-sidebar-collaboration-count]',root),dot=$('[data-sidebar-live-dot]',root),trigger=$('[data-sidebar-huddle-trigger]',root),list=$('[data-sidebar-huddle-people]',root);
    if(count)count.textContent=!connected?'Reconnecting…':people.length?people.length+' teammate'+(people.length===1?'':'s')+' online':'Only you are online';
    dot?.classList.toggle('online',allowed&&people.length>0);if(trigger){trigger.disabled=false;trigger.classList.toggle('has-team',people.length>0);}
    if(!list)return;
    if(!connected)list.innerHTML='<p>Reconnecting to your account…</p>';
    else if(!huddleCanChat)list.innerHTML='<p>Your role can view collaboration, but cannot start a huddle.</p>';
    else if(!people.length)list.innerHTML='<p>No teammates from this account are online right now.</p>';
    else{list.innerHTML=people.map(person=>'<button type="button" data-sidebar-person="'+escape(person.id)+'"><span class="sidebar-person-avatar"><svg viewBox="0 0 24 24" aria-hidden="true"><circle cx="12" cy="8" r="3.25"/><path d="M5.5 19c.7-4 3-6 6.5-6s5.8 2 6.5 6"/></svg><i></i></span><span><strong>'+escape(person.name||person.email)+'</strong><small>Start temporary huddle</small></span><b aria-hidden="true">›</b></button>').join('');$$('[data-sidebar-person]',list).forEach((button,index)=>button.addEventListener('click',()=>startSidebarHuddle(people[index])));}
  }
  function huddleForState(){
    if(!state)return null;return [...liveHuddles.values()].filter(huddle=>huddle.status==='ACTIVE'&&huddle.subjectType===state.type&&huddle.subjectKey===state.key)
      .sort((left,right)=>new Date(right.createdAt)-new Date(left.createdAt))[0]||null;
  }
  function huddleStatus(message,error=false){
    const panel=$('#huddle-status');if(!panel)return;$('#huddle-setup').hidden=true;$('#huddle-live').hidden=true;
    panel.hidden=false;panel.classList.toggle('error',error);panel.innerHTML=error?escape(message):'<div><span class="conversation-loading">'+escape(message)+'</span></div>';
  }
  function resetHuddlePanels(){const status=$('#huddle-status');if(status){status.hidden=true;status.classList.remove('error');}}
  function renderHuddleSetup(){
    resetHuddlePanels();const setup=$('#huddle-setup'),live=$('#huddle-live');setup.hidden=false;live.hidden=true;state.currentHuddleId=null;updateHuddlePresence();
    const root=$('#huddle-online-people'),people=huddleOnline.filter(person=>person.id!==huddleSelf?.id);root.innerHTML='';
    if(!socketOpen())root.innerHTML='<div class="huddle-empty-online">Reconnecting to the live huddle service…</div>';
    else if(!huddleCanChat||!state.canCollaborate)root.innerHTML='<div class="huddle-empty-online">Your role can view collaboration, but cannot start a live huddle.</div>';
    else if(!people.length)root.innerHTML='<div class="huddle-empty-online">Your teammates are offline right now. You can use Team Chat and @mention them instead.</div>';
    else people.forEach(person=>{const label=document.createElement('label');label.className='huddle-person';label.title='Invite '+(person.name||person.email);label.innerHTML='<input type="checkbox" value="'+escape(person.id)+'"><span class="huddle-person-avatar"><svg viewBox="0 0 24 24" aria-hidden="true"><circle cx="12" cy="8" r="3.25"/><path d="M5.5 19c.7-4 3-6 6.5-6s5.8 2 6.5 6"/></svg><i></i></span><div><strong>'+escape(person.name||person.email)+'</strong><small>Online · invite to huddle</small></div><b aria-hidden="true">✓</b>';root.append(label);const input=$('input',label);input.addEventListener('change',()=>{const selected=$$('input:checked',root);if(selected.length>2){input.checked=false;return;}label.classList.toggle('selected',input.checked);$('#huddle-start-button').disabled=!selected.length;});});
    $('#huddle-start-button').disabled=true;
  }
  function renderHuddleMessages(huddle){
    const root=$('#huddle-messages'),messages=huddle.messages||[];root.innerHTML=messages.length?'':'<div class="huddle-welcome"><svg viewBox="0 0 24 24"><path d="m13 2-8 12h7l-1 8 8-12h-7l1-8Z"/></svg><strong>You are together live</strong><span>Messages remain temporary until someone deliberately saves the huddle.</span></div>';
    messages.forEach(message=>{const mine=message.senderId===huddleSelf?.id,article=document.createElement('article');article.className='huddle-message'+(mine?' mine':'');article.innerHTML='<span class="huddle-message-avatar">'+escape((message.senderName||'?').charAt(0).toUpperCase())+'</span><div class="huddle-message-body"><header><strong>'+escape(mine?'You':message.senderName)+'</strong><time>'+escape(formatDate(message.createdAt))+'</time></header><p>'+formattedBody(message.body)+'</p></div>';root.append(article);});root.scrollTop=root.scrollHeight;
  }
  function renderLiveHuddle(huddle){
    resetHuddlePanels();state.currentHuddleId=huddle.id;hideHuddleDock(huddle.id);$('#huddle-setup').hidden=true;$('#huddle-live').hidden=false;$('#huddle-live-title').textContent=huddle.subjectLabel||'Quick huddle';
    const joined=new Set(huddle.joinedParticipantIds||[]),others=(huddle.participants||[]).filter(person=>person.id!==huddleSelf?.id),live=others.filter(person=>joined.has(person.id)).map(person=>person.name||person.email),waiting=others.filter(person=>!joined.has(person.id)).map(person=>person.name||person.email),parts=[];if(live.length)parts.push('Live with '+live.join(' and '));if(waiting.length)parts.push('Waiting for '+waiting.join(' and ')+' to join');$('#huddle-live-people').textContent=parts.join(' · ')||'Waiting for a teammate';renderHuddleMessages(huddle);$('#huddle-message-input').focus();
  }
  function renderHuddlePanel(){
    if(!state)return;const active=state.currentHuddleId?liveHuddles.get(state.currentHuddleId):huddleForState();active?renderLiveHuddle(active):renderHuddleSetup();
  }
  function closeTemporaryHuddle(){
    const huddle=liveHuddles.get(state?.currentHuddleId);if(!huddle){dialog().close();return;}const owner=huddle.startedBy?.id===huddleSelf?.id;
    sendHuddle({type:owner?'END':'LEAVE',huddleId:huddle.id});liveHuddles.delete(huddle.id);hideHuddleInvitation(huddle.id);hideHuddleDock(huddle.id);dialog().close();
  }
  function saveHuddle(closeConversation){
    const id=state?.currentHuddleId;if(!id)return;huddleStatus(closeConversation?'Saving this huddle as completed…':'Saving this huddle for follow-up…');
    if(!sendHuddle({type:'SAVE',huddleId:id,closeConversation}))huddleStatus('The live connection was interrupted. Reconnect before saving.',true);
  }
  function startHuddle(){
    const selected=$$('#huddle-online-people input:checked').map(input=>input.value);if(!selected.length)return;
    huddleStatus('Opening a private live room for your team…');if(!sendHuddle({type:'CREATE',participantIds:selected,subjectType:state.type,subjectKey:state.key,subjectLabel:state.label,parentUrl:state.parentUrl||location.pathname+location.search,contextSnapshot:JSON.stringify(state.snapshot||{})}))huddleStatus('The live connection was interrupted. Please try again.',true);
  }
  function mountFloatingHuddle(node){if(!node)return;const sidebarSlot=$('[data-sidebar-huddle-live-slot]');if(sidebarSlot&&!dialog()?.open){sidebarSlot.append(node);node.classList.add('is-sidebar-mounted');return;}node.classList.remove('is-sidebar-mounted');const openDialogs=$$('dialog[open]');(openDialogs.at(-1)||document.body).append(node);}
  function showHuddleInvitation(huddle){
    const invitation=$('#huddle-invitation');if(!invitation||huddle.startedBy?.id===huddleSelf?.id||huddle.status!=='ACTIVE')return;mountFloatingHuddle(invitation);const joined=(huddle.joinedParticipantIds||[]).includes(huddleSelf?.id);invitation.dataset.huddleId=huddle.id;invitation.dataset.joined=String(joined);$('[data-huddle-invite-title]',invitation).textContent=joined?(huddle.startedBy?.name||'A teammate')+' is still live':(huddle.startedBy?.name||'A teammate')+' is calling';$('[data-huddle-invite-context]',invitation).textContent=huddle.subjectLabel||'General team huddle';$('[data-huddle-join]',invitation).textContent=joined?'Resume':'Answer';invitation.hidden=false;startHuddleRing();
  }
  function hideHuddleInvitation(id){const invitation=$('#huddle-invitation');if(invitation&&!invitation.hidden&&(!id||invitation.dataset.huddleId===id)){invitation.hidden=true;stopHuddleRing();}}
  function showHuddleDock(huddle,message){
    const dock=$('#huddle-dock'),joined=(huddle.joinedParticipantIds||[]).includes(huddleSelf?.id);if(!dock||!joined||huddle.status!=='ACTIVE')return;mountFloatingHuddle(dock);huddleUnread++;dock.dataset.huddleId=huddle.id;$('[data-huddle-dock-title]',dock).textContent=message&&message.senderId!==huddleSelf?.id?(message.senderName||'A teammate')+' sent a live message':huddle.subjectLabel||'Quick huddle';$('[data-huddle-dock-context]',dock).textContent=message&&message.senderId!==huddleSelf?.id?'Open the huddle to reply':'Live conversation ready to resume';$('[data-huddle-dock-count]',dock).textContent=String(huddleUnread);dock.hidden=false;
  }
  function hideHuddleDock(id){const dock=$('#huddle-dock');if(dock&&!dock.hidden&&(!id||dock.dataset.huddleId===id)){dock.hidden=true;huddleUnread=0;}}
  function declineHuddleInvitation(){const invitation=$('#huddle-invitation'),id=invitation?.dataset.huddleId,huddle=id?liveHuddles.get(id):null;if(huddle)sendHuddle({type:'LEAVE',huddleId:id});if(id)liveHuddles.delete(id);hideHuddleInvitation(id);}
  function openLiveHuddle(huddle){
    state={type:huddle.subjectType,key:huddle.subjectKey,label:huddle.subjectLabel,parentUrl:huddle.parentUrl||'',snapshot:{},canCollaborate:true,messageType:'TEAM_CHAT',mode:'QUICK_HUDDLE',currentReviewId:null,currentHuddleId:huddle.id};
    const virtual={dataset:{entityType:huddle.subjectType,entityId:huddle.subjectKey,title:huddle.subjectLabel,identifier:huddle.subjectKey,parentUrl:huddle.parentUrl||''}};setHeader(virtual);hideHuddleInvitation(huddle.id);hideHuddleDock(huddle.id);if(dialog().open)dialog().close();dialog().showModal();selectTab('QUICK_HUDDLE',false);
  }
  function handleHuddleEvent(event){
    if(event.type==='WELCOME'){huddleSelf=event.self;huddleCanChat=Boolean(event.canHuddle);huddleOnline=event.online||[];const existing=event.huddles||[];liveHuddles.clear();existing.forEach(huddle=>liveHuddles.set(huddle.id,huddle));updateHuddlePresence();if(state?.mode==='QUICK_HUDDLE')renderHuddlePanel();const invitation=existing.find(huddle=>huddle.status==='ACTIVE'&&huddle.startedBy?.id!==huddleSelf?.id&&!(huddle.joinedParticipantIds||[]).includes(huddleSelf?.id));if(invitation)showHuddleInvitation(invitation);else{const joined=existing.find(huddle=>huddle.status==='ACTIVE'&&(huddle.joinedParticipantIds||[]).includes(huddleSelf?.id));if(joined&&(!dialog().open||state?.currentHuddleId!==joined.id))showHuddleDock(joined);}return;}
    if(event.type==='PRESENCE'){huddleOnline=event.online||[];updateHuddlePresence();if(state?.mode==='QUICK_HUDDLE'&&!state.currentHuddleId)renderHuddleSetup();return;}
    if(event.type==='ERROR'){if(state?.mode==='QUICK_HUDDLE')huddleStatus(event.message||'The huddle action could not be completed.',true);return;}
    const huddle=event.huddle;if(!huddle)return;
    if(event.type==='HUDDLE_STARTED'||event.type==='HUDDLE_UPDATED'){const previous=liveHuddles.get(huddle.id),latest=(huddle.messages||[]).at(-1),incoming=event.type==='HUDDLE_UPDATED'&&(huddle.messages||[]).length>(previous?.messages||[]).length&&latest?.senderId!==huddleSelf?.id;liveHuddles.set(huddle.id,huddle);if(event.type==='HUDDLE_STARTED')showHuddleInvitation(huddle);const visible=dialog().open&&state?.mode==='QUICK_HUDDLE'&&state.currentHuddleId===huddle.id;if(state?.mode==='QUICK_HUDDLE'&&(state.currentHuddleId===huddle.id||(state.type===huddle.subjectType&&state.key===huddle.subjectKey)))renderLiveHuddle(huddle);if(incoming&&!visible)showHuddleDock(huddle,latest);return;}
    if(event.type==='HUDDLE_ENDED'){liveHuddles.delete(huddle.id);hideHuddleInvitation(huddle.id);hideHuddleDock(huddle.id);if(state?.currentHuddleId===huddle.id){huddleStatus(event.reason||'This temporary huddle has ended. Nothing was saved.');setTimeout(()=>dialog().open&&dialog().close(),900);}return;}
    if(event.type==='HUDDLE_SAVED'){liveHuddles.delete(huddle.id);hideHuddleInvitation(huddle.id);hideHuddleDock(huddle.id);if(!event.huddle?.status)return;if(state?.currentHuddleId===huddle.id){huddleStatus('Huddle saved for follow-up in Team Chat.');if(event.reviewId)markRecordConversationActive(state.type,state.key);setTimeout(()=>dialog().open&&dialog().close(),1200);}}
  }
  function decorateRecordConversationButton(button){
    if(!button)return;const total=Number(button.dataset.totalCount||0),label=button.dataset.title||button.dataset.identifier||'this record';button.classList.toggle('has-conversation',total>0);button.title=total>1?'Choose from '+total+' conversations':total===1?'Open conversation':'Start collaboration';button.setAttribute('aria-label',total>1?'Choose a conversation for '+label:total===1?'Open collaboration for '+label:'Start collaboration for '+label);
    const active=Number(button.dataset.activeCount||0);button.classList.toggle('closed-history',total>0&&active===0);
    let badge=button.querySelector('b');if(active>0){if(!badge){badge=document.createElement('b');button.append(badge)}badge.textContent=String(active)}else badge?.remove();
    if(total>0&&active===0)button.title='View closed conversation history';
  }
  async function markRecordConversationActive(type,key){
    const nodes=$$('[data-entity-type="'+CSS.escape(type)+'"][data-entity-id="'+CSS.escape(key)+'"]');
    try{
      const params=new URLSearchParams({entityType:type,entityId:key});
      const response=await fetch('/app/collaboration/summaries?'+params,{headers:{Accept:'application/json'},cache:'no-store'});
      if(!response.ok)throw new Error('Summary unavailable');
      const summary=(await response.json())[key]||{};
      nodes.forEach(node=>{const button=node.matches('.collaboration-row-button')?node:$('.collaboration-row-button',node);if(!button)return;
        ['activeCount','totalCount','closedCount'].forEach(field=>button.dataset[field]=String(summary[field]||0));
        decorateRecordConversationButton(button);
      });
    }catch(_){nodes.forEach(node=>{const button=node.matches('.collaboration-row-button')?node:$('.collaboration-row-button',node);if(button)button.title='Saved. Refresh this table to update conversation counts.'})}
  }
  function connectHuddles(){
    if(!('WebSocket'in window)||huddleSocket?.readyState===WebSocket.OPEN||huddleSocket?.readyState===WebSocket.CONNECTING)return;clearTimeout(huddleReconnect);const protocol=location.protocol==='https:'?'wss:':'ws:';huddleSocket=new WebSocket(protocol+'//'+location.host+'/ws/huddles');
    huddleSocket.addEventListener('open',updateHuddlePresence);huddleSocket.addEventListener('message',message=>{try{handleHuddleEvent(JSON.parse(message.data))}catch(error){console.warn('A live huddle update could not be displayed.',error);}});huddleSocket.addEventListener('close',event=>{huddleOnline=[];updateHuddlePresence();if(event.code!==1003&&event.code!==1008)huddleReconnect=setTimeout(connectHuddles,2000);});huddleSocket.addEventListener('error',updateHuddlePresence);
  }
  function selectTab(type,reload=true){
    if(!state)return;const huddleMode=type==='QUICK_HUDDLE';state.mode=huddleMode?'QUICK_HUDDLE':'PERSISTENT';if(!huddleMode)state.messageType=type==='PRIVATE_NOTE'?'PRIVATE_NOTE':'TEAM_CHAT';
    $$('[data-thread-tab]',dialog()).forEach(button=>button.setAttribute('aria-selected',String(button.dataset.threadTab===(huddleMode?'QUICK_HUDDLE':state.messageType))));$('#thread-persistent-panel').hidden=huddleMode;$('#quick-huddle-panel').hidden=!huddleMode;
    if(huddleMode){renderHuddlePanel();return;}configureForms();
    if(!reload)return;if(state.currentReviewId)loadConversation(state.currentReviewId,state.showBack);else if(state.active?.length)loadConversation(state.active[0].id,false);else showNewConversation();
  }
  function showNewConversation(){
    const elements=view();elements.reply.hidden=true;elements.start.hidden=!state.canCollaborate;elements.list.innerHTML='<div class="conversation-empty"><svg viewBox="0 0 24 24"><path d="M5 5.5h14v9H9l-4 4v-13Z"/></svg><strong>'+(state.messageType==='PRIVATE_NOTE'?'Add a private note':'Start a team conversation')+'</strong><span>'+(state.messageType==='PRIVATE_NOTE'?'Only your signed-in user can read it.':'Keep the discussion attached to this exact record.')+'</span>'+(state.closedCount?'<a class="conversation-history-link" href="'+escape(state.historyUrl)+'">View '+state.closedCount+' closed conversation'+(state.closedCount===1?'':'s')+'</a>':'')+'</div>';configureForms();if(!elements.start.hidden)$('textarea',elements.start).focus();
  }
  function showPicker(){
    const elements=view(),all=[...(state.threads||[])].sort((left,right)=>Date.parse(right.createdAt)-Date.parse(left.createdAt));elements.start.hidden=true;elements.reply.hidden=true;state.currentReviewId=null;
    elements.list.innerHTML='<section class="conversation-picker"><header><strong>'+all.length+' conversation'+(all.length===1?'':'s')+' for this record</strong><span>Choose by date, status, and participants.</span></header><div>'+all.map(review=>'<button type="button" data-conversation-id="'+review.id+'"><span class="conversation-picker-icon '+(review.status==='CLOSED'?'closed':'active')+'"><svg viewBox="0 0 24 24"><path d="M5 5.5h14v9H9l-4 4v-13Z"/></svg></span><span><strong>'+escape(formatDate(review.createdAt))+'</strong><small>'+escape(review.status==='CLOSED'?'Closed':'Active')+' · '+escape(review.participants||review.requester)+' · '+review.messageCount+' item'+(review.messageCount===1?'':'s')+'</small></span><b>›</b></button>').join('')+'</div><footer>'+(state.canCollaborate?'<button type="button" class="conversation-new-button" data-new-conversation>＋ New conversation</button>':'')+'<a class="conversation-history-link" href="'+escape(state.historyUrl)+'">Open filtered history</a></footer></section>';
    $$('[data-conversation-id]',elements.list).forEach(button=>button.addEventListener('click',()=>loadConversation(button.dataset.conversationId,true)));$('[data-new-conversation]',elements.list)?.addEventListener('click',showNewConversation);
  }
  async function loadConversation(reviewId,showBack=false){
    const elements=view();state.currentReviewId=reviewId;state.showBack=showBack;elements.start.hidden=true;elements.reply.hidden=true;elements.list.innerHTML='<div class="conversation-loading">Loading conversation…</div>';
    try{const response=await fetch('/app/collaboration/reviews/'+encodeURIComponent(reviewId)+'?messageType='+state.messageType,{headers:{Accept:'application/json'}});if(!response.ok)throw new Error();const conversation=await response.json();state.currentReview=conversation.review;setParentLink(conversation.review.parentUrl,conversation.review.subjectType);elements.list.innerHTML=renderMessages(conversation,showBack);elements.reply.action='/app/collaboration/reviews/'+conversation.review.id+'/reply';elements.reply.hidden=!state.canCollaborate||conversation.review.status==='CLOSED';configureForms();$('[data-conversation-back]',elements.list)?.addEventListener('click',showPicker);if(!elements.reply.hidden)$('textarea',elements.reply).focus();elements.list.scrollTop=elements.list.scrollHeight;}catch(_){elements.list.innerHTML='<div class="history-error">This conversation could not be loaded. Please try again.</div>';}
  }
  async function loadSubject(forcePicker){
    const elements=view();elements.list.innerHTML='<div class="conversation-loading">Loading conversations…</div>';elements.start.hidden=true;elements.reply.hidden=true;
    try{const response=await fetch('/app/collaboration/subject?subjectType='+encodeURIComponent(state.type)+'&subjectKey='+encodeURIComponent(state.key),{headers:{Accept:'application/json'}});if(!response.ok)throw new Error();const conversations=await response.json(),threads=conversations.map(item=>item.review),active=threads.filter(review=>review.status==='ACTIVE');Object.assign(state,{threads,active,closedCount:threads.length-active.length,historyUrl:historyUrl(state.type,state.key)});if((forcePicker||threads.length>1)&&threads.length)showPicker();else if(active.length)loadConversation(active[0].id,false);else if(threads.length===1)loadConversation(threads[0].id,false);else showNewConversation();}catch(_){elements.list.innerHTML='<div class="history-error">These conversations could not be loaded. Please try again.</div>';}
  }
  function setHeader(button){
    $('#thread-record-type').textContent=(button.dataset.entityType||button.dataset.type||'Record').replaceAll('_',' ')+' collaboration';$('#thread-record-title').textContent=button.dataset.title||button.dataset.product||'Record conversation';
    const context=[button.dataset.identifier,button.dataset.locationLabel].filter(Boolean).join(' · ');$('#thread-record-context').textContent=context||'Conversation attached to this record';
    const badges=$('#thread-record-badges');badges.innerHTML='';[['expiration',button.dataset.expiration?'Expires '+button.dataset.expiration:null],['status',button.dataset.status],['marketplace',button.dataset.marketplace]].forEach(([kind,text])=>{if(!text)return;const badge=document.createElement('span');badge.className=kind;badge.textContent=text;badges.append(badge);});
    const visual=$('#thread-record-visual'),image=button.dataset.image;
    visual.style.backgroundImage=image?'url("'+String(image).replaceAll('"','%22')+'")':'';
    visual.classList.toggle('has-image',Boolean(image));
    setParentLink(button.dataset.parentUrl,(button.dataset.entityType||button.dataset.type||'record'));
  }
  function setParentLink(url,type){const link=$('#thread-parent-link');if(!link)return;const safe=typeof url==='string'&&url.startsWith('/app')&&!url.startsWith('//');link.hidden=!safe;if(safe){link.href=url;link.querySelector('span').textContent='Open '+String(type||'record').toLowerCase().replaceAll('_',' ');}}
  window.openRecordCollaboration=(button,forcePicker=false)=>{
    const type=(button.dataset.entityType||button.dataset.type||'RECORD').toUpperCase(),key=button.dataset.entityId||button.dataset.key;
    const chooseConversation=forcePicker||Number(button.dataset.totalCount||0)>1;
    state={type,key,label:button.dataset.title||button.dataset.product||button.dataset.identifier||'Record',parentUrl:button.dataset.parentUrl||location.pathname+location.search,snapshot:snapshotFrom(button),canCollaborate:button.dataset.canCollaborate!=='false',messageType:'TEAM_CHAT',mode:'PERSISTENT',currentReviewId:null,currentHuddleId:null};setHeader(button);selectTab('TEAM_CHAT',false);dialog().showModal();loadSubject(chooseConversation);
  };
  window.prepareRecordCollaboration=decorateRecordConversationButton;
  window.openInventoryConversation=button=>window.openRecordCollaboration(button,false);
  window.openCollaborationReview=button=>{state={type:button.dataset.subjectType,key:button.dataset.subjectKey,label:button.dataset.subjectLabel,parentUrl:button.dataset.parentUrl||'',snapshot:{},canCollaborate:button.dataset.canCollaborate!=='false',messageType:'TEAM_CHAT',mode:'PERSISTENT',currentReviewId:button.dataset.reviewId,currentHuddleId:null};setHeader(button);selectTab(button.dataset.messageType||'TEAM_CHAT',false);dialog().showModal();loadConversation(button.dataset.reviewId,false);};
  window.openGeneralHuddle=button=>{state={type:'PLATFORM',key:button?.dataset.entityId||'GENERAL',label:button?.dataset.title||'General team huddle',parentUrl:'/app/collaboration',snapshot:{area:'Team workspace'},canCollaborate:button?.dataset.canCollaborate!=='false',messageType:'TEAM_CHAT',mode:'QUICK_HUDDLE',currentReviewId:null,currentHuddleId:null};setHeader({dataset:{entityType:'PLATFORM',title:state.label,identifier:'Next AI Commerce team'}});dialog().showModal();selectTab('QUICK_HUDDLE',false);};
  window.filterCollaborationRows=value=>{const query=(value||'').trim().toLowerCase(),rows=$$('.collaboration-open-row'),visible=rows.reduce((count,row)=>{const show=!query||(row.dataset.search||'').includes(query);row.hidden=!show;return count+(show?1:0);},0),empty=$('.collaboration-search-empty');if(empty)empty.hidden=!query||visible>0;const total=$('.collaboration-card .table-footer span');if(total)total.textContent=query?'Showing '+visible+' matching conversation'+(visible===1?'':'s'):'Showing '+rows.length+' conversation'+(rows.length===1?'':'s');};
  function boot(){
    document.addEventListener('pointerdown',()=>{huddleSoundReady=true;},{once:true,capture:true});
    $$('.collaboration-row-button').forEach(decorateRecordConversationButton);
    $$('[data-collaboration-composer]').forEach(field=>{wireComposer(field);wireEnterToSend(field,field.closest('form'));});wireAttachments();$$('[data-thread-tab]').forEach(button=>button.addEventListener('click',()=>selectTab(button.dataset.threadTab)));$$('[data-open-huddle]').forEach(button=>button.addEventListener('click',()=>selectTab('QUICK_HUDDLE')));
    $$('.collaboration-composer').forEach(form=>form.addEventListener('submit',event=>{const field=$('textarea',form),files=$('input[type=file]',form)?.files;if(!event.submitter?.name&&!field.value.trim()&&!files?.length){event.preventDefault();field.focus();}}));
    $('#huddle-start-button')?.addEventListener('click',startHuddle);const huddleForm=$('#huddle-message-form'),huddleField=$('#huddle-message-input');if(huddleField)wireComposer(huddleField);huddleForm?.addEventListener('submit',event=>{event.preventDefault();const body=huddleField.value.trim();if(!body||!state?.currentHuddleId)return;if(sendHuddle({type:'MESSAGE',huddleId:state.currentHuddleId,body})){huddleField.value='';huddleField.focus();closeMentionMenu(huddleField);}else huddleStatus('Your message is still here. Reconnect before sending it.',true);});huddleField?.addEventListener('keydown',event=>{if(event.defaultPrevented||event.isComposing||event.key!=='Enter'||event.shiftKey)return;event.preventDefault();huddleForm.requestSubmit();});
    $('#huddle-finish-button')?.addEventListener('click',closeTemporaryHuddle);$('#huddle-leave-button')?.addEventListener('click',closeTemporaryHuddle);$('#huddle-save-followup')?.addEventListener('click',()=>saveHuddle(false));
    $('[data-huddle-join]')?.addEventListener('click',()=>{const invitation=$('#huddle-invitation'),huddle=liveHuddles.get(invitation.dataset.huddleId);if(huddle){if(invitation.dataset.joined!=='true')sendHuddle({type:'JOIN',huddleId:huddle.id});openLiveHuddle(huddle);}});$('[data-huddle-decline]')?.addEventListener('click',declineHuddleInvitation);$('[data-huddle-resume]')?.addEventListener('click',()=>{const dock=$('#huddle-dock'),huddle=liveHuddles.get(dock.dataset.huddleId);if(huddle)openLiveHuddle(huddle);});
    $$('[data-thread-dialog-close]').forEach(button=>button.addEventListener('click',()=>state?.mode==='QUICK_HUDDLE'&&liveHuddles.get(state.currentHuddleId)?.status==='ACTIVE'?closeTemporaryHuddle():dialog().close()));
    dialog()?.addEventListener('cancel',event=>{if(state?.mode==='QUICK_HUDDLE'&&liveHuddles.get(state.currentHuddleId)?.status==='ACTIVE'){event.preventDefault();closeTemporaryHuddle();}});dialog()?.addEventListener('click',event=>{if(event.target!==event.currentTarget)return;if(state?.mode==='QUICK_HUDDLE'&&liveHuddles.get(state.currentHuddleId)?.status==='ACTIVE')closeTemporaryHuddle();else event.currentTarget.close();});
    const auto=$('[data-thread-autopen="true"]');if(auto)setTimeout(()=>window.openCollaborationReview(auto),80);
    const sidebarTrigger=$('[data-sidebar-huddle-trigger]');sidebarTrigger?.addEventListener('click',event=>{event.stopPropagation();const menu=$('[data-sidebar-huddle-menu]'),open=menu.hidden;menu.hidden=!open;sidebarTrigger.setAttribute('aria-expanded',String(open));});
    document.addEventListener('click',event=>{if(!event.target.closest('[data-sidebar-collaboration]'))closeSidebarHuddleMenu();});document.addEventListener('keydown',event=>{if(event.key==='Escape')closeSidebarHuddleMenu();});renderSidebarCollaboration();
    connectHuddles();setInterval(()=>sendHuddle({type:'PING'}),25000);document.addEventListener('visibilitychange',()=>{if(!document.hidden)connectHuddles();});
  }
  document.readyState==='loading'?document.addEventListener('DOMContentLoaded',boot):boot();
})();
