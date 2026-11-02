/* Board preferences and anchors only: never persist task bodies, people or files. */
(()=>{
  'use strict';
  function create(root,userId,tenantId){
    if(!userId||!tenantId)throw Error('A signed-in account is required.');
    const key='collaboration-board:'+encodeURIComponent(userId)+':'+encodeURIComponent(tenantId);
    let generation=0,controller=null;
    const cards=()=>[...root.querySelectorAll('[data-card-id]')].filter(n=>!n.hidden&&n.getClientRects().length);
    function capture(){
      const visible=cards(),anchor=visible.find(n=>n.getBoundingClientRect().bottom>0);
      const focused=document.activeElement,card=focused?.closest('[data-card-id]');
      return {anchor:anchor?.dataset.cardId,offset:anchor?.getBoundingClientRect().top,
        neighbors:visible.map(n=>n.dataset.cardId),index:visible.indexOf(anchor),scrollY:window.scrollY,
        focusCard:root.contains(focused)?card?.dataset.cardId:null,
        focusControl:root.contains(focused)?focused?.dataset.focusKey:null,
        focused,expanded:[...root.querySelectorAll('details[data-group][open]')].map(n=>n.dataset.group)};
    }
    function restore(state){
      if(!state)return;
      for(const details of root.querySelectorAll('details[data-group]'))details.open=state.expanded.includes(details.dataset.group);
      const visible=cards(),byId=new Map(visible.map(n=>[n.dataset.cardId,n]));
      let anchor=byId.get(state.anchor);
      if(!anchor&&state.index>=0){
        for(let delta=1;delta<state.neighbors.length&&!anchor;delta++)
          anchor=byId.get(state.neighbors[state.index+delta])||byId.get(state.neighbors[state.index-delta]);
      }
      if(anchor)window.scrollBy({top:anchor.getBoundingClientRect().top-state.offset,behavior:'instant'});
      else window.scrollTo({top:state.scrollY,behavior:'instant'});
      // Preserve a live toolbar/input focus; only repair focus removed by a card update.
      if((state.focused||state.focusCard)&&!state.focused?.isConnected&&document.activeElement===document.body){
        const card=byId.get(state.focusCard);
        const control=card&&[...card.querySelectorAll('[data-focus-key]')].find(n=>n.dataset.focusKey===state.focusControl);
        (control||card)?.focus({preventScroll:true});
      }
    }
    function preferences(){
      const value=window.NextAiTablePreferences?.read(key)||{},count=Number(value.initialCards);
      return {schema:1,order:value.order||[],visibility:value.visibility||{},
        initialCards:Number.isInteger(count)&&count>=1&&count<=50?count:5,
        scope:typeof value.scope==='string'?value.scope:'ALL',
        groups:Array.isArray(value.groups)?value.groups.filter(g=>typeof g==='string'):[]};
    }
    function savePreferences(value){
      const current=preferences(),count=Number(value.initialCards??current.initialCards);
      window.NextAiTablePreferences?.write(key,{schema:1,
        order:Array.isArray(value.order)?value.order:current.order,
        visibility:value.visibility||current.visibility,
        initialCards:Number.isInteger(count)&&count>=1&&count<=50?count:5,
        scope:typeof value.scope==='string'?value.scope:current.scope,
        groups:Array.isArray(value.groups)?value.groups.filter(g=>typeof g==='string'):current.groups});
    }
    function request(){
      controller?.abort();controller=new AbortController();const expected=++generation;
      return {signal:controller.signal,current:()=>expected===generation&&!controller.signal.aborted};
    }
    function invalidate(){generation++;controller?.abort();}
    return {capture,restore,preferences,savePreferences,request,invalidate};
  }
  window.NextAiCollaborationBoardState={create};
})();
