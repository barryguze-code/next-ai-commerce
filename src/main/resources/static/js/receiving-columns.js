(()=>{
 const upload=document.querySelector('#session-dialog form');if(!upload)return;
 const dialog=document.getElementById('receiving-columns-dialog'),body=dialog.querySelector('[data-column-matches]'),error=dialog.querySelector('[role=alert]');
 let pending=false,ready=false,previews=[];

 const aliases={AccountSKU:['accountsku'],VendorItemCode:['itemcode','vendoritemcode','vendorsku','itemnumber','itemno','sku','shipitem'],UPC:['upc','ean','gtin','barcode','upcean'],Description:['itemname','description','productname','itemdescription','title','product'],Quantity:['shipquantity','shippedquantity','quantity','qty','orderedquantity','orderqty','shippedqty'],UnitsPerCase:['unitspercase','casepack','packsize'],UnitCost:['neteach','unitcost','unitprice','price','cost']};
 function element(tag,text,cls){const el=document.createElement(tag);if(text!=null)el.textContent=text;if(cls)el.className=cls;return el;}
 function feedback(message){error.textContent=message;error.hidden=!message;if(message){error.tabIndex=-1;error.focus();}}
 function build(index,headings,previous){
  const preview=previews[index],sheet=preview.headed;
  const section=element('section',null,'mapping-card');section.dataset.fileIndex=index;
  section.append(element('strong',preview.filename));
  const selects={};
  function columnField(key,title,required=false){
   const label=element('label'),span=element('span',title+(required?' · Required':'')),select=element('select');select.dataset.field=key;select.setAttribute('aria-label',title+' · '+preview.filename);select.setAttribute('data-standard-choice','');select.required=required;select.append(new Option(required?'Choose uploaded column':'Not mapped',''));
   sheet.headers.forEach((header,i)=>{const sample=sheet.samples.map(row=>row[header]).find(value=>value!=null&&String(value).trim()!=='');const snippet=sample==null?'':String(sample).trim();const text=header+(!headings&&snippet?' — '+(snippet.length>55?snippet.slice(0,55)+'…':snippet):'');select.append(new Option(text,String(i)));});
   let selected=previous?.[key];if(selected===undefined){const matches=sheet.headers.map((h,i)=>({h,i})).filter(({h})=>aliases[key].includes(h.toLowerCase().replace(/[^a-z0-9]/g,''))&&(key!=='Description'||sheet.samples.some(row=>String(row[h]??'').trim()!=='')));selected=matches.length===1?String(matches[0].i):'';}select.value=selected;selects[key]=select;label.append(span,select);return label;
  }
  section.append(element('h3','Required'));
  const requiredGrid=element('div',null,'mapping-grid');requiredGrid.append(columnField('Description','Product description',true),columnField('Quantity','Quantity',true));
  const unitsLabel=element('label'),units=element('select');units.dataset.quantityUnit='';units.required=true;units.setAttribute('aria-label','Quantity type · '+preview.filename);units.setAttribute('data-standard-choice','');units.append(new Option('Choose units or cases',''),new Option('Individual units','EACH'),new Option('Cases','CASE'));
  const quantityHeader=sheet.headers[Number(selects.Quantity.value)]??'';
  units.value=previous?.quantityUnit??(selects.Quantity.value!==''&&quantityHeader.toLowerCase().replace(/[^a-z0-9]/g,'')==='shipquantity'?'EACH':'');
  unitsLabel.append(element('span','Quantity type · Required'),units);requiredGrid.append(unitsLabel);section.append(requiredGrid);
  section.append(element('h3','Product identifier · Match at least one'));
  const identityGrid=element('div',null,'mapping-grid');identityGrid.append(columnField('AccountSKU','Account SKU'),columnField('VendorItemCode','Vendor item code'),columnField('UPC','UPC / EAN'));section.append(identityGrid);
  const optional=element('details',null,'receiving-optional-columns');optional.append(element('summary','Optional fields'));
  const optionalGrid=element('div',null,'mapping-grid');optionalGrid.append(columnField('UnitCost','Unit cost / each'));
  const packRow=columnField('UnitsPerCase','Units / case');optionalGrid.append(packRow);optional.append(optionalGrid);section.append(optional);

  function updateCaseOverride(){const cases=units.value==='CASE';packRow.hidden=!cases;selects.UnitsPerCase.disabled=!cases;}
  units.addEventListener('change',updateCaseOverride);updateCaseOverride();
  section.append(element('p','First '+sheet.samples.length+' of '+sheet.totalRows+' product rows','muted'));
  const wrap=element('div',null,'receiving-column-samples'),table=element('table'),head=element('thead'),tr=element('tr');sheet.headers.forEach(h=>tr.append(element('th',h)));head.append(tr);table.style.minWidth=Math.max(640,sheet.headers.length*140)+'px';table.append(head);const rows=element('tbody');sheet.samples.forEach(sample=>{const tr=element('tr');sheet.headers.forEach(h=>tr.append(element('td',sample[h] == null || sample[h] === '' ? '—' : sample[h])));rows.append(tr);});table.append(rows);wrap.append(table);section.append(wrap);
  return section;
 }
 const cancel=()=>{dialog.close();document.getElementById('session-dialog').showModal();};dialog.querySelector('[data-cancel-columns]').onclick=cancel;dialog.querySelector('.dialog-close').onclick=cancel;dialog.addEventListener('cancel',event=>{event.preventDefault();cancel();});
 function uploadError(message){let notice=upload.querySelector('[data-column-upload-error],.receiving-upload-error');if(!notice){notice=element('p',null,'notice error receiving-upload-error');notice.dataset.columnUploadError='';notice.setAttribute('role','alert');upload.querySelector('.dialog-actions').prepend(notice);}notice.textContent=message;notice.hidden=false;notice.tabIndex=-1;notice.focus();}
 upload.addEventListener('submit',async event=>{
  event.preventDefault();
  if(ready){
   if(pending||!upload.reportValidity())return;pending=true;
   const save=upload.querySelector('.dialog-actions button:last-child');save.disabled=true;
   try{
    const response=await fetch(upload.action,{method:'POST',body:new FormData(upload)});
    const url=new URL(response.url);
    if(url.pathname==='/login')throw Error('Your session expired. Sign in and try again.');
    if(response.ok&&/^\/app\/receiving\/[0-9a-f-]{36}$/.test(url.pathname)){location.assign(response.url);return;}
    const result=new DOMParser().parseFromString(await response.text(),'text/html');
    throw Error(result.querySelector('#session-dialog [role=alert]')?.textContent||'The documents could not be saved. Review the fields and try again.');
   }catch(e){ready=false;document.getElementById('session-dialog').showModal();uploadError(e.message);}
   finally{pending=false;save.disabled=false;}
   return;
  }
if(pending||!upload.reportValidity())return;pending=true;
  const button=upload.querySelector('.dialog-actions button:last-child');button.disabled=true;
  try{const csrf=upload.querySelector('input[name="_csrf"]'),headers={Accept:'application/json'};if(csrf)headers['X-CSRF-TOKEN']=csrf.value;
   const response=await fetch('/app/receiving/columns/preview',{method:'POST',body:new FormData(upload),headers});if(response.redirected&&new URL(response.url).pathname==='/login')throw Error('Your session expired. Sign in and try again.');const result=await response.json();if(!response.ok)throw Error(result.error||'The uploaded columns could not be read.');
   // Always confirm quantity meaning; generic Quantity must never silently mean Cases.
   previews=result;body.replaceChildren(...previews.map((p,i)=>build(i,true)));window.NextAiPlatformControls?.enhance(dialog);feedback('');document.getElementById('session-dialog').close();dialog.showModal();
  }catch(e){let notice=upload.querySelector('[data-column-upload-error]');if(!notice){notice=element('p',null,'notice error receiving-upload-error');notice.dataset.columnUploadError='';notice.setAttribute('role','alert');upload.querySelector('.dialog-actions').prepend(notice);}notice.textContent=e.message||'The file could not be read. Try again.';
  }finally{pending=false;button.disabled=false;}
 });
 dialog.querySelector('form').addEventListener('submit',event=>{
  event.preventDefault();feedback('');if(!event.target.reportValidity())return;
  const mappings=[];
  for(const section of body.children){const fields={};section.querySelectorAll('[data-field]').forEach(select=>{if(!select.disabled&&select.value!=='')fields[select.dataset.field]=select.value;});
   if(!fields.AccountSKU&&!fields.VendorItemCode&&!fields.UPC){feedback('Match Account SKU, vendor item code or UPC / EAN for every file.');return;}
   if(new Set(Object.values(fields)).size!==Object.values(fields).length){feedback('Match each uploaded column only once.');return;}
   mappings.push({headings:true,quantityUnit:section.querySelector('[data-quantity-unit]').value,fields});
  }
  const schemas=mappings.map((choice,index)=>{const sheet=previews[index].headed;return JSON.stringify({headers:sheet.headers.length,quantityUnit:choice.quantityUnit,fields:Object.entries(choice.fields).sort(([a],[b])=>a.localeCompare(b))});});
  const mismatch=schemas.findIndex(schema=>schema!==schemas[0]);
  if(mismatch>=0){feedback(previews[mismatch].filename+': columns or quantity meaning differ from the first file. Upload matching files together.');return;}
  let hidden=upload.querySelector('[name=columnMapping]');if(!hidden){hidden=element('input');hidden.type='hidden';hidden.name='columnMapping';upload.append(hidden);}hidden.value=JSON.stringify(mappings);ready=true;dialog.close();upload.requestSubmit();
 });
})();
