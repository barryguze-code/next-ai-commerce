/* Table presentation preferences never belong in HTTP request cookies. */
(()=>{
  'use strict';
  if(window.NextAiTablePreferences)return;
  const legacyPrefix='nextai-table-',storagePrefix='nextai.table.preferences.';
  const memory=new Map();
  const valid=value=>value&&typeof value==='object'&&!Array.isArray(value)
    &&Number.isInteger(value.schema)&&Array.isArray(value.order)
    &&value.order.every(id=>typeof id==='string')
    &&value.visibility&&typeof value.visibility==='object'&&!Array.isArray(value.visibility)
    &&Object.values(value.visibility).every(visible=>typeof visible==='boolean');
  function parse(text){try{const value=JSON.parse(text);return valid(value)?value:null}catch(_){return null}}
  function read(key){
    if(memory.has(key))return memory.get(key);
    try{return parse(window.localStorage.getItem(storagePrefix+key))}catch(_){return null}
  }
  function write(key,value){
    if(!valid(value))return;
    memory.set(key,value);
    try{window.localStorage.setItem(storagePrefix+key,JSON.stringify(value))}catch(_){
      // Disabled/full storage must not break tables or recreate oversized cookies.
    }
  }
  function migrate(){
    let cookies;try{cookies=document.cookie.split(';')}catch(_){return}
    for(const cookie of cookies){
      const entry=cookie.trim(),separator=entry.indexOf('=');
      if(separator<0)continue;
      const name=entry.slice(0,separator);
      if(!name.startsWith(legacyPrefix))continue;
      const key=name.slice(legacyPrefix.length);
      try{
        const legacy=parse(decodeURIComponent(entry.slice(separator+1)));
        if(legacy&&!read(key))write(key,legacy);
      }catch(_){/* A corrupt preference must not prevent recovery. */}
      // The old widget used host-only cookies with Path=/. Leave all others alone.
      try{document.cookie=name+'=; Path=/; Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:00 GMT; SameSite=Lax'}catch(_){}
    }
  }
  window.NextAiTablePreferences={read,write,migrate};
  migrate();
  // An older open tab may still create legacy cookies until it is refreshed.
  window.addEventListener('pageshow',migrate);
  document.addEventListener('visibilitychange',()=>{if(!document.hidden)migrate()});
})();
