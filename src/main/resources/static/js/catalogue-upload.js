/* Keep file additions cumulative; staging still requires explicit review and approval. */
(()=>{
 const form=document.querySelector('[data-catalog-upload]');if(!form)return;
 const input=form.querySelector('input[type=file]'),drop=form.querySelector('[data-catalog-drop]'),add=form.querySelector('[data-catalog-add-files]'),list=form.querySelector('[data-catalog-files]'),error=form.querySelector('[data-catalog-upload-error]');let files=[];
 const feedback=message=>{error.textContent=message;error.hidden=!message;};
 function render(){const transfer=new DataTransfer();files.forEach(file=>transfer.items.add(file));input.files=transfer.files;list.replaceChildren();add.textContent=files.length?'Add more files':'Add files';
 files.forEach((file,index)=>{const row=document.createElement('li'),name=document.createElement('span'),remove=document.createElement('button');name.textContent=file.name+' · '+Math.max(1,Math.round(file.size/1024))+' KB';remove.type='button';remove.className='catalog-file-remove';remove.textContent='×';remove.setAttribute('aria-label','Remove '+file.name);remove.onclick=()=>{files.splice(index,1);feedback('');render();};row.append(name,remove);list.append(row);});}
 function append(incoming){const proposed=[...files];for(const file of incoming){if(!/\.(csv|tsv|xlsx)$/i.test(file.name)){feedback('Choose CSV, TSV or XLSX catalogue files.');render();return;}if(!proposed.some(f=>f.name===file.name&&f.size===file.size&&f.lastModified===file.lastModified))proposed.push(file);}
 if(proposed.length>12||proposed.reduce((n,f)=>n+f.size,0)>20*1024*1024){feedback('Choose up to 12 files, totalling 20 MB or less.');render();return;}files=proposed;feedback('');render();}
 add.addEventListener('click',()=>input.click());input.addEventListener('change',()=>append([...input.files]));
 ['dragenter','dragover'].forEach(type=>drop.addEventListener(type,e=>{e.preventDefault();drop.classList.add('is-dragging');}));drop.addEventListener('dragleave',()=>drop.classList.remove('is-dragging'));drop.addEventListener('drop',e=>{e.preventDefault();drop.classList.remove('is-dragging');append([...e.dataTransfer.files]);});
 input.addEventListener('invalid',e=>{e.preventDefault();feedback('Add at least one catalogue file.');add.focus();});
 form.addEventListener('submit',e=>{if(!files.length){e.preventDefault();feedback('Add at least one catalogue file.');return;}const submit=form.querySelector('[type=submit]');submit.disabled=true;submit.textContent='Preparing…';});
})();
