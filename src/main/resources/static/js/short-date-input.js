/* Human dates use MM/DD/YY; submitted dates remain ISO and timezone-free. */
(()=>{
  function display(value){return String(value||'').replace(/^(\d{4})-(\d{2})-(\d{2})(?:T(\d{2}:\d{2}))?.*$/,(_,y,m,d,time)=>`${m}/${d}/${y.slice(-2)}${time?' '+time:''}`);}
  function mask(value,withTime=false){
    const normalized=String(value||'').replace(/^(\d{1,2}\/\d{1,2}\/)20(\d{2})/,'$1$2');
    let digits=normalized.replace(/\D/g,'');
    if(!withTime&&digits.length===8&&digits.slice(4,6)==='20')digits=digits.slice(0,4)+digits.slice(6);
    digits=digits.slice(0,withTime?10:6);
    return digits.slice(0,2)+(digits.length>2?'/'+digits.slice(2,4):'')+(digits.length>4?'/'+digits.slice(4,6):'')+(withTime&&digits.length>6?' '+digits.slice(6,8):'')+(withTime&&digits.length>8?':'+digits.slice(8,10):'');
  }
  function parse(value,withTime=false){
    if(!value.trim())return '';
    const match=value.trim().match(/^(\d{1,2})\/(\d{1,2})\/(\d{2}|\d{4})(?:\s+(\d{2}):(\d{2}))?$/);
    if(!match)return null;
    const [,m,d,y,h,minute]=match,year=y.length===2?2000+Number(y):Number(y),month=Number(m),day=Number(d);
    if(year<2000||year>2099||month<1||month>12||day<1||day>new Date(Date.UTC(year,month,0)).getUTCDate())return null;
    if(withTime&&(h===undefined||Number(h)>23||Number(minute)>59))return null;
    if(!withTime&&h!==undefined)return null;
    return `${year}-${m.padStart(2,'0')}-${d.padStart(2,'0')}${withTime?'T'+h+':'+minute:''}`;
  }
  if(typeof module!=='undefined')module.exports={parse,display,mask};
  if(typeof document==='undefined'||window.NextAiShortDates)return;
  window.NextAiShortDates={parse,display,mask};
  const selector='input[type="date"],input[type="datetime-local"]';
  function enhance(input){
    if(input.dataset.shortDateReady)return;input.dataset.shortDateReady='true';
    const withTime=input.type==='datetime-local',wrap=document.createElement('span'),text=document.createElement('input'),picker=document.createElement('button');
    wrap.className='short-date-control';text.type='text';text.className='short-date-text';text.placeholder=withTime?'mm/dd/yy hh:mm':'mm/dd/yy';text.autocomplete='off';text.inputMode='numeric';
    text.setAttribute('aria-label',(input.getAttribute('aria-label')||input.closest('label')?.childNodes[0]?.textContent?.trim()||'Date')+' (MM/DD/YY'+(withTime?' HH:mm':'')+')');
    text.title='Two-digit years mean 2000–2099';picker.type='button';picker.className='short-date-picker';picker.setAttribute('aria-label','Choose date from calendar');picker.innerHTML='<svg viewBox="0 0 24 24" aria-hidden="true"><rect x="4" y="5" width="16" height="16" rx="2"/><path d="M8 3v4m8-4v4M4 11h16"/></svg>';
    input.before(wrap);wrap.append(text,input,picker);input.classList.add('short-date-native');input.tabIndex=-1;input.setAttribute('aria-hidden','true');
    const sync=()=>{text.value=display(input.value);text.required=input.required;text.disabled=input.disabled;text.readOnly=input.readOnly;picker.disabled=input.disabled||input.readOnly;text.setCustomValidity('');};
    // Existing dialogs set .value before opening; keep their displayed date in sync.
    const descriptor=Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value');
    Object.defineProperty(input,'value',{configurable:true,get(){return descriptor.get.call(this)},set(value){descriptor.set.call(this,value);sync();}});
    function commit(){const iso=parse(text.value,withTime);const invalid=iso===null||(iso&&input.min&&iso<input.min)||(iso&&input.max&&iso>input.max);text.setCustomValidity(invalid?'Enter a valid '+text.placeholder+' date between 2000 and 2099.':'');if(invalid){descriptor.set.call(input,'');return false;}descriptor.set.call(input,iso);return true;}
    text.addEventListener('input',()=>{
      const digitsBefore=text.value.slice(0,text.selectionStart??text.value.length).replace(/\D/g,'').length;
      text.value=mask(text.value,withTime);let caret=0,seen=0;
      while(caret<text.value.length&&seen<digitsBefore){if(/\d/.test(text.value[caret]))seen++;caret++;}
      while(caret<text.value.length&&/\D/.test(text.value[caret]))caret++;
      text.setSelectionRange(caret,caret);if(commit())input.dispatchEvent(new Event('input',{bubbles:true}));
    });
    text.addEventListener('change',()=>{if(commit()){sync();input.dispatchEvent(new Event('change',{bubbles:true}));}});
    input.addEventListener('change',sync);input.addEventListener('input',()=>{if(document.activeElement!==text)sync();});
    input.addEventListener('invalid',event=>{event.preventDefault();text.focus();text.reportValidity();});
    picker.onclick=()=>{try{input.showPicker();}catch(_){text.focus();}};
    input.form?.addEventListener('reset',()=>queueMicrotask(sync));
    new MutationObserver(sync).observe(input,{attributes:true,attributeFilter:['value','required','disabled','readonly','min','max']});sync();
  }
  function scan(root){if(root.matches?.(selector))enhance(root);root.querySelectorAll?.(selector).forEach(enhance);}
  if(document.readyState==='loading')document.addEventListener('DOMContentLoaded',()=>scan(document));else scan(document);
  new MutationObserver(records=>records.forEach(record=>record.addedNodes.forEach(node=>{if(node.nodeType===1)scan(node);}))).observe(document.documentElement,{childList:true,subtree:true});
})();
