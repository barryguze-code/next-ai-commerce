/* Table data interactions shared by semantic tables and the Orders grid.
 * Domain filters retain ownership of hidden/style; paging uses its own class.
 * Server paging remains authoritative: local column filters are explicitly scoped.
 */
(()=>{
'use strict';
if(window.NextAiTableDataTools)return;
const states=new Map();
const normalize=value=>String(value??'').replace(/\s+/g,' ').trim();
const csvCell=value=>{let text=normalize(value);if(/^[=+\-@\t\r]/.test(text))text="'"+text;return '"'+text.replace(/"/g,'""')+'"'};
function headers(root){return [...root.querySelectorAll('thead th,.table-grid-header [data-column]')].map((el,index)=>({id:el.dataset.column||'column-'+index,title:el.dataset.title||normalize(el.textContent).replace(/[↑↓]/g,''),el,index})).sort((a,b)=>Number(a.el.style.order||a.index)-Number(b.el.style.order||b.index));}
function rows(root){return root.matches('table')?[...root.tBodies].flatMap(body=>[...body.rows]).filter(row=>![...row.cells].some(cell=>cell.colSpan>1)):[...root.querySelectorAll(root.dataset.gridRow||'.order-item')];}
function textOf(cell){
  if(!cell)return '';
  if(cell.dataset.exportValue!==undefined)return cell.dataset.exportValue;
  const copy=cell.cloneNode(true);
  if(copy.matches('.item-number'))copy.querySelector(':scope > small')?.remove();
  copy.querySelectorAll('svg,img,script,style,.sr-only,.marketplace-links,.product-image-upload,.product-avatar,[hidden]').forEach(el=>el.remove());
  copy.querySelectorAll('button').forEach(el=>el.replaceWith(document.createTextNode(el.textContent)));
  copy.querySelectorAll('input,select,textarea').forEach(el=>el.replaceWith(document.createTextNode(el.type==='checkbox'?(el.checked?'Yes':'No'):el.value)));
  copy.querySelectorAll('div,p,small,strong,span,code,br').forEach(el=>el.append(document.createTextNode(' ')));
  return normalize(copy.textContent);
}
function values(root,row,cols){return cols.map(col=>textOf([...row.children].find(cell=>cell.dataset.column===col.id)||row.children[col.index]));}
// One pager presentation for both local collections and server-backed lists.
function paginationControls(nav,onSize,onPage){
  nav.setAttribute('aria-label','Table pages');
  const sizeLabel=document.createElement('label');sizeLabel.textContent='Rows ';
  const select=document.createElement('select');select.setAttribute('aria-label','Rows per page');
  [25,50,100].forEach(size=>select.add(new Option(String(size),String(size))));sizeLabel.append(select);nav.append(sizeLabel);
  select.onchange=()=>onSize(Number(select.value));
  let current=1,last=1;
  const buttons=[];
  for(const [label,delta] of [['First',-Infinity],['Previous',-1],['Next',1],['Last',Infinity]]){
    const button=document.createElement('button');button.type='button';button.className='secondary-button compact-button';button.textContent=label;button.dataset.pageDelta=String(delta);
    button.onclick=()=>onPage(delta===-Infinity?1:delta===Infinity?last:current+delta);nav.append(button);buttons.push(button);
  }
  const jump=document.createElement('label');jump.className='table-local-page';jump.append('Page ');
  const input=document.createElement('input');input.type='number';input.min='1';input.setAttribute('aria-label','Page number');
  input.onchange=()=>onPage(Math.max(1,Math.min(last,Math.floor(Number(input.value)||1))));
  input.onkeydown=event=>{if(event.key==='Enter'){event.preventDefault();input.onchange()}};
  const total=document.createElement('span');jump.append(input,total);buttons[2].before(jump);
  return (page,pages,size)=>{current=page;last=pages;input.value=String(page);input.max=String(pages);select.value=String(size);total.textContent='of '+pages;buttons.forEach((button,index)=>button.disabled=index<2?page===1:page===pages)};
}
function init(root){
  if(root.dataset.dataToolsReady)return;
  const cols=headers(root);if(!cols.length)return;
  const host=[...document.querySelectorAll('[data-column-control]')].find(el=>el.dataset.columnControl===root.dataset.tableWidget);
  if(!host)return;
  root.dataset.dataToolsReady='true';
  const card=root.closest('.data-card,.receive-lines,.desk-card')||root.parentElement;
  let serverPager=card.querySelector('.table-pagination');
  if(root.dataset.tableWidget==='receiving-lines'){serverPager?.remove();serverPager=null;root.querySelectorAll('tbody tr').forEach(row=>row.hidden=false)}
  const isServer=!!serverPager&&(serverPager.dataset.page!==undefined||!!serverPager.querySelector('a[href],form[data-page-jump]'));
  const saved=states.get(root.dataset.tableWidget)||{page:1,size:25,query:'',filters:{}};
  states.set(root.dataset.tableWidget,saved);
  const toolbar=document.createElement('div');toolbar.className='table-standard-toolbar';
  const container=root.closest('.table-wrap,.receive-table-wrap')||root;
  container.before(toolbar);toolbar.append(host);host.classList.add('table-widget-tools');
  let search=card.querySelector('input[type="search"]:not(.table-column-search input)');
  // Reuse existing search handlers (including catalogue lookups and server queries).
  if(search){
    const form=search.closest('form');
    const searchGroup=form||search.closest('label')||search;
    if(!searchGroup.contains(root)&&!searchGroup.contains(toolbar))toolbar.prepend(searchGroup);
    if(!search.getAttribute('aria-label'))search.setAttribute('aria-label','Search table');
  }else{
    const label=document.createElement('label');label.className='table-standard-search';
    label.innerHTML='<input type="search" aria-label="Search table" placeholder="Search this table">';
    toolbar.prepend(label);search=label.querySelector('input');search.value=saved.query;
    search.addEventListener('input',()=>{saved.query=search.value;saved.page=1;render()});
  }
  // One search presentation, retaining the original input and its domain listeners.
  let searchLabel=search.closest('label');
  if(!searchLabel){searchLabel=document.createElement('label');search.before(searchLabel);searchLabel.append(search)}
  searchLabel.classList.add('table-standard-search');
  searchLabel.querySelectorAll(':scope > svg,:scope > span').forEach(icon=>icon.remove());
  searchLabel.insertAdjacentHTML('afterbegin','<svg viewBox="0 0 24 24" aria-hidden="true"><circle cx="10.5" cy="10.5" r="6.5"/><path d="m16 16 4.5 4.5"/></svg>');
  toolbar.classList.add('table-widget-tools');
  const filters=document.createElement('details');filters.className='table-filter-control';
  const summary=document.createElement('summary');summary.className='secondary-button compact-button';summary.textContent='Filters';filters.append(summary);
  const panel=document.createElement('div');panel.className='table-filter-panel';filters.append(panel);
  const hint=document.createElement('p');hint.textContent=isServer?'Filter rows on this page. Search above searches all results.':'Match values in one or more columns.';panel.append(hint);
  const filterCols=cols.filter(col=>!['action','actions','record-context'].includes(col.id));
  filterCols.forEach(col=>{
    const label=document.createElement('label');label.textContent=col.title;
    const input=document.createElement('input');input.type='search';input.placeholder='Contains…';input.value=saved.filters[col.id]||'';
    input.setAttribute('aria-label','Filter '+col.title);input.addEventListener('input',()=>{saved.filters[col.id]=input.value;saved.page=1;render()});label.append(input);panel.append(label);
  });
  const clear=document.createElement('button');clear.type='button';clear.className='secondary-button compact-button';clear.textContent='Clear column filters';
  clear.onclick=()=>{saved.filters={};panel.querySelectorAll('input').forEach(input=>input.value='');saved.page=1;render()};panel.append(clear);host.prepend(filters);
  const clearAll=document.createElement('button');clearAll.type='button';clearAll.className='table-clear-active-filters';clearAll.hidden=true;clearAll.setAttribute('aria-label','Clear all table filters');clearAll.title='Clear all filters';clearAll.innerHTML='<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M3 4h18l-7 8v7l-4 2v-9L3 4m13 12 5 5m0-5-5 5"/></svg>';host.prepend(clearAll);
  const hasDomainFilter=()=>{const url=new URL(location.href);return !!url.searchParams.get('q')||!!(url.searchParams.get('status')&&url.searchParams.get('status')!=='ALL')};
  function updateActiveFilterState(){const columnCount=Object.values(saved.filters).filter(Boolean).length;const searchActive=!!search.value.trim();const active=columnCount>0||searchActive||hasDomainFilter();const total=columnCount+(hasDomainFilter()?1:0);toolbar.classList.toggle('table-has-active-filters',active);filters.classList.toggle('has-active-filters',active);summary.textContent='Filters'+(total?' · '+total:'');clearAll.hidden=false;clearAll.disabled=!active;}
  clearAll.onclick=()=>{saved.filters={};panel.querySelectorAll('input').forEach(input=>input.value='');saved.page=1;if(isServer||hasDomainFilter()){const url=new URL(location.href);url.searchParams.delete('q');url.searchParams.delete('status');url.searchParams.delete('page');location.assign(url);return;}search.value='';saved.query='';search.dispatchEvent(new Event('input',{bubbles:true}));render()};
  filters.addEventListener('toggle',()=>{if(filters.open){const box=summary.getBoundingClientRect();panel.style.top=Math.min(box.bottom+8,Math.max(12,innerHeight-360))+'px';panel.style.left=Math.max(12,Math.min(box.left,innerWidth-332))+'px'}});
  const close=event=>{if(!root.isConnected){document.removeEventListener('pointerdown',close);return}if(!filters.contains(event.target))filters.open=false};document.addEventListener('pointerdown',close);
  filters.addEventListener('keydown',event=>{if(event.key==='Escape'){filters.open=false;summary.focus()}});
  const exportButton=document.createElement('button');exportButton.type='button';exportButton.className='secondary-button compact-button table-export';
  exportButton.innerHTML='<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M12 3v12m-5-5 5 5 5-5M4 16v5h16v-5"/></svg>';
  exportButton.setAttribute('aria-label','Download table as CSV');
  exportButton.title=isServer?'Download all matching pages with your visible columns':'Download all filtered rows with your visible columns';host.append(exportButton);
  const status=document.createElement('span');status.className='table-data-status';status.setAttribute('role','status');toolbar.after(status);
  let footer=null,pageLabel=null,updatePager=null;
  if(isServer){
    serverPager.classList.add('table-footer','table-standard-pagination');
    const oldJump=serverPager.querySelector('[name=goToPage]');
    const page=serverPager.dataset.page!==undefined?Number(serverPager.dataset.page)+1:Number(oldJump?.value)||1;
    const pages=Number(serverPager.dataset.pageMax)||Number(oldJump?.dataset.pageMax)||1;
    const size=Number(serverPager.dataset.pageSize)||Number(new URL(location.href).searchParams.get('size'))||25;
    const summary=serverPager.firstElementChild,nav=document.createElement('nav');
    serverPager.replaceChildren(...(summary?[summary]:[]),nav);
    const navigate=(page,size)=>{const url=new URL(location.href);url.searchParams.set('size',String(size));url.searchParams.set('page',String(page-1));url.searchParams.delete('goToPage');location.assign(url)};
    paginationControls(nav,value=>navigate(1,value),value=>navigate(value,size))(page,pages,size);
  }else if(!serverPager){
    footer=document.createElement('div');footer.className='table-footer table-pagination table-standard-pagination';container.after(footer);
    pageLabel=document.createElement('span');pageLabel.setAttribute('aria-live','polite');footer.append(pageLabel);
    const nav=document.createElement('nav');footer.append(nav);
    updatePager=paginationControls(nav,size=>{saved.size=size;saved.page=1;render()},page=>{saved.page=page;render()});
    const previousFooter=card.querySelector('.table-footer:not(.table-pagination)');
    if(previousFooter&&/^Showing\b/.test(normalize(previousFooter.textContent))){previousFooter.querySelectorAll('a').forEach(link=>footer.append(link));previousFooter.hidden=true}
  }
  function matches(row){const data=values(root,row,cols);return (!saved.query||data.join(' ').toLowerCase().includes(saved.query.toLowerCase()))&&cols.every((col,index)=>!saved.filters[col.id]||data[index].toLowerCase().includes(saved.filters[col.id].toLowerCase()));}
  function domainVisible(row){return !row.hidden&&row.style.display!=='none'&&!row.closest('[hidden]');}
  function render(){
    if(root.dataset.gridRow){const current=headers(root);rows(root).forEach(row=>[...row.children].forEach((cell,index)=>{const col=cols.find(c=>c.index===index);if(!col)return;cell.dataset.column=col.id;const header=current.find(c=>c.id===col.id)?.el,hidden=!!header?.hidden,order=header?.style.order||String(index);if(cell.hidden!==hidden)cell.hidden=hidden;if(cell.style.order!==order)cell.style.order=order}))}
    const all=rows(root),filtered=all.filter(row=>domainVisible(row)&&matches(row));
    const pages=Math.max(1,Math.ceil(filtered.length/saved.size));saved.page=Math.min(pages,Math.max(1,saved.page));
    const shown=new Set(footer?filtered.slice((saved.page-1)*saved.size,saved.page*saved.size):filtered);
    all.forEach(row=>row.classList.toggle('table-data-hidden',!shown.has(row)));
    if(!root.matches('table'))root.querySelectorAll('.order-row').forEach(row=>row.classList.toggle('table-data-hidden',![...row.querySelectorAll('.order-item')].some(item=>!item.classList.contains('table-data-hidden'))));
    const count=Object.values(saved.filters).filter(Boolean).length;updateActiveFilterState();
    const orderEmpty=root.querySelector('.order-empty-state');
    if(orderEmpty){const hide=filtered.length>0;if(orderEmpty.hidden!==hide)orderEmpty.hidden=hide;root.classList.toggle('orders-is-empty',!hide);if(serverPager&&serverPager.hidden===hide)serverPager.hidden=!hide;}
    if(!exportButton.disabled)status.textContent=!filtered.length?(orderEmpty?'':'Nothing found. Try another search or change your filters.'):count&&isServer?filtered.length+' matches on this page.':'';
    if(footer){footer.dataset.pages=String(pages);updatePager(saved.page,pages,saved.size);pageLabel.textContent=filtered.length?'Showing '+((saved.page-1)*saved.size+1)+'–'+Math.min(saved.page*saved.size,filtered.length)+' of '+filtered.length+' · Page '+saved.page+' of '+pages:'0 rows'}
  }
  exportButton.onclick=async()=>{
    exportButton.disabled=true;status.textContent='Preparing CSV…';
    const progress=window.NextAiBackgroundJobs?.start('Download '+root.dataset.tableWidget,'Preparing CSV. Keep this page open until the download starts.');
    try{
      const selected=headers(root).filter(col=>!col.el.hidden&&!['select','action','actions','record-context'].includes(col.id));
      const exportFilters={...saved.filters};
      let data=[];
      if(isServer){
        // Fetch only the existing authenticated list route. Never call sync/action endpoints.
        let url=new URL(location.href);const exportSize=serverPager.dataset.pageSize||url.searchParams.get('size')||'25';url.searchParams.set('size',exportSize);url.searchParams.set('page','0');url.searchParams.delete('goToPage');const visited=new Set();
        for(let page=0;url;page++){
          if(page>=1000||visited.has(url.href))throw new Error('Too many pages. Narrow the search and try again.');visited.add(url.href);
          const response=await fetch(url,{credentials:'same-origin',headers:{Accept:'text/html'}});if(!response.ok||new URL(response.url).pathname!==location.pathname)throw new Error('Session or page unavailable. Refresh and try again.');
          const doc=new DOMParser().parseFromString(await response.text(),'text/html');
          const source=[...doc.querySelectorAll('[data-table-widget]')].find(el=>el.dataset.tableWidget===root.dataset.tableWidget);
          if(!source)throw new Error('Could not read table data. Please refresh and try again.');
          const sourceCols=headers(source);sourceCols.forEach((col,index)=>{if(!col.el.dataset.column)col.id=cols.find(c=>c.title===col.title)?.id||col.id;col.index=index});
          const mapped=selected.map(col=>({...col,index:sourceCols.find(c=>c.id===col.id)?.index??col.index}));
          for(const row of rows(source)){
            const allValues=values(source,row,sourceCols);
            if(sourceCols.every((col,index)=>!exportFilters[col.id]||allValues[index].toLowerCase().includes(exportFilters[col.id].toLowerCase())))data.push(values(source,row,mapped));
          }
          const next=[...(source.closest('.data-card')||doc).querySelectorAll('.table-pagination a[href]')].find(link=>normalize(link.textContent).toLowerCase()==='next');
          url=next?new URL(next.getAttribute('href'),location.href):null;
          if(url&&(url.origin!==location.origin||url.pathname!==location.pathname))throw new Error('Unexpected pagination link. Export stopped.');
          if(url)url.searchParams.set('size',exportSize);
          status.textContent='Preparing CSV · '+data.length+' rows…';
          progress?.update(status.textContent);
        }
      }else data=rows(root).filter(row=>domainVisible(row)&&matches(row)).map(row=>values(root,row,selected));
      const csv='\uFEFF'+[selected.map(col=>col.title),...data].map(row=>row.map(csvCell).join(',')).join('\r\n');
      const url=URL.createObjectURL(new Blob([csv],{type:'text/csv;charset=utf-8'}));const link=document.createElement('a');link.href=url;link.download=root.dataset.tableWidget+'-'+new Date().toISOString().slice(0,10)+'.csv';link.click();setTimeout(()=>URL.revokeObjectURL(url),1000);
      status.textContent='Exported '+data.length+' '+(data.length===1?'row.':'rows.');
      progress?.complete(status.textContent);
    }catch(error){status.textContent='Export failed: '+error.message;progress?.fail(status.textContent)}finally{exportButton.disabled=false}
  };
  // Page-specific search/status handlers can still set hidden without fighting pagination.
  const observer=new MutationObserver(records=>{if(!root.isConnected){observer.disconnect();return}if(records.some(record=>record.type==='childList'||record.attributeName==='hidden'||record.attributeName==='style'))render()});
  observer.observe(root,{subtree:true,childList:true,attributes:true,attributeFilter:['hidden','style']});
  search.addEventListener('input',()=>{saved.page=1;updateActiveFilterState();queueMicrotask(render)});
  root.addEventListener('table:filter',()=>{saved.page=1;render()});
  root.addEventListener('table:reset-filters',()=>{saved.filters={};saved.query='';saved.page=1;panel.querySelectorAll('input').forEach(input=>input.value='');render()});
  render();
}
function refresh(){document.querySelectorAll('[data-table-widget-ready]').forEach(init)}
window.NextAiTableDataTools={refresh,csvCell,resetFilters:key=>{const saved=states.get(key);if(saved){saved.filters={};saved.query='';saved.page=1;}}};
if(document.readyState==='loading')document.addEventListener('DOMContentLoaded',()=>{window.NextAiTableWidget?.refresh();refresh()});else{window.NextAiTableWidget?.refresh();refresh()}
})();
