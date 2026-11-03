(() => {
    const root = document.querySelector('#personal-queue');
    if (!root) return;
    const account = {value:''}, search = root.querySelector('#personal-search');
    const size = root.querySelector('#personal-size'), status = root.querySelector('#personal-status');
    const storage = 'collaboration.personal.' + root.dataset.user;
    let filters = {};
    try { filters = JSON.parse(sessionStorage.getItem(storage)) || {}; } catch {}
    search.value = filters.search || '';
    try { size.value = Math.max(1,Math.min(50,Number(localStorage.getItem(storage+'.initialRows'))||Number(filters.size)||5)); } catch { size.value=filters.size||5; }
    account.value=filters.account||'';
    const type=root.querySelector('#personal-type');type.value=filters.type||'';
    let store=filters.store||'';
    const viewport=window.NextAiCollaborationBoardState.create(root,root.dataset.user,'personal');
    window.addEventListener('pagehide',()=>{
        const {focused,...anchor}=viewport.capture();
        sessionStorage.setItem(storage+'.anchor',JSON.stringify(anchor));
    });
    let page, busy = false, generation=0, dragged=null;
    let scope=filters.scope||'ASSIGNED';
    const extent = filters.extent || {};
    const expanded=filters.expanded||{};
    function save() {
        sessionStorage.setItem(storage, JSON.stringify({account:account.value, search:search.value, size:size.value, extent, expanded, scope, store, type:type.value}));
    }
    function element(tag, text, parent) {
        const node = document.createElement(tag);
        node.textContent = text;
        parent.append(node);
        return node;
    }
    function render() {
        const focusedGroup=document.activeElement?.closest('.personal-group-toggle')?.dataset.groupKey;
        clearInsertion();
        root.dataset.loadedScope=scope;
        const columns = root.querySelector('#personal-columns');

        const filtered=filteredRows();
        const ranked=scope==='ASSIGNED';
        status.textContent='';status.classList.toggle('personal-empty-status',!filtered.length);
        const count=Math.max(1,Math.min(50,Number(size.value)||5));

        const first=filtered.slice(0,count),firstIds=new Set(first.map(r=>r.card.key.type+r.card.key.id));
        const labels={THIS_WEEK:'This week',LAST_WEEK:'Last week',EARLIER_THIS_MONTH:'This month',LAST_MONTH:'Last month',OLDER:'Older'};
        const groups=[['',first]];
        for(const window of page.windows||[]){
            const rows=filtered.filter(r=>{
                const id=r.card.key.type+r.card.key.id,at=page.dates?.[id];
                return !firstIds.has(id)&&at&&(!window.from||Date.parse(at)>=Date.parse(window.from))&&Date.parse(at)<Date.parse(window.until);
            });
            if(rows.length)groups.push([labels[window.key]||window.key,rows]);
        }
        let wrap=columns.querySelector('.table-wrap');
        if(!wrap){wrap=element('div','',columns);wrap.className='table-wrap';}
        wrap.querySelectorAll('tbody').forEach(n=>n.remove());
        let table=wrap.querySelector('table');
        if(!table){table=element('table','',wrap);table.className='collaboration-table';}
        table.querySelector('thead [data-column="record-context"]')?.remove();
        delete table.dataset.tableWidgetReady; delete table.dataset.overviewReady;
        table.dataset.tableWidget='personal-collaboration';table.dataset.priorityAfterRecord=String(ranked);table.dataset.dataToolsReady='true';
        const head=table.tHead?.rows[0]||element('tr','',element('thead','',table));
        if(!head.cells.length)
        for(const caption of ['Subject','Conversation','People','Created','Last activity','Assigned to','Due date','Actions']){
            const th=element('th',caption,head);if(caption==='Actions')th.dataset.contextActions='';
        }
        const oldPriority=head.querySelector('[data-column=priority]');
        if(ranked&&!oldPriority){
            const priority=element('th','Priority',head);priority.dataset.column='priority';priority.dataset.columnRequired='true';priority.dataset.noSort='true';
            head.prepend(priority);
        }else if(!ranked)oldPriority?.remove();
        if(filtered.length&&!head.querySelector('[data-context-actions]'))element('th','Actions',head).dataset.contextActions='';
        if(!filtered.length)head.querySelector('[data-context-actions]')?.remove();
        if(!filtered.length){
            const cell=element('td','',element('tr','',element('tbody','',table)));cell.colSpan=ranked?9:8;cell.className='personal-empty-cell';
            element('strong',scope==='ASSIGNED'?'No conversations assigned to you in this view':'No conversations match this view',cell);
            element('span','Try another account, or adjust your search and filters.',cell);
        }
        for(const [label,rows] of groups){
            const section=element('tbody','',table);
            const key=[scope,account.value,store,type.value,search.value,label].join('|');
            if(label){
                const groupCell=element('td','',element('tr','',section));groupCell.colSpan=ranked?9:8;
                const heading=element('button',(rows.length?(expanded[key]?'▾ ':'▸ '):'')+label+' · '+rows.length,groupCell);
                heading.className='personal-group-toggle';heading.textContent=label+' · '+rows.length;const arrow=element('span','',heading);arrow.className='personal-group-chevron';arrow.setAttribute('aria-hidden','true');heading.title=(expanded[key]?'Collapse ':'Expand ')+label;heading.type='button';heading.dataset.groupKey=key;heading.setAttribute('aria-expanded',String(Boolean(expanded[key])));
                heading.disabled=!rows.length;
                heading.onclick=()=>{expanded[key]=!expanded[key];save();render();restoreGroup(key,true);};
                if(!expanded[key])continue;
            }
            const tbody=section;
            const olderExtra=Math.max(0,Math.floor((Number(extent[key])||0)/25))*25;
            const limit=label==='Older'?25+olderExtra:label?rows.length:count;
            for(const row of rows.slice(0,limit)) {
                const closed=['DONE','CLOSED'].includes(row.card.state);
                const c=row.card, article=element('tr','',tbody);
                article.id=c.key.type+c.key.id; article.dataset.cardId=article.id; article.className='collaboration-open-row'; article.tabIndex=0;

                if(ranked){
                    const priority=element('td','',article);priority.dataset.column='priority';priority.className='personal-priority-cell';
                    const grip=element('button','⠿',priority);grip.type='button';grip.className='personal-priority-handle';grip.draggable=true;
                    grip.dataset.focusKey='priority';grip.setAttribute('aria-label','Move priority for '+c.title);
                    grip.setAttribute('aria-keyshortcuts','Alt+ArrowUp Alt+ArrowDown');
                    element('span',row.position==null?'—':String(row.position),priority).dataset.priorityNumber='';
                    if(row.position!=null){
                        article.draggable=true;
                        article.ondragstart=event=>{
                            if(busy||!event.target.closest('.personal-priority-handle')){event.preventDefault();return;}
                            dragged=c.key;article.classList.add('personal-dragging');event.dataTransfer.effectAllowed='move';event.dataTransfer.setData('text/plain',article.id);
                        };
                        article.ondragend=()=>{dragged=null;clearInsertion();article.classList.remove('personal-dragging');};
                        article.ondragover=event=>{
                            if(!dragged||sameKey(dragged,c.key)||scope!=='ASSIGNED')return;
                            event.preventDefault();event.dataTransfer.dropEffect='move';clearInsertion();
                            const after=event.clientY>article.getBoundingClientRect().top+article.offsetHeight/2;
                            article.classList.add(after?'personal-drop-after':'personal-drop-before');
                            article.classList.toggle('personal-drop-unchanged',!changesOrder(dragged,c.key,after));
                        };
                        article.ondrop=event=>{
                            event.preventDefault();const from=dragged;dragged=null;
                            const after=article.classList.contains('personal-drop-after');clearInsertion();
                            if(from&&!sameKey(from,c.key)&&changesOrder(from,c.key,after))reorder(from,c.key,after?'AFTER':'BEFORE');
                        };
                        grip.onkeydown=event=>{
                            if(!event.altKey||!['ArrowUp','ArrowDown'].includes(event.key))return;
                            event.preventDefault();const delta=event.key==='ArrowUp'?-1:1;
                            const target=filtered[filtered.indexOf(row)+delta];
                            if(target)reorder(c.key,target.card.key,delta<0?'BEFORE':'AFTER');
                        };
                    }else grip.disabled=true;
                }
                const detail=page.reviews?.[c.key.id];
                const subject=element('td','',article);subject.className='personal-subject-cell';
                const layout=element('div','',subject);layout.className='table-overview-layout';
                const accountChoice=[...root.querySelectorAll('[data-collaboration-account]:not([data-collaboration-store])')].find(b=>b.dataset.collaborationAccount===(c.contextAccount||c.account));
                const picture=page.pictures?.[c.key.id]||accountChoice?.dataset.logo;
                article.dataset.image=picture||'';article.dataset.title=detail?.subjectLabel||c.title;
                const rail=element('div','',layout);rail.className='table-overview-rail';
                const chatButton=element('button','',rail);chatButton.className='collaboration-row-button';chatButton.type='button';chatButton.dataset.conversationCount=String(detail?.messageCount||0);chatButton.dataset.assignedToMe=String(!closed&&(c.assignedToMe??(c.key.type==='REVIEW'&&(detail?.assigneeEmail||'').split(',').some(email=>email.trim().toLowerCase()===root.dataset.user.toLowerCase()))));chatButton.dataset.unreadMessageCount=String(detail?.unreadMessageCount||0);chatButton.setAttribute('aria-label','Open conversation');
                const chat=element('img','',chatButton);chat.src=window.NextAiIcons?.source(chatButton.dataset.assignedToMe==='true'?'collaboration-assigned-red':'collaboration-blue')||'/images/platform/table/collaboration-blue.png';chat.dataset.collaborationIcon='';chat.alt=chatButton.dataset.assignedToMe==='true'?'Assigned to you':'Conversation';chat.className='platform-icon';
                const copy=element('div','',layout);copy.className='collaboration-record-copy';
                if(ranked){const handle=element('button','⠿',copy);handle.type='button';handle.draggable=true;handle.className='personal-priority-handle personal-subject-handle';handle.dataset.focusKey='subject-priority';handle.setAttribute('aria-label','Move priority for '+c.title);
                    handle.onkeydown=article.querySelector('.personal-priority-handle')?.onkeydown;}
                element('strong',subjectTitle(detail,c),copy);element('small',c.context,copy);
                const heading=element('td','',article);heading.className='collaboration-topic';

                const open=element('button',(detail?.messageCount||0)+' messages',heading);open.title=c.title;open.type='button';open.className='collaboration-open-thread';open.dataset.focusKey='open';open.dataset.open='';
                open.onclick=()=>{
                    if(detail){
                        Object.assign(open.dataset,{reviewId:detail.id,subjectType:detail.subjectType,subjectKey:detail.subjectKey,
                            title:detail.subjectLabel||c.title,entityType:detail.subjectType,identifier:sourceContext(c,detail),status:detail.status,subjectLabel:detail.subjectLabel||c.title,parentUrl:detail.parentUrl||'',personalContext:detail.id,image:picture||'',
                            messageType:scope==='PRIVATE'?'PRIVATE_NOTE':'TEAM_CHAT',canCollaborate:String((page.editableAccounts||[]).includes(c.account))});
                        window.openCollaborationReview?.(open);
                    }else if(c.parent)openNativeTask(c,open);
                };

                const peopleCell=element('td','',article);
                const people=element('div','',peopleCell);people.className='collaboration-participants';
                initials(people,detail?.participants||'','People',true);
                const date=value=>value?new Date(value).toLocaleDateString():'—';
                element('time',date(detail?.createdAt),element('td','',article));
                element('time',date(detail?.updatedAt),element('td','',article));
                const assignedCell=element('td','',article),dueCell=element('td','',article);
                if(detail){
                    const editable=(page.editableAccounts||[]).includes(c.account);
                    article.dataset.reviewId=detail.id;article.dataset.status=detail.status;article.dataset.dueAt=detail.dueAt||'';
                    const assignment=element('button','',assignedCell);assignment.type='button';assignment.className='assignment-trigger';assignment.disabled=!editable||detail.messageCount===detail.privateNoteCount;
                    initials(assignment,detail.assigneeName||'','Assigned to',false);element('span','⌄',assignment);assignment.dataset.focusKey='assignees';
                    Object.assign(assignment.dataset,{assignReview:'',reviewId:detail.id,accountId:c.account,assignedEmails:detail.assigneeEmail||'',
                        assignmentUrl:'/app/collaboration/personal/reviews/'+detail.id+'/assignees'});
                    const due=element('button','',dueCell);due.type='button';due.className='task-due-button';due.disabled=closed||!editable;
                    Object.assign(due.dataset,{editDue:'',reviewId:detail.id,dueDate:detail.dueDate||'',timeZone:detail.dueTimeZone||'America/Los_Angeles',
                        dueUrl:'/app/collaboration/personal/reviews/'+detail.id+'/due-date'});
                    due.insertAdjacentHTML('afterbegin','<svg viewBox="0 0 24 24" aria-hidden="true"><rect x="4" y="5" width="16" height="16" rx="3"/><path d="M8 3v4M16 3v4M4 11h16"/></svg>');
                    element('span',detail.dueDate?detail.dueDate.slice(5,7)+'/'+detail.dueDate.slice(8,10)+'/'+detail.dueDate.slice(2,4):'No due date',due);
                }else{
                    element('span','—',assignedCell);element('span','No due date',dueCell);
                }
                const actionCell=element('td','',article);
                const actions=element('div','',actionCell);actions.dataset.contextMenu='';actions.dataset.title=c.title;
                chatButton.onclick=()=>open.click();
                const action=element('button',closed?'Reopen':'Close',actions);
                if(!closed)action.dataset.actionImage='/images/platform/table/collaboration-complete.png';
                action.onclick=async()=>{
                    if(busy) return;
                    if(!window.NextAiConfirm)return;
                    const accepted=await window.NextAiConfirm({title:closed?'Reopen conversation?':'Close conversation?',
                        message:'Update this conversation’s status. Notifications follow the existing account settings.',accept:closed?'Reopen':'Close conversation'});
                    if(!accepted){const trigger=article.querySelector('.table-action-trigger');(trigger||article).focus({preventScroll:true});return;}
                    busy=true;
                    try {
                        const body=new URLSearchParams(new FormData(document.querySelector('#personal-form')));
                        body.set('id',c.key.id);body.set('type',c.key.type);body.set('close',String(!closed));
                        const response=await fetch('/app/collaboration/personal/state',{method:'POST',body});
                        if(!response.ok) throw Error();
                        await load();document.getElementById(article.id)?.focus();
                    } catch {status.textContent='The status could not be changed. Check access and refresh.';}
                    finally {busy=false;}
                };
            }
            if(label==='Older'&&rows.length>limit){
                const moreCell=element('td','',element('tr','',section));moreCell.colSpan=ranked?9:8;
                const more=element('button','Load more',moreCell);more.type='button';more.className='secondary-button';more.onclick=()=>{
                extent[key]=olderExtra+25; save();render();
            };
            }
        }
        if(filtered.length){
            const noun=ranked?'assigned conversation':scope==='CLOSED'?'closed conversation':scope==='PRIVATE'?'private note':'active conversation';
            const accountName=account.value?[...root.querySelectorAll('[data-collaboration-account]:not([data-collaboration-store])')].find(b=>b.dataset.collaborationAccount===account.value)?.dataset.name||'this account':'all accessible accounts';
            status.textContent=filtered.length+' '+noun+(filtered.length===1?'':'s')+' across '+accountName+' · Showing '+table.querySelectorAll('tr[data-card-id]').length;
        }
        window.NextAiTableWidget?.refresh();
        if(focusedGroup&&document.activeElement===document.body)restoreGroup(focusedGroup,false);
    }
    function subjectTitle(detail,card){
        return detail?.title&&detail.title!=='Conversation about '+detail.subjectLabel?detail.title:(detail?.subjectLabel||card.title);
    }
    let savedTimer;
    size.onchange=()=>{
        size.value=Math.max(1,Math.min(50,Number(size.value)||5));
        const feedback=root.querySelector('#personal-size-saved');
        try{localStorage.setItem(storage+'.initialRows',size.value);feedback.textContent='Saved';}
        catch{feedback.textContent='For this visit';}
        save();render();clearTimeout(savedTimer);savedTimer=setTimeout(()=>feedback.textContent='',2500);
    };
    function sourceContext(card,detail){
        const sourceStore=card.store||detail.storeId;
        const option=[...root.querySelectorAll('[data-collaboration-store]')].find(b=>b.dataset.collaborationAccount===card.account&&b.dataset.collaborationStore===sourceStore);
        const name=option?.dataset.name;
        return name&&name!==card.context?card.context+' · '+name:card.context;
    }
    async function openNativeTask(card,trigger){
        const dialog=element('dialog','',document.body);dialog.className='invite-dialog';
        const shell=element('section','',dialog);shell.className='dialog-shell';
        const header=element('header','',shell);header.className='dialog-header';element('h2',card.title,header);
        const close=element('button','×',header);close.className='dialog-close';close.type='button';close.setAttribute('aria-label','Close task');close.onclick=()=>dialog.close();
        const body=element('div','',shell);body.className='dialog-body';if(card.sourceRestricted){const notice=element('p','Source access required. Ask an account administrator for access to the original record.',body);notice.className='notice';}
        const error=element('p','Loading task…',body);error.setAttribute('role','status');
        const list=element('div','',body),more=element('button','Load earlier messages',body);more.type='button';more.className='secondary-button';more.hidden=true;
        let oldest;
        async function messages(append=false){
            const params=new URLSearchParams({parent:card.parent});if(append&&oldest){params.set('before',oldest.createdAt);params.set('beforeId',oldest.id);}
            const response=await fetch('/app/collaboration/board/tasks/'+card.key.id+'/messages?'+params,{cache:'no-store'});if(!response.ok)throw Error('This task could not be loaded.');
            const notes=await response.json();if(!append)list.replaceChildren();
            for(const note of notes){const article=element('article','',list);element('strong',note.author,article);element('small',new Date(note.createdAt).toLocaleString(),article);element('p',note.body,article);
                for(const file of note.files||[]){const link=element('a',file.name,article);link.href='/app/collaboration/board/tasks/'+card.key.id+'/files/'+file.id+'?parent='+encodeURIComponent(card.parent);}}
            oldest=notes.at(-1);more.hidden=notes.length<50;error.textContent=notes.length?'':'No messages yet.';
        }
        more.onclick=()=>messages(true).catch(e=>error.textContent=e.message);
        const form=document.querySelector('#personal-form').cloneNode(true);form.removeAttribute('id');form.hidden=false;body.append(form);
        const field=element('textarea','',form);field.name='body';field.maxLength=4000;field.required=true;field.setAttribute('aria-label','Reply to task');field.placeholder='Write a message…';
        const send=element('button','Send',form);send.type='submit';send.className='primary-button';
        form.onsubmit=async event=>{event.preventDefault();send.disabled=true;try{const data=new FormData(form);data.set('parent',card.parent);
            const response=await fetch('/app/collaboration/board/tasks/'+card.key.id+'/messages',{method:'POST',body:data});if(!response.ok)throw Error('Your reply could not be saved.');field.value='';await messages();
        }catch(e){error.textContent=e.message;}finally{send.disabled=false;}};
        dialog.addEventListener('close',()=>{dialog.remove();trigger.focus({preventScroll:true});window.dispatchEvent(new Event('personal-collaboration-updated'));});
        dialog.showModal();messages().catch(e=>error.textContent=e.message);
    }
    function changesOrder(from,target,after){
        const rank=key=>page.rows.find(r=>sameKey(r.card.key,key))?.position;
        const a=rank(from),b=rank(target);if(a==null||b==null||a===b)return false;
        const next=after?(a<b?b:b+1):(a<b?b-1:b);
        return next!==a;
    }
    function sameKey(a,b){return a?.type===b?.type&&a?.id===b?.id;}
    function clearInsertion(){root.querySelectorAll('.personal-drop-before,.personal-drop-after,.personal-drop-unchanged').forEach(r=>r.classList.remove('personal-drop-before','personal-drop-after','personal-drop-unchanged'));}
    async function reorder(from,target,placement){
        if(busy||scope!=='ASSIGNED')return;
        busy=true;
        try{
            const data=new URLSearchParams(new FormData(document.querySelector('#personal-form')));
            Object.entries({type:from.type,id:from.id,targetType:target.type,target:target.id,revision:page.revision,placement}).forEach(([k,v])=>data.set(k,v));
            const response=await fetch('/app/collaboration/personal/position',{method:'POST',body:data});
            if(!response.ok){
                await load();
                status.textContent=response.status===409?'Your order changed. Review the refreshed priorities and try again.':'Your priority could not be saved.';
                return;
            }
            await load();
            revealPriority(from);
            const row=document.getElementById(from.type+from.id),grip=row?.querySelector('[data-focus-key=priority]');
            if(grip){grip.focus({preventScroll:true});grip.scrollIntoView({block:'nearest'});}
            status.textContent='Priority saved.';
        }catch{
            try{await load();}catch{}
            status.textContent='The saved order could not be confirmed. Refresh and try again.';
        }finally{busy=false;dragged=null;clearInsertion();}
    }
    function revealPriority(key){
        const filtered=filteredRows(),index=filtered.findIndex(r=>sameKey(r.card.key,key));
        const count=Number(size.value)||5;
        if(index<0||index<count)return;
        const id=key.type+key.id,at=page.dates?.[id];
        const window=page.windows?.find(w=>at&&(!w.from||Date.parse(at)>=Date.parse(w.from))&&Date.parse(at)<Date.parse(w.until));
        if(!window)return;
        const labels={THIS_WEEK:'This week',LAST_WEEK:'Last week',EARLIER_THIS_MONTH:'This month',LAST_MONTH:'Last month',OLDER:'Older'};
        const group=filtered.slice(count).filter(r=>{const date=page.dates?.[r.card.key.type+r.card.key.id];return date&&(!window.from||Date.parse(date)>=Date.parse(window.from))&&Date.parse(date)<Date.parse(window.until);});
        const groupIndex=group.findIndex(r=>sameKey(r.card.key,key));
        const groupKey=[scope,account.value,store,type.value,search.value,labels[window.key]||window.key].join('|');
        expanded[groupKey]=true;extent[groupKey]=Math.max(extent[groupKey]||0,Math.ceil((groupIndex+1)/count)*count-count);
        save();render();
    }
    function scopeIdentity(button){
        if(!button)return;
        const all=!account.value,summary=root.querySelector('.context-switcher');
        root.querySelector('#collaboration-scope-label').textContent=button.dataset.name;
        root.querySelector('#collaboration-scope-caption').textContent=store?[button.dataset.countryFlag,button.dataset.marketplaceLabel].filter(Boolean).join(' '):all?'All accessible accounts':'All stores · Account overview';
        const logo=summary?.querySelector('.context-brand-logo'),fallback=summary?.querySelector('[data-collaboration-scope-initials]');
        if(logo){logo.hidden=!all&&!button.dataset.logo;if(all||button.dataset.logo)logo.src=all?'/images/next-ai-mark-transparent.png':button.dataset.logo;else logo.removeAttribute('src');}
        if(fallback){fallback.hidden=all||Boolean(button.dataset.logo);fallback.textContent=button.dataset.initials||'';}
        const channel=summary?.querySelector('[data-collaboration-channel]');
        if(channel){
            const images={AMAZON:'/images/channels/amazon-seller.png',SHOPIFY:'/images/channels/shopify-bag.png',WALMART:'/images/channels/walmart-official.png'};
            channel.hidden=!store||!images[button.dataset.channel];
            channel.classList.toggle('walmart-channel',button.dataset.channel==='WALMART');
            channel.classList.toggle('amazon-channel',button.dataset.channel==='AMAZON');
            channel.classList.toggle('shopify-channel',button.dataset.channel==='SHOPIFY');
            const img=channel.querySelector('img');if(!channel.hidden){img.src=images[button.dataset.channel];img.alt=button.dataset.channel;}
        }
        root.querySelectorAll('[data-collaboration-account]').forEach(b=>b.classList.toggle('selected',b===button));
    }
    function initials(parent,value,label,focusable){
        const names=value.split(',').map(n=>n.trim()).filter(Boolean);
        const stack=element('span','',parent);stack.className='participant-stack';
        names.slice(0,3).forEach(name=>element('i',name[0],stack));
        if(names.length>3)element('i','+'+(names.length-3),stack);
        if(!names.length)element('span','—',stack);
        const full=names.length?names.join(', '):'Unassigned';
        if(focusable){stack.tabIndex=0;stack.setAttribute('role','img');stack.setAttribute('aria-label',label+': '+full);stack.title=full;}
        else {parent.setAttribute('aria-label',label+': '+full);parent.title=full;}
    }
    function restoreGroup(key,scroll){
        const button=[...root.querySelectorAll('.personal-group-toggle')].find(b=>b.dataset.groupKey===key);
        if(!button)return;
        button.focus({preventScroll:true});
        if(scroll){
            const sticky=[...root.querySelectorAll('thead,thead th,.table-standard-toolbar')].filter(n=>['sticky','fixed'].includes(getComputedStyle(n).position));
            const offset=Math.max(0,...sticky.map(n=>n.getBoundingClientRect().height))+12;
            button.style.scrollMarginTop=offset+'px';
            button.scrollIntoView({block:'start',behavior:'auto'});
        }
    }
    async function load() {
        const current=++generation;
        const res=await fetch('/app/collaboration/personal/data?'+new URLSearchParams({q:search.value,scope}));
        if(!res.ok) throw Error();
        const result=await res.json();if(current!==generation)return;page=result;
        render();
    }
    type.onchange=()=>{save();render();};
    root.querySelectorAll('[data-collaboration-account]').forEach(button=>{
        button.onclick=()=>{
            if(busy)return;
            account.value=button.dataset.collaborationAccount;store=button.dataset.collaborationStore||'';
            const picker=button.closest('details');picker.open=false;
            scopeIdentity(button);
            root.dataset.filterAccount=account.value;root.dataset.filterStore=store;save();render();picker.querySelector('summary').focus();
        };
    });
    const selectedScope=[...root.querySelectorAll('[data-collaboration-account]')].find(b=>b.dataset.collaborationAccount===account.value&&(b.dataset.collaborationStore||'')===store);
    if(selectedScope)scopeIdentity(selectedScope);
    else {account.value='';store='';scopeIdentity(root.querySelector('[data-collaboration-account=""]'));}
    root.dataset.filterAccount=account.value;root.dataset.filterStore=store;
    const scopePicker=root.querySelector('.context-menu');
    scopePicker?.addEventListener('keydown',event=>{
        if(event.key==='Escape'){event.preventDefault();scopePicker.open=false;scopePicker.querySelector('summary').focus();}
    });
    document.addEventListener('pointerdown',event=>{if(scopePicker?.open&&!scopePicker.contains(event.target))scopePicker.open=false;});
    function filteredRows(){
        return page.rows.filter(({card})=>(!account.value||account.value===(card.contextAccount||card.account))&&
            (!type.value||(card.subjectType||page.reviews?.[card.key.id]?.subjectType)===type.value))
            .sort((a,b)=>{
                if(scope==='ASSIGNED')return (a.position??Number.MAX_SAFE_INTEGER)-(b.position??Number.MAX_SAFE_INTEGER);
                const x=page.dates?.[a.card.key.type+a.card.key.id]||'',y=page.dates?.[b.card.key.type+b.card.key.id]||'';
                return (Date.parse(y)-Date.parse(x)||0)||(a.card.key.type+a.card.key.id).localeCompare(b.card.key.type+b.card.key.id);
            });
    }
    root.querySelector('#personal-download').onclick=()=>{
        const cell=value=>{
            let text=String(value??'');
            if(/^[\s]*[=+\-@]|^[\t\r\n]/.test(text))text="'"+text;
            return '"'+text.replaceAll('"','""')+'"';
        };
        const rows=filteredRows().map(({card:c})=>{
            const d=page.reviews?.[c.key.id];
            return [d?.subjectLabel||c.title,c.title,d?.participants||'',d?.createdAt||'',d?.updatedAt||'',d?.assigneeName||'',d?.dueDate||''];
        });
        const csv=[['Subject','Conversation','People','Created','Last activity','Assigned to','Due date'],...rows].map(r=>r.map(cell).join(',')).join('\r\n');
        const url=URL.createObjectURL(new Blob(['\uFEFF'+csv],{type:'text/csv;charset=utf-8'}));
        const link=document.createElement('a');link.href=url;link.download='collaboration.csv';link.click();setTimeout(()=>URL.revokeObjectURL(url),1000);
    };
    root.querySelectorAll('[data-personal-scope]').forEach(tab=>{
        tab.onclick=event=>{event.preventDefault();if(busy)return;scope=tab.dataset.personalScope;save();
            root.querySelectorAll('[data-personal-scope]').forEach(t=>{t.classList.toggle('active',t===tab);if(t===tab)t.setAttribute('aria-current','page');else t.removeAttribute('aria-current');});
            load().catch(()=>status.textContent='This view could not be loaded.');};
        tab.classList.toggle('active',tab.dataset.personalScope===scope);
        if(tab.dataset.personalScope===scope)tab.setAttribute('aria-current','page');else tab.removeAttribute('aria-current');
    });
window.addEventListener('personal-collaboration-updated',()=>{const anchor=viewport.capture();load().then(()=>viewport.restore(anchor));});
    let searchTimer;
    search.oninput=()=>{save();clearTimeout(searchTimer);searchTimer=setTimeout(()=>load().catch(()=>status.textContent='Search could not be loaded.'),250);};
    load().then(()=>viewport.restore(JSON.parse(sessionStorage.getItem(storage+'.anchor')))).catch(()=>{status.textContent='Your assignments could not be loaded. Refresh to try again.';});
})();
