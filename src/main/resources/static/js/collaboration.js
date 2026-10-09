(()=>{
  if(window.NextAiCollaborationReady)return;
  window.NextAiCollaborationReady=true;
  let membersPromise,state=null,huddleSocket=null,huddleReconnect=null,lastPong=Date.now(),summariesRefreshing=false;
  const $=(selector,scope=document)=>scope.querySelector(selector);
  const $$=(selector,scope=document)=>[...scope.querySelectorAll(selector)];
  const escape=value=>{const node=document.createElement('span');node.textContent=value??'';return node.innerHTML};
  const formattedBody=value=>escape(value??'').replace(/(^|\s)(@[A-Za-z0-9._-]+)/g,'$1<strong class="message-mention">$2</strong>').replaceAll('\n','<br>');
  const formatDate=value=>value?new Intl.DateTimeFormat('en-US',{month:'2-digit',day:'2-digit',year:'2-digit',hour:'numeric',minute:'2-digit'}).format(new Date(value)):'';
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
    const available=await members();
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
      marketplace:button.dataset.marketplace,tracking:button.dataset.tracking,package:button.dataset.package,image:button.dataset.image};
    return Object.fromEntries(Object.entries(pairs).filter(([,value])=>value!=null&&value!==''));
  }
  function renderAttachments(attachments){if(!attachments?.length)return '';return '<div class="thread-attachments">'+attachments.map(file=>'<a href="/app/collaboration/attachments/'+encodeURIComponent(file.id)+'"><svg viewBox="0 0 24 24" aria-hidden="true"><path d="m9 12 5.5-5.5a3 3 0 014.2 4.2l-7.4 7.4a5 5 0 01-7.1-7.1l7.1-7.1"/></svg><span><strong>'+escape(file.fileName)+'</strong><small>'+escape(formatBytes(file.sizeBytes))+'</small></span></a>').join('')+'</div>';}
  function renderMessages(conversation,showBack){
    const review=conversation.review,messages=conversation.messages||[],privateMode=conversation.messageType==='PRIVATE_NOTE';
    const empty='<div class="thread-feed-empty"><strong>'+(privateMode?'No private notes yet':'No team messages yet')+'</strong><span>'+(privateMode?'Only you will be able to read notes added here.':'Send the first team reply in this thread.')+'</span></div>';
    return (showBack?'<button class="conversation-back" type="button" data-conversation-back>‹ All conversations for this record</button>':'')+
      (privateMode?'<div class="private-note-safety"><svg viewBox="0 0 24 24"><rect x="6" y="10" width="12" height="10" rx="2"/><path d="M9 10V7a3 3 0 016 0v3"/></svg><span><strong>Private to you</strong><small>Only you can see these notes.</small></span></div>':'')+
      (messages.length?'<section class="conversation-thread"><div class="conversation-thread-meta"><span>Started '+escape(formatDate(review.createdAt))+' by '+escape(review.requester)+'</span><span>'+escape(review.participants||review.requester)+'</span></div><div class="conversation-messages">'+messages.map(message=>'<article><div class="conversation-avatar">'+escape((message.senderName||message.authorEmail||'?').charAt(0).toUpperCase())+'</div><div><header><strong>'+escape(message.senderName||message.authorEmail)+'</strong><time>'+escape(formatDate(message.createdAt))+'</time></header><p>'+formattedBody(message.body)+'</p>'+renderAttachments(message.attachments)+'</div></article>').join('')+'</div></section>':empty);
  }
  function configureForms(){
    if(dialog())dialog().dataset.visibility=state.messageType;
    $$('.chat-add-person',dialog()).forEach(button=>button.hidden=state.messageType==='PRIVATE_NOTE');
    const management=$('#thread-management');if(management)management.hidden=!state.currentReview||!state.canCollaborate||state.currentReview.status!=='ACTIVE'||state.messageType==='PRIVATE_NOTE';
    const elements=view(),snapshot=JSON.stringify(state.snapshot||{});if(elements.start){const form=elements.start;form.elements.subjectType.value=state.type;form.elements.subjectKey.value=state.key;form.elements.subjectLabel.value=state.label;form.elements.title.value='Conversation about '+state.label;form.elements.messageType.value=state.messageType;form.elements.contextSnapshot.value=snapshot;form.elements.parentUrl.value=state.parentUrl||location.pathname+location.search;}
    if(elements.reply)elements.reply.elements.messageType.value=state.messageType;
    $$('.collaboration-composer textarea',elements.dialog).forEach(field=>{field.placeholder=state.messageType==='PRIVATE_NOTE'?'Write a private note…':'Write a message… Type @ to mention a teammate';});
    $$('[data-thread-composer-hint]',elements.dialog).forEach(hint=>hint.textContent=state.messageType==='PRIVATE_NOTE'?'Visible only to your signed-in account.':'Type @ to notify a teammate by email.');
  }
  function handleHuddleEvent(event){lastPong=Date.now();window.LiveHuddleUI?.event(event,sendHuddle);}
  function updateAssignment(review){
    const current=($('#thread-management')?.dataset.currentEmail||'').toLowerCase();
    const emails=(review.assigneeEmail||'').toLowerCase().split(',').map(x=>x.trim());
    $$('tr[data-review-id]').filter(row=>row.dataset.reviewId===String(review.id)).forEach(row=>{
      const trigger=$('[data-assign-review]',row),icon=$('[data-conversation-count]',row);
      if(trigger){trigger.dataset.assignedEmails=review.assigneeEmail||'';$('span',trigger).textContent=review.assigneeName||'Unassigned';}
      if(icon){icon.dataset.assignedToMe=String(emails.includes(current)&&review.status==='ACTIVE');icon.dataset.conversationCount=review.messageCount;icon.dataset.unreadMessageCount=review.unreadMessageCount||0;decorateRecordConversationButton(icon);}
      const count=$('[data-message-total]',row);if(count)count.textContent=review.messageCount+' messages';
    });
  }
  async function saveAssignment(id,people){
    const body=new FormData();$$('input[type=hidden]',view().reply).filter(input=>input.name.includes('csrf')).forEach(input=>body.set(input.name,input.value));
    people.forEach(person=>body.append('people',person));
    const response=await fetch('/app/collaboration/reviews/'+encodeURIComponent(id)+'/assignees',{method:'POST',body,headers:{Accept:'application/json'}});
    if(!response.ok)throw new Error('Assignment could not be saved. Please try again.');
    const review=await response.json();updateAssignment(review);if(state?.type&&state?.key)await markRecordConversationActive(state.type,state.key);return review;
  }
  window.assignConversationToMe=async(id,email,button)=>{
    button.disabled=true;
    try{
      const response=await fetch('/app/collaboration/reviews/'+encodeURIComponent(id),{headers:{Accept:'application/json'}});
      if(!response.ok)throw new Error('Conversation could not be loaded.');
      const conversation=await response.json(),team=await members(),emails=new Set((conversation.review.assigneeEmail||'').toLowerCase().split(',').map(x=>x.trim()));
      emails.add((email||'').toLowerCase());
      if(!team.some(person=>person.email.toLowerCase()===(email||'').toLowerCase()))throw new Error('Your account is not available for assignment.');
      await saveAssignment(id,team.filter(person=>emails.has(person.email.toLowerCase())).map(person=>person.id));
      if(state?.currentReviewId===id)await loadConversation(id);
      button.closest('details')?.removeAttribute('open');
    }catch(error){alert(error.message);}finally{button.disabled=false;}
  };
  async function renderManagement(review){
    const root=$('#thread-management');if(!root)return;updateAssignment(review);
    const assigned=(review.assigneeEmail||'').toLowerCase().split(',').map(value=>value.trim()).includes((root.dataset.currentEmail||'').toLowerCase());
    const statusIcon=$('#thread-status-icon');if(statusIcon){statusIcon.src=window.NextAiIcons.source(review.status==='ACTIVE'&&assigned?'collaboration-assigned-red':review.messageCount>0?'collaboration-blue':'collaboration-no-message-gray');statusIcon.alt=assigned?'Assigned to you':review.messageCount>0?'Active conversation':'No messages';}
    window.CollaborationTaskDates?.prepare(review);
    root.hidden=!state.canCollaborate||review.status!=='ACTIVE'||state.messageType==='PRIVATE_NOTE';if(root.hidden)return;
    const label=$('[data-thread-assignees]',root);label.textContent=review.assigneeName||'Unassigned';label.classList.toggle('unassigned',!review.assigneeEmail);
    $('[data-thread-assign-self]',root).onclick=event=>window.assignConversationToMe(review.id,root.dataset.currentEmail,event.currentTarget);
    const options=$('[data-thread-assignment-options]',root);options.replaceChildren();
    const emails=new Set((review.assigneeEmail||'').toLowerCase().split(',').map(x=>x.trim()));
    for(const person of await members()){if(state?.currentReviewId!==review.id)return;const label=document.createElement('label'),input=document.createElement('input');input.type='checkbox';input.value=person.id;input.checked=emails.has(person.email.toLowerCase());label.append(input,document.createTextNode(person.name));options.append(label);}
    $('[data-thread-save-assignment]',root).onclick=async event=>{
      const button=event.currentTarget,error=$('[data-thread-management-error]',root);button.disabled=true;error.textContent='';
      try{await saveAssignment(review.id,$$('input:checked',options).map(input=>input.value));$('details',root).open=false;await loadConversation(review.id);}
      catch(ex){error.textContent=ex.message;}finally{button.disabled=false;}
    };
  }
  function decorateRecordConversationButton(button){
    if(!button)return;
    const formatCount=count=>count>999?'999+':String(count);
    button.dataset.readState=button.dataset.unreadMessageCount===undefined?'unknown':Number(button.dataset.unreadMessageCount)>0?'unread':'read';
    if(button.hasAttribute('data-conversation-count')){
      const count=Number(button.dataset.conversationCount||0),mine=button.dataset.assignedToMe==='true';
      const file=mine?'collaboration-assigned-red':count?'collaboration-blue':'collaboration-no-message-gray';
      const image=button.querySelector('img');if(image){image.dataset.collaborationIcon='';image.className='platform-icon';const src=window.NextAiIcons?window.NextAiIcons.source(file):'/images/platform/table/'+file+'.png?v=20260922-26';if(image.getAttribute('src')!==src)image.setAttribute('src',src);}
      let badge=button.querySelector('b');if(count>0){if(!badge){badge=document.createElement('b');button.append(badge);}badge.className='collaboration-message-count';badge.textContent=formatCount(count);}else badge?.remove();
      button.classList.toggle('has-conversation',count>0);button.title=(mine?'Assigned to you · ':'')+count+' message'+(count===1?'':'s');button.setAttribute('aria-label',button.title);return;
    }
    const active=Number(button.dataset.activeCount||0),origin=Number(button.dataset.originCount??active),related=Number(button.dataset.relatedCount||0),mine=Number(button.dataset.mineCount||0);
    const directMessages=Number(button.dataset.directMessageCount??origin),relatedMessages=Number(button.dataset.relatedMessageCount??related);
    const attention=mine>0||Number(button.dataset.urgentUnreadMentionCount||0)>0;
    const tone=attention?'mine':origin>0?'origin':related>0?'related':'empty';
    const file=attention?'collaboration-assigned-red':origin>0?'collaboration-blue':'collaboration-no-message-gray';
    button.dataset.contextTone=tone;button.classList.toggle('has-conversation',active>0);button.classList.remove('closed-history');
    let image=button.querySelector('img[data-collaboration-icon]');
    if(!image){button.querySelector('svg')?.remove();image=document.createElement('img');image.dataset.collaborationIcon='';image.className='platform-icon';image.alt='';image.style.cssText='width:28px;height:28px;object-fit:contain';button.prepend(image);}
    const src=window.NextAiIcons?window.NextAiIcons.source(file):'/images/platform/table/'+file+'.png';if(image.getAttribute('src')!==src)image.setAttribute('src',src);
    let badge=button.querySelector('b');if(directMessages>0){if(!badge){badge=document.createElement('b');button.append(badge)}badge.className='collaboration-direct-count';badge.textContent=formatCount(directMessages);}else badge?.remove();
    let indirect=button.querySelector('.collaboration-indirect-count');
    if(relatedMessages>0){if(!indirect){indirect=document.createElement('span');indirect.className='collaboration-indirect-count';button.append(indirect);}indirect.textContent=formatCount(relatedMessages);indirect.setAttribute('aria-hidden','true');}else indirect?.remove();
    button.title=active?directMessages+' direct messages · '+relatedMessages+' related messages in '+active+' active conversation'+(active===1?'':'s')+(mine>0?' · Requires your attention':''):'Start collaboration';
    const unread=Math.max(0,Number(button.dataset.urgentUnreadMentionCount)||0);
    let unreadDot=button.querySelector('.collaboration-unread-dot');
    if(unread>0){if(!unreadDot){unreadDot=document.createElement('i');unreadDot.className='collaboration-unread-dot';unreadDot.setAttribute('aria-hidden','true');button.append(unreadDot);}button.title+=' · '+unread+' unread message'+(unread===1?'':'s');}else unreadDot?.remove();
    button.setAttribute('aria-label',button.title+' for '+(button.dataset.title||button.dataset.identifier||'this record'));
  }
  async function refreshRecordSummaries(){
    if(summariesRefreshing||navigator.onLine===false)return;summariesRefreshing=true;
    try{
    const groups=new Map();$$('.collaboration-row-button[data-entity-type][data-entity-id]').forEach(button=>{const type=button.dataset.entityType;if(!groups.has(type))groups.set(type,[]);groups.get(type).push(button);});
    for(const [type,buttons] of groups){
      const keys=[...new Set(buttons.map(button=>button.dataset.entityId))];
      for(let start=0;start<keys.length;start+=250){try{
        const params=new URLSearchParams({entityType:type});keys.slice(start,start+250).forEach(key=>params.append('entityId',key));
        const response=await fetch('/app/collaboration/summaries?'+params,{headers:{Accept:'application/json'},cache:'no-store',signal:AbortSignal.timeout(10000)});if(!response.ok)continue;const summaries=await response.json();
        buttons.filter(button=>keys.slice(start,start+250).includes(button.dataset.entityId)).forEach(button=>{
          const summary=summaries[button.dataset.entityId]||{};['activeCount','totalCount','closedCount','originCount','relatedCount','mineCount','urgentUnreadMentionCount','unreadMessageCount','directMessageCount','relatedMessageCount'].forEach(field=>{if(summary[field]===undefined)delete button.dataset[field];else button.dataset[field]=String(summary[field]);});decorateRecordConversationButton(button);
        });
      }catch(_){}}
    }
    }finally{summariesRefreshing=false;}
  }

  async function markRecordConversationActive(type,key){
    const nodes=$$('[data-entity-type="'+CSS.escape(type)+'"][data-entity-id="'+CSS.escape(key)+'"]');
    try{
      const params=new URLSearchParams({entityType:type,entityId:key});
      const response=await fetch('/app/collaboration/summaries?'+params,{headers:{Accept:'application/json'},cache:'no-store'});
      if(!response.ok)throw new Error('Summary unavailable');
      const summary=(await response.json())[key]||{};
      nodes.forEach(node=>{const button=node.matches('.collaboration-row-button')?node:$('.collaboration-row-button',node);if(!button)return;
        ['activeCount','totalCount','closedCount','originCount','relatedCount','mineCount','urgentUnreadMentionCount','unreadMessageCount','directMessageCount','relatedMessageCount'].forEach(field=>{if(summary[field]===undefined)delete button.dataset[field];else button.dataset[field]=String(summary[field]);});
        if(summary.unreadCount!==undefined)button.dataset.unreadCount=String(summary.unreadCount);
        decorateRecordConversationButton(button);
      });
    }catch(_){nodes.forEach(node=>{const button=node.matches('.collaboration-row-button')?node:$('.collaboration-row-button',node);if(button)button.title='Saved. Refresh this table to update conversation counts.'})}
  }
  function connectHuddles(){
    if(!('WebSocket'in window)||huddleSocket?.readyState===WebSocket.OPEN||huddleSocket?.readyState===WebSocket.CONNECTING)return;clearTimeout(huddleReconnect);const protocol=location.protocol==='https:'?'wss:':'ws:';huddleSocket=new WebSocket(protocol+'//'+location.host+'/ws/huddles');
    huddleSocket.addEventListener('open',()=>{lastPong=Date.now();});huddleSocket.addEventListener('message',message=>{try{handleHuddleEvent(JSON.parse(message.data))}catch(error){console.warn('A live huddle update could not be displayed.',error);}});huddleSocket.addEventListener('close',event=>{window.LiveHuddleUI?.event({type:'OFFLINE'},sendHuddle);if(event.code!==1003&&event.code!==1008)huddleReconnect=setTimeout(connectHuddles,2000);});huddleSocket.addEventListener('error',()=>huddleSocket.close());
  }
  function selectTab(type,reload=true){
    if(!state)return;state.mode='PERSISTENT';state.messageType=type==='PRIVATE_NOTE'?'PRIVATE_NOTE':'TEAM_CHAT';
    $$('[data-thread-tab]',dialog()).forEach(button=>button.setAttribute('aria-selected',String(button.dataset.threadTab===state.messageType)));
    configureForms();if(!reload)return;if(state.currentReviewId)loadConversation(state.currentReviewId,state.showBack);else if(state.active?.length)loadConversation(state.active[0].id,false);else showNewConversation();
  }
  function showNewConversation(){
    const elements=view();elements.reply.hidden=true;elements.start.hidden=!state.canCollaborate;elements.list.innerHTML='<div class="conversation-empty"><svg viewBox="0 0 24 24"><path d="M5 5.5h14v9H9l-4 4v-13Z"/></svg><strong>'+(state.messageType==='PRIVATE_NOTE'?'Add a private note':'Start a team conversation')+'</strong><span>'+(state.messageType==='PRIVATE_NOTE'?'Only your signed-in user can read it.':'Keep the discussion attached to this exact record.')+'</span>'+(state.closedCount?'<a class="conversation-history-link" href="'+escape(state.historyUrl)+'">View '+state.closedCount+' closed conversation'+(state.closedCount===1?'':'s')+'</a>':'')+'</div>';configureForms();if(!elements.start.hidden)$('textarea',elements.start).focus();
  }
  function showPicker(){
    const elements=view(),all=[...(state.threads||[])].sort((left,right)=>Date.parse(right.createdAt)-Date.parse(left.createdAt));elements.start.hidden=true;elements.reply.hidden=true;state.currentReviewId=null;
    elements.list.innerHTML='<section class="conversation-picker"><header><strong>'+all.length+' conversation'+(all.length===1?'':'s')+' for this record</strong><span>Choose by date, status, and participants.</span></header><div>'+all.map(review=>'<button type="button" data-conversation-id="'+review.id+'"><span class="conversation-picker-icon '+(review.status==='CLOSED'?'closed':'active')+'"><svg viewBox="0 0 24 24"><path d="M5 5.5h14v9H9l-4 4v-13Z"/></svg></span><span><strong>'+escape(formatDate(review.createdAt))+'</strong><small class="conversation-picker-meta"><span class="conversation-picker-status">'+escape(review.status==='CLOSED'?'Closed':'Active')+'</span><span>'+escape(review.participants||review.requester)+'</span><span class="conversation-picker-count">'+review.messageCount+' message'+(review.messageCount===1?'':'s')+'</span></small></span><b aria-hidden="true">›</b></button>').join('')+'</div><footer>'+(state.canCollaborate?'<button type="button" class="conversation-new-button" data-new-conversation>＋ New conversation</button>':'')+'<a class="conversation-history-link" href="'+escape(state.historyUrl)+'">Open filtered history</a></footer></section>';
    $$('[data-conversation-id]',elements.list).forEach(button=>button.addEventListener('click',()=>loadConversation(button.dataset.conversationId,true)));$('[data-new-conversation]',elements.list)?.addEventListener('click',showNewConversation);
  }
  async function loadConversation(reviewId,showBack=false){
    const elements=view();state.currentReviewId=reviewId;state.showBack=showBack;elements.start.hidden=true;elements.reply.hidden=true;elements.list.innerHTML='<div class="conversation-loading">Loading conversation…</div>';
    const requestedType=state.messageType;
    document.dispatchEvent(new Event('collaboration-thread-loading'));
    try{
      const response=await fetch('/app/collaboration/reviews/'+encodeURIComponent(reviewId)+'?messageType='+requestedType,{headers:{Accept:'application/json'},cache:'no-store'});
      if(!response.ok)throw new Error();const conversation=await response.json();
      if(state.currentReviewId!==reviewId||state.messageType!==requestedType)return;
      state.currentReview=conversation.review;
      setParentLink(conversation.review.parentUrl,conversation.review.subjectType,conversation.review.subjectKey,conversation.review.subjectLabel);
      elements.list.innerHTML=renderMessages(conversation,showBack);
      elements.reply.action='/app/collaboration/reviews/'+conversation.review.id+'/reply';
      elements.reply.hidden=!state.canCollaborate||conversation.review.status==='CLOSED';configureForms();renderManagement(conversation.review);
      $('[data-conversation-back]',elements.list)?.addEventListener('click',showPicker);
      if(!elements.reply.hidden)$('textarea',elements.reply).focus();elements.list.scrollTop=elements.list.scrollHeight;
      document.dispatchEvent(new CustomEvent('collaboration-thread-rendered',{detail:{reviewId,messageType:requestedType,messageIds:conversation.messages.map(message=>message.id),unreadMentionMessageIds:conversation.unreadMentionMessageIds||[]}}));
    }catch(_){elements.list.innerHTML='<div class="history-error">This conversation could not be loaded. Please try again.</div>';}
  }
  async function loadSubject(forcePicker){
    const elements=view();elements.list.innerHTML='<div class="conversation-loading">Loading conversations…</div>';elements.start.hidden=true;elements.reply.hidden=true;
    try{const response=await fetch('/app/collaboration/subject?subjectType='+encodeURIComponent(state.type)+'&subjectKey='+encodeURIComponent(state.key),{headers:{Accept:'application/json'}});if(!response.ok)throw new Error();const conversations=await response.json(),threads=conversations.map(item=>item.review).filter(review=>review.status==='ACTIVE'),active=threads.filter(review=>review.status==='ACTIVE');Object.assign(state,{threads,active,closedCount:threads.length-active.length,historyUrl:historyUrl(state.type,state.key)});if((forcePicker||threads.length>1)&&threads.length)showPicker();else if(active.length)loadConversation(active[0].id,false);else if(threads.length===1)loadConversation(threads[0].id,false);else showNewConversation();}catch(_){elements.list.innerHTML='<div class="history-error">These conversations could not be loaded. Please try again.</div>';}
  }
  function setHeader(button){
    $('#thread-record-type').textContent=(button.dataset.entityType||button.dataset.type||'Record').replaceAll('_',' ')+' collaboration';$('#thread-record-title').textContent=button.dataset.title||button.dataset.product||'Record conversation';
    const identifier=button.dataset.identifier||'',opaque=/^[0-9a-f]{8}-[0-9a-f-]{27,}(?:\||$)/i.test(identifier),context=[opaque?'':identifier,button.dataset.locationLabel].filter(Boolean).join(' · ');$('#thread-record-context').textContent=context;$('#thread-record-context').hidden=!context;
    const badges=$('#thread-record-badges');badges.innerHTML='';[['expiration',button.dataset.expiration?'Expires '+button.dataset.expiration:null],['status',button.dataset.status],['marketplace',button.dataset.marketplace]].forEach(([kind,text])=>{if(!text)return;const badge=document.createElement('span');badge.className=kind;if(kind==='status')badge.dataset.status=text.toUpperCase();badge.textContent=text;badges.append(badge);});
    setParentLink(button.dataset.parentUrl,(button.dataset.entityType||button.dataset.type||'record'));
  }
  function sourceTableUrl(url,type,key,label){
    const fallback={ORDER:'/app/orders',INVENTORY:'/app/inventory',MARKETPLACE_SKU:'/app/marketplace-skus',CATALOG:'/app/catalog',ACCOUNT_PRODUCT:'/app/account-catalog',USER:'/app/users',SHIPMENT:'/app/shipping'};
    const path=url||fallback[type];if(typeof path!=='string'||!/^\/app(?:\/|\?|$)/.test(path)||path.includes('\\'))return '';
    const source=new URL(path,location.origin);
    if(!source.searchParams.get('q')&&type!=='PLATFORM'){
      const readableKey=key&&!/^[0-9a-f]{8}-[0-9a-f-]{27}/i.test(key);
      const query=readableKey?key:String(label||'').split(' · expires')[0];
      if(query)source.searchParams.set('q',query);
    }
    source.searchParams.delete('page');source.searchParams.delete('status');
    return source.pathname+source.search;
  }
  function setParentLink(url,type,key=state?.key,label=state?.label){const link=$('#thread-parent-link');if(!link)return;const source=sourceTableUrl(url,type,key,label);link.hidden=!source;if(source){link.href=source;link.dataset.tooltip='Open original '+(type||'record').replaceAll('_',' ').toLowerCase()+': '+(label||key||'');link.setAttribute('aria-label',link.dataset.tooltip);}}

  window.openRecordCollaboration=(button,forcePicker=false)=>{
    const type=(button.dataset.entityType||button.dataset.type||'RECORD').toUpperCase(),key=button.dataset.entityId||button.dataset.key;
    const chooseConversation=forcePicker||Number(button.dataset.totalCount||0)>1;
    state={type,key,label:button.dataset.title||button.dataset.product||button.dataset.identifier||'Record',parentUrl:sourceTableUrl(location.pathname,type,key,button.dataset.identifier||button.dataset.title),snapshot:snapshotFrom(button),canCollaborate:button.dataset.canCollaborate!=='false',messageType:'TEAM_CHAT',mode:'PERSISTENT',currentReviewId:null,currentHuddleId:null};setHeader(button);selectTab('TEAM_CHAT',false);dialog().showModal();loadSubject(chooseConversation);
  };
  let recordRefreshTimer;
  window.prepareRecordCollaboration=button=>{decorateRecordConversationButton(button);clearTimeout(recordRefreshTimer);recordRefreshTimer=setTimeout(refreshRecordSummaries,100);};
  window.openInventoryConversation=button=>window.openRecordCollaboration(button,false);
  window.openCollaborationReview=button=>{state={type:button.dataset.subjectType,key:button.dataset.subjectKey,label:button.dataset.subjectLabel,parentUrl:button.dataset.parentUrl||'',snapshot:{},canCollaborate:button.dataset.canCollaborate!=='false',messageType:'TEAM_CHAT',mode:'PERSISTENT',currentReviewId:button.dataset.reviewId,currentHuddleId:null};setHeader(button);selectTab(button.dataset.messageType||'TEAM_CHAT',false);dialog().showModal();loadConversation(button.dataset.reviewId,false);};
  window.openGeneralHuddle=()=>window.LiveHuddleUI?.showPeople();
  window.filterCollaborationRows=value=>{const query=(value||'').trim().toLowerCase(),rows=$$('.collaboration-open-row'),visible=rows.reduce((count,row)=>{const show=!query||(row.dataset.search||'').includes(query);row.hidden=!show;return count+(show?1:0);},0),empty=$('.collaboration-search-empty');if(empty)empty.hidden=!query||visible>0;const total=$('.collaboration-card .table-footer span');if(total)total.textContent=query?'Showing '+visible+' matching conversation'+(visible===1?'':'s'):'Showing '+rows.length+' conversation'+(rows.length===1?'':'s');};
  function boot(){
    document.addEventListener('collaboration-messages-read',()=>{clearTimeout(recordRefreshTimer);recordRefreshTimer=setTimeout(refreshRecordSummaries,100);
      const id=state?.currentReviewId;
      if(id&&$$('tr[data-review-id]').some(row=>row.dataset.reviewId===String(id)))fetch('/app/collaboration/reviews/'+encodeURIComponent(id),{cache:'no-store',headers:{Accept:'application/json'}}).then(r=>r.ok?r.json():null).then(c=>{if(c)updateAssignment(c.review);}).catch(()=>{});
    });
    document.addEventListener('visibilitychange',()=>{if(!document.hidden)refreshRecordSummaries();});
    setInterval(()=>{refreshRecordSummaries();},120000);
    setInterval(()=>{if(!document.hidden)refreshRecordSummaries();},15000);
    window.addEventListener('focus',refreshRecordSummaries);
    window.addEventListener('online',refreshRecordSummaries);
    $$('.collaboration-row-button').forEach(decorateRecordConversationButton);refreshRecordSummaries();
    new MutationObserver(records=>{
      const added=new Set();
      records.forEach(record=>record.addedNodes.forEach(node=>{
        if(node.nodeType!==1)return;
        if(node.matches('.collaboration-row-button'))added.add(node);
        node.querySelectorAll('.collaboration-row-button').forEach(button=>added.add(button));
      }));
      added.forEach(button=>window.prepareRecordCollaboration(button));
    }).observe(document.body,{childList:true,subtree:true});
    $$('[data-collaboration-composer]').forEach(field=>{wireComposer(field);wireEnterToSend(field,field.closest('form'));});wireAttachments();$$('[data-thread-tab]').forEach(button=>button.addEventListener('click',()=>selectTab(button.dataset.threadTab)));
    $$('.collaboration-composer').forEach(form=>form.addEventListener('submit',event=>{const field=$('textarea',form),files=$('input[type=file]',form)?.files;if(!event.submitter?.name&&!field.value.trim()&&!files?.length){event.preventDefault();field.focus();}}));
    $$('[data-thread-dialog-close]').forEach(button=>button.addEventListener('click',()=>dialog().close()));
    dialog()?.addEventListener('click',event=>{if(event.target===event.currentTarget)dialog().close();});
    const auto=$('[data-thread-autopen="true"]');if(auto)setTimeout(()=>window.openCollaborationReview(auto),80);
    connectHuddles();
    setInterval(()=>{if(socketOpen()&&Date.now()-lastPong>75000)huddleSocket.close();else sendHuddle({type:'PING'});},25000);
    document.addEventListener('visibilitychange',()=>{if(!document.hidden){connectHuddles();sendHuddle({type:'PING'});}});
    window.addEventListener('online',connectHuddles);

  }
  document.readyState==='loading'?document.addEventListener('DOMContentLoaded',boot):boot();
})();
