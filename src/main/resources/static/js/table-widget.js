(()=>{
if(window.NextAiTableWidget)return;
document.addEventListener('pointerdown',event=>document.querySelectorAll('.table-row-warning[open],.order-stage-actions[open]').forEach(details=>{if(!details.contains(event.target))details.open=false}));
document.addEventListener('keydown',event=>{if(event.key==='Escape')document.querySelectorAll('.order-stage-actions[open]').forEach(details=>{details.open=false;details.querySelector('summary')?.focus()})});
function read(key){return window.NextAiTablePreferences?.read(key)||null}
function write(key,value){window.NextAiTablePreferences?.write(key,value)}
function actionTrigger(element,warning){
  element.classList.add('table-action-trigger');
  element.classList.toggle('needs-attention',Boolean(warning));
  element.innerHTML='<svg viewBox="0 0 28 20" aria-hidden="true"><circle cx="5" cy="10" r="1.3"/><circle cx="10" cy="10" r="1.3"/><circle cx="15" cy="10" r="1.3"/><path d="m20 8 3 3 3-3"/></svg>';
  if(warning){const badge=document.createElement('span');badge.className='table-action-alert';badge.textContent='!';badge.setAttribute('aria-hidden','true');element.append(badge)}
}
function metadata(el,index){
  const title=el.dataset.title||el.textContent.replace(/[↑↓]/g,'').trim();
  const id=el.dataset.column||title.toLowerCase().replace(/[^a-z0-9]+/g,'-').replace(/(^-|-$)/g,'')||'column-'+index;
  el.dataset.column=id;
  return{id,title,required:el.dataset.columnRequired==='true'||index===0||['action','actions'].includes(id),defaultVisible:el.dataset.defaultHidden!=='true'};
}
function tableColumns(root){return [...root.querySelectorAll('thead th')].map((th,index)=>{const column=metadata(th,index);[...root.tBodies].forEach(body=>[...body.rows].forEach(row=>{if(row.cells[index]&&!row.cells[index].hasAttribute('colspan'))row.cells[index].dataset.column=column.id}));return column})}
function gridColumns(root){
  if(root.dataset.gridRow){const headings=[...root.querySelectorAll('.table-grid-header>*')];headings.forEach(metadata);root.querySelectorAll(root.dataset.gridRow).forEach(row=>[...row.children].forEach((cell,index)=>{if(headings[index])cell.dataset.column=headings[index].dataset.column}))}
  if(root.dataset.tableWidget==='orders')root.querySelectorAll('.order-item').forEach(row=>{
    const set=(el,id)=>{if(el){el.dataset.column=id;el.dataset.title=id}};
    set(row.querySelector('.item-product'),'product');set(row.querySelector('.item-order-reference'),'order');
    const numbers=row.querySelectorAll('.item-number');['quantity','sales','buy-box','shipping','available'].forEach((id,index)=>set(numbers[index],id));
    set(row.querySelector('.item-map'),'catalogue');set(row.querySelector('.item-actions'),'action');
  });
  const seen=new Map();root.querySelectorAll('.table-grid-header [data-column]').forEach((el,index)=>seen.set(el.dataset.column,metadata(el,index)));
  root.querySelectorAll('[data-column]').forEach((el,index)=>{if(!seen.has(el.dataset.column))seen.set(el.dataset.column,metadata(el,index))});
  return [...seen.values()];
}
function prepareContextColumn(root){
  const isTable=root.matches('table'),header=root.querySelector(isTable?'thead tr':'.table-grid-header');
  if(!header||header.querySelector('[data-column=record-context]'))return;
  const records=isTable?[...root.querySelectorAll('tbody tr')].filter(row=>!row.querySelector('[colspan]')):[...root.querySelectorAll('.order-item')];
  const hydrate=new Map();
  records.filter(row=>row.dataset.entityType&&row.dataset.entityId&&!row.querySelector('.collaboration-row-button')).forEach(row=>{
    const button=document.createElement('button');button.type='button';button.className='collaboration-row-button';
    for(const name of ['entityType','entityId','title','canCollaborate'])button.dataset[name]=row.dataset[name]||'';
    button.dataset.canCollaborate=row.dataset.canCollaborate==='true'?'true':'false';
    button.dataset.parentUrl=location.pathname;button.dataset.totalCount='0';button.dataset.activeCount='0';
    button.title='Start collaboration';button.setAttribute('aria-label','Start collaboration for '+(row.dataset.title||'this record'));
    button.innerHTML='<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M5 5.5h14v9H9l-4 4v-13Z"/></svg>';
    button.onclick=()=>window.openRecordCollaboration?.(button);
    row.lastElementChild.append(button);
    if(!hydrate.has(button.dataset.entityType))hydrate.set(button.dataset.entityType,[]);
    hydrate.get(button.dataset.entityType).push(button);
  });
  // Batched, tenant-authorized summaries: never a request per row.
  hydrate.forEach(async(buttons,type)=>{
    for(let offset=0;offset<buttons.length;offset+=250){
      const batch=buttons.slice(offset,offset+250),params=new URLSearchParams({entityType:type});
      [...new Set(batch.map(button=>button.dataset.entityId))].forEach(id=>params.append('entityId',id));
      try{
        const response=await fetch('/app/collaboration/summaries?'+params,{headers:{Accept:'application/json'}});
        if(!response.ok)throw new Error('History unavailable');
        const summaries=await response.json();
        batch.forEach(button=>{const summary=summaries[button.dataset.entityId]||{};
          ['activeCount','totalCount','closedCount'].forEach(key=>button.dataset[key]=String(summary[key]||0));
          window.prepareRecordCollaboration?.(button);
        });
      }catch(_){batch.forEach(button=>button.title='Conversation history unavailable. Click to retry.')}
    }
  });
  // Picture-first rows own their chat and actions on the product image.  Do not
  // create the legacy leading context rail beside an image-based product cell.
  if(records.some(row=>row.querySelector('[data-picture-actions]'))){
    root.classList.add('table-picture-first');
    records.forEach(row=>{
      const action=row.querySelector('[data-context-action]');
      if(action){
        const shelf=row.querySelector('.shelf-status:not(.healthy):not(.undated)');
        action.dataset.warningText=shelf?shelf.parentElement.textContent.trim():'';
        action.title=(action.dataset.warningText? action.dataset.warningText+' · ':'')+'Inventory actions';
      }
    });
    if(!isTable)root.querySelectorAll('.order-actions').forEach(el=>el.hidden=true);
    return;
  }
  if(!records.some(row=>row.querySelector('.collaboration-row-button')||row.closest('.order-row')?.querySelector('.collaboration-row-button')))return;
  if(header.firstElementChild)header.firstElementChild.dataset.columnRequired='true';
  const heading=document.createElement(isTable?'th':'span');heading.dataset.column='record-context';heading.dataset.title='Conversation and alerts';heading.dataset.columnRequired='true';heading.dataset.noSort='true';heading.setAttribute('aria-label','Conversation and alerts');header.prepend(heading);
  if(isTable)root.querySelectorAll('tbody [colspan]').forEach(cell=>cell.colSpan++);
  records.forEach(row=>{
    const context=row.closest('.order-row')||row,source=context.querySelector('.collaboration-row-button');
    const cell=document.createElement(isTable?'td':'div');cell.dataset.column='record-context';cell.className='table-context-cell';row.prepend(cell);
    if(source){const button=isTable?source:source.cloneNode(true);cell.append(button);window.prepareRecordCollaboration?.(button)}
    {
      const shelf=row.querySelector('.shelf-status:not(.healthy):not(.undated)');
      const warning=context.querySelector('.shortage-message')||(shelf?shelf.parentElement:null)||row.querySelector('[data-row-warning]');
      const action=row.querySelector('[data-context-action]');
      if(action){
        cell.append(action);
        action.classList.add('table-context-action');
        action.classList.toggle('needs-attention',Boolean(warning));
        actionTrigger(action,warning);
        action.dataset.warningText=warning?warning.textContent.trim():'';
        action.title=(warning?warning.textContent.trim()+' · ':'')+'Inventory actions';
        action.setAttribute('aria-label',(warning?'Review warning and actions for ':'Actions for ')+(row.dataset.product||'this record'));
      }else if(warning||row.querySelector('[data-context-menu]')){
        const menu=row.querySelector('[data-context-menu]');
        const details=document.createElement('details');details.className='table-row-warning';
        const summary=document.createElement('summary');summary.textContent=warning?'!':'⋯';summary.title=menu?'Document actions':'Stock needs attention';summary.setAttribute('aria-label',menu?'Actions for '+(menu.dataset.title||'this record'):'Show stock warning');
        actionTrigger(summary,warning);
        summary.title=warning?'Needs attention · Open actions':'Open actions';
        summary.setAttribute('aria-label',(warning?'Needs attention. ':'')+(menu?'Open actions for '+(menu.dataset.title||'this record'):'Open stock actions'));
        details.classList.toggle('table-row-menu',!warning);
        const panel=document.createElement('div');panel.className='table-warning-panel';
        if(warning&&!menu){if(warning.matches('td')){const copy=document.createElement('div');copy.textContent=warning.textContent.trim();panel.append(copy)}else panel.append(warning.cloneNode(true));}
        if(menu)panel.append(menu);
        const content=menu||panel.querySelector('.shortage-message')||panel;
        const header=document.createElement('header');header.className='table-action-heading';
        const title=document.createElement('strong');title.textContent=menu?.dataset.title||context.querySelector('.item-product-copy strong,.item-product strong')?.textContent||'Inventory readiness';
        const subtitle=document.createElement('small');subtitle.textContent=warning?(warning.querySelector('span')?.textContent||warning.textContent).trim():menu?.querySelector('p')?.textContent||'Choose an action';
        const copy=document.createElement('div');copy.append(title,subtitle);
        const close=document.createElement('button');close.type='button';close.textContent='×';close.setAttribute('aria-label','Close actions');close.onclick=()=>{details.open=false;summary.focus()};
        header.append(copy,close);panel.prepend(header);
        if(menu){menu.querySelector(':scope > strong')?.remove();menu.querySelector(':scope > p')?.remove();}
        else content.querySelector(':scope > span:first-child')?.remove();
        content.querySelectorAll('a,button').forEach(control=>{
          if(control.closest('.table-action-heading'))return;
          control.classList.add('table-action-entry');
          const icon=document.createElement('span');icon.className='table-action-icon';icon.setAttribute('aria-hidden','true');
          icon.textContent=control.matches('[data-open-selection]')?'⇉':menu?'↗':'±';
          const label=document.createElement('span');label.className='table-action-label';
          const strong=document.createElement('strong');strong.textContent=control.textContent.trim();
          const small=document.createElement('small');small.textContent=control.matches('[data-open-selection]')?'Open your checked documents in one workspace':menu?'Open only this document; keep your selection':'Update stock for the mapped products';
          label.append(strong,small);control.replaceChildren(icon,label);
        });
        details.append(summary,panel);cell.append(details);
        details.addEventListener('toggle',()=>{if(details.open){const box=summary.getBoundingClientRect();panel.style.top=Math.max(12,Math.min(box.bottom+6,innerHeight-panel.offsetHeight-12))+'px';panel.style.left=Math.max(12,Math.min(box.right+8,innerWidth-312))+'px'}});
        details.addEventListener('keydown',event=>{if(event.key==='Escape'){details.open=false;summary.focus()}});
        (root.closest('.table-wrap')||root).addEventListener('scroll',()=>details.open=false,{passive:true});
      }
    }
  });
  if(isTable){
    const actionHeading=header.querySelector('[data-context-actions]');
    if(actionHeading){
      const index=[...header.children].indexOf(actionHeading);
      records.forEach(row=>row.cells[index]?.remove());
      actionHeading.remove();
      root.querySelectorAll('tbody [colspan]').forEach(cell=>cell.colSpan--);
    }
  }
  if(!isTable)root.querySelectorAll('.order-actions').forEach(el=>el.hidden=true);
}
function prepareSalesBars(root){
  root.querySelectorAll('.weekly-sales:not([data-bars-ready])').forEach(el=>{
    const values=el.textContent.split('|').map(value=>Number(value.trim()));
    if(values.length!==4||values.some(value=>!Number.isFinite(value)||value<0))return;
    el.dataset.barsReady='true';el.parentElement.dataset.exportValue=values.join(' | ');
    el.classList.add('weekly-sales-bars');el.textContent='';
    const max=Math.max(1,...values),anchor=new Date(root.dataset.salesAsOf||Date.now());
    values.forEach((value,index)=>{
      const bar=document.createElement('span');bar.textContent=String(value);bar.style.height=(18+26*value/max)+'px';
      const end=new Date(anchor.getTime()-(3-index)*7*86400000),start=new Date(end.getTime()-7*86400000);
      bar.title=start.toLocaleDateString()+' – '+end.toLocaleDateString()+': '+value+' units (rolling 7 days)';
      bar.setAttribute('aria-label',bar.title);bar.tabIndex=0;el.append(bar);
    });
    el.removeAttribute('title');
  });
}
function init(root){
  const key=root.dataset.tableWidget;if(!key)return;
  prepareContextColumn(root);
  prepareSalesBars(root);
  root.classList.add('table-widget');
  const isTable=root.matches('table'),defaults=isTable?tableColumns(root):gridColumns(root);if(!defaults.length)return;
  const stored=read(key)||{},saved=stored.schema===2?stored:{},valid=id=>defaults.some(c=>c.id===id);
  let order=[...(saved.order||[])].filter(valid);defaults.forEach(c=>{if(!order.includes(c.id))order.push(c.id)});
  if(order.includes('record-context'))order=['record-context',...order.filter(id=>id!=='record-context')];
  const visibility=Object.fromEntries(defaults.map(c=>[c.id,c.required?true:(saved.visibility?.[c.id]??c.defaultVisible)]));
  let host=document.querySelector('[data-column-control="'+key+'"]');
  if(!host){host=document.createElement('div');host.dataset.columnControl=key;(root.closest('.table-wrap')||root).before(host)}
  const column=id=>defaults.find(c=>c.id===id);
  if(root.dataset.tableMinWidth)root.style.setProperty('--table-min-width',Number(root.dataset.tableMinWidth)+'px');
  function apply(){
    defaults.filter(c=>c.required).forEach(c=>visibility[c.id]=true);
    if(isTable){
      [...root.rows].forEach(row=>{const cells=[...row.cells],byId=new Map(cells.map((cell,index)=>[cell.dataset.column||defaults[index]?.id,cell]));order.forEach(id=>{const cell=byId.get(id);if(cell){cell.hidden=!visibility[id];row.appendChild(cell)}})});
      const visible=defaults.filter(c=>visibility[c.id]&&c.id!=='record-context');
      const weight=id=>Number(root.querySelector('thead [data-column="'+id+'"]')?.dataset.columnWeight)||(['product','listing','vendor','source'].includes(id)?2.5:['action','actions'].includes(id)?1.2:1);
      const total=visible.reduce((sum,c)=>sum+weight(c.id),0);
      const context=defaults.some(c=>c.id==='record-context')?Math.min(8,48/Math.max(320,root.getBoundingClientRect().width)*100):0;
      // Fixed table layout ignores mixed percentage/pixel calc widths in browsers.
      root.querySelectorAll('thead th').forEach(th=>{th.style.width=(th.dataset.column==='record-context'?context:weight(th.dataset.column)/total*(100-context))+'%'});
    }else{
      root.querySelectorAll('[data-column]').forEach(el=>{el.hidden=!visibility[el.dataset.column];el.style.order=String(order.indexOf(el.dataset.column))});
      root.style.setProperty('--visible-columns',order.filter(id=>visibility[id]).length);
    }
    write(key,{schema:2,order,visibility});
  }
  const eye=open=>open?'<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M2.5 12s3.4-6 9.5-6 9.5 6 9.5 6-3.4 6-9.5 6-9.5-6-9.5-6Z"/><circle cx="12" cy="12" r="2.6"/></svg>':'<svg viewBox="0 0 24 24" aria-hidden="true"><path d="m3 3 18 18M10.6 6.2A10.8 10.8 0 0 1 12 6c6.1 0 9.5 6 9.5 6a16.3 16.3 0 0 1-3 3.8M6.2 6.2C3.9 8.1 2.5 12 2.5 12s3.4 6 9.5 6c1.4 0 2.7-.3 3.8-.8"/><path d="M9.8 9.8a3.1 3.1 0 0 0 4.4 4.4"/></svg>';
  function panelOrder(){return [...order.filter(id=>column(id)?.required),...order.filter(id=>!column(id)?.required)]}
  function render(filter=''){
    if(!host)return;const list=host.querySelector('.table-column-list'),query=filter.toLowerCase();list.innerHTML='';
    panelOrder().map(column).filter(Boolean).filter(c=>c.title.toLowerCase().includes(query)).forEach(c=>{
      const row=document.createElement('div');row.className='table-column-option'+(c.required?' required':'');
      row.innerHTML='<button type="button" class="column-eye" aria-pressed="'+visibility[c.id]+'">'+eye(visibility[c.id])+'</button><span></span><em></em><button type="button">↑</button><button type="button">↓</button>';
      row.querySelector('span').textContent=c.title;row.querySelector('em').textContent=c.required?'Required':'';
      const buttons=row.querySelectorAll('button');buttons[0].disabled=c.required;
      buttons[0].setAttribute('aria-label','Toggle '+c.title);buttons[1].setAttribute('aria-label','Move '+c.title+' left');buttons[2].setAttribute('aria-label','Move '+c.title+' right');
      buttons[0].onclick=()=>{if(c.required)return;visibility[c.id]=!visibility[c.id];apply();render(filter);if(visibility[c.id])reveal(c.id)};
      buttons[1].onclick=()=>move(c.id,-1,filter);buttons[2].onclick=()=>move(c.id,1,filter);list.appendChild(row);
    });
  }
  function move(id,amount,filter){const index=order.indexOf(id),next=index+amount;if(id==='record-context'||order[next]==='record-context'||next<0||next>=order.length)return;[order[index],order[next]]=[order[next],order[index]];apply();render(filter)}
  function reveal(id){const target=root.querySelector('thead [data-column="'+id+'"],.table-grid-header [data-column="'+id+'"]');if(target)target.scrollIntoView({block:'nearest',inline:'nearest',behavior:'smooth'})}
  function placePanel(details){
    const panel=details.querySelector('.table-column-panel');panel.classList.remove('opens-up');panel.style.removeProperty('--panel-max-height');
    const trigger=details.querySelector('summary').getBoundingClientRect(),below=innerHeight-trigger.bottom-16,above=trigger.top-16;
    const opensUp=below<360&&above>below;panel.classList.toggle('opens-up',opensUp);panel.style.position='fixed';
    panel.style.right=Math.max(12,innerWidth-trigger.right)+'px';panel.style.left='auto';
    panel.style.top=opensUp?'auto':Math.max(12,trigger.bottom+8)+'px';
    panel.style.bottom=opensUp?Math.max(12,innerHeight-trigger.top+8)+'px':'auto';
    panel.style.setProperty('--panel-max-height',Math.max(220,Math.min(590,opensUp?above:below))+'px');
  }
  if(host){
    host.innerHTML='<details class="table-column-control"><summary class="secondary-button compact-button" title="Choose table columns"><span aria-hidden="true">☷</span> Columns</summary><div class="table-column-panel"><header><div><strong>Table columns</strong><small>Show, hide, or arrange columns.</small></div><button type="button">Reset default</button></header><label class="table-column-search"><svg viewBox="0 0 24 24" aria-hidden="true"><circle cx="10.5" cy="10.5" r="5.5"></circle><path d="m15 15 5 5"></path></svg><input type="search" placeholder="Find a column"></label><div class="table-column-list"></div><small class="table-column-footnote">Required columns always stay visible. Saved in this browser.</small></div></details>';
    const details=host.querySelector('details');
    details.addEventListener('toggle',()=>{if(details.open){document.querySelectorAll('.table-column-control[open]').forEach(other=>{if(other!==details)other.open=false});render();requestAnimationFrame(()=>placePanel(details))}});
    document.addEventListener('pointerdown',event=>{if(details.open&&!details.contains(event.target))details.open=false});
    document.addEventListener('keydown',event=>{if(event.key==='Escape'&&details.open){details.open=false;details.querySelector('summary').focus()}});
    addEventListener('resize',()=>{if(details.open)placePanel(details)},{passive:true});
    host.querySelector('header button').onclick=()=>{order=defaults.map(c=>c.id);defaults.forEach(c=>visibility[c.id]=c.required||c.defaultVisible);apply();render();host.querySelector('input').value=''};
    host.querySelector('input').oninput=e=>render(e.target.value);render();
  }
  apply();
  requestAnimationFrame(()=>{const scroller=root.closest('.table-wrap');if(scroller)scroller.scrollLeft=0});
}
function initPageJump(form){if(form.dataset.pageJumpReady)return;form.dataset.pageJumpReady='true';const input=form.querySelector('[name="goToPage"]');if(!input)return;let starting=input.value;const clean=()=>{input.value=input.value.replace(/\D/g,'')};input.addEventListener('input',clean);form.addEventListener('submit',event=>{clean();const max=Math.max(1,Number(input.dataset.pageMax)||1),value=Number(input.value);if(!Number.isFinite(value)||value<1){event.preventDefault();input.value='1';input.focus();return}input.value=String(Math.min(max,Math.floor(value)))});input.addEventListener('blur',()=>{clean();if(input.value&&input.value!==starting)form.requestSubmit()});input.addEventListener('focus',()=>input.select())}
function boot(){document.querySelectorAll('.desk-table:not([data-table-widget])').forEach(root=>{root.dataset.tableWidget='shipping-ready';root.dataset.gridRow='.candidate-row';const header=root.querySelector('.desk-table-head');header?.classList.add('table-grid-header');[...header.children].forEach((cell,index)=>{cell.dataset.column=['select','product','order','package','shipping','action'][index];cell.dataset.columnRequired=String(index===0||index===1||index===5);if(index===0)cell.dataset.title='Select'})});document.querySelectorAll('.data-card table:not([data-table-widget])').forEach((root,index)=>{if(root.tHead&&!root.closest('dialog'))root.dataset.tableWidget=location.pathname.replace(/\W+/g,'-')+'-'+index});document.querySelectorAll('[data-table-widget]:not([data-table-widget-ready])').forEach(root=>{root.dataset.tableWidgetReady='true';init(root)});document.querySelectorAll('[data-page-jump]').forEach(initPageJump);window.NextAiTableDataTools?.refresh()}
document.readyState==='loading'?document.addEventListener('DOMContentLoaded',boot):boot();window.NextAiTableWidget={refresh:boot};
})();
