const {test,before,after}=require('node:test');
const assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path'),http=require('node:http');
const {chromium}=require('playwright');
const inventory=JSON.parse(fs.readFileSync('src/test/fixtures/release-cache-assets.json','utf8'));
const templates=path.resolve('src/main/resources/templates');
const refs=html=>[...html.matchAll(/["'](\/(?:css|js)\/[^"'?]+)(\?[^"']*)?["']/g)].map(m=>({asset:m[1],query:m[2]||''}));
const html=file=>fs.readFileSync(path.join(templates,file),'utf8');
function assetUrl(file,asset){const found=refs(html(file)).find(r=>r.asset===asset);assert.ok(found,`${file} references ${asset}`);return found.asset+found.query;}
test('all changed released asset references use one fresh canonical cache version',()=>{
 assert.deepEqual(Object.keys(inventory.versions).sort(),[...inventory.assets].sort(),'Every inventoried asset has an explicit release cache version');
 const actualFiles=fs.readdirSync(templates,{recursive:true}).filter(file=>file.endsWith('.html')&&refs(html(file)).some(ref=>inventory.assets.includes(ref.asset))).sort();
 assert.deepEqual([...inventory.templates].sort(),actualFiles,'Inventory covers every template reference, including shared fragments');
 let count=0;
 for(const file of inventory.templates)for(const ref of refs(html(file)))if(inventory.assets.includes(ref.asset)){
  assert.equal(ref.query,'?v='+inventory.versions[ref.asset],`${file}: ${ref.asset} must not reuse a released stale cache URL`);count++;
 }
 assert.equal(count,inventory.reference_count);
 for(const asset of ['/js/table-widget.js','/css/table-widget.css'])assert.equal(assetUrl('marketplace-skus.html',asset),assetUrl('fragments/sidebar-user.html',asset),'Head and shared fragment must request the same version');
 for(const asset of ['/js/table-data-tools.js','/js/profit.js']){
  const matches=inventory.templates.flatMap(file=>refs(html(file))).filter(ref=>ref.asset===asset);assert.ok(matches.length);assert.ok(matches.every(ref=>ref.query==='?v='+inventory.versions[asset]));
 }
});
let browser,server,origin,requests=[];
before(async()=>{
 browser=await chromium.launch({headless:true,channel:process.env.TEST_BROWSER_CHANNEL||'chrome'});
 server=http.createServer((req,res)=>{
  const url=new URL(req.url,'http://fixture');requests.push(url.pathname+url.search);
  if(url.pathname==='/seed'){
   res.setHeader('Content-Type','text/html');return res.end('<!doctype html><script src="/js/table-widget.js?v=1.6.6"></script><link rel="stylesheet" href="/css/table-widget.css?v=20261006-hotfix">');
  }
  if(url.pathname==='/target'||url.pathname==='/legacy-target'){
   const script=url.pathname==='/legacy-target'?'/js/table-widget.js?v=1.6.6':assetUrl('marketplace-skus.html','/js/table-widget.js'),second=assetUrl('fragments/sidebar-user.html','/js/table-widget.js'),css=assetUrl('fragments/sidebar-user.html','/css/table-widget.css');
   res.setHeader('Content-Type','text/html');return res.end(`<!doctype html><meta name="viewport" content="width=device-width,initial-scale=1"><link rel="stylesheet" href="${css}"><style>body{margin:0}.data-card{position:relative}.table-column-control{position:fixed;left:12px;top:100px}table{margin-top:200px}</style><script defer src="${script}"></script><script defer src="${second}"></script><body><section class="data-card"><div data-column-control="cache-regression"></div><table data-table-widget="cache-regression"><thead><tr>${Array.from({length:25},(_,i)=>`<th>Field ${i}</th>`).join('')}</tr></thead><tbody><tr>${Array.from({length:25},(_,i)=>`<td>${i}</td>`).join('')}</tr></tbody></table></section></body>`);
  }
  if(['/js/table-widget.js','/css/table-widget.css'].includes(url.pathname)){
   const legacy=url.searchParams.get('v')==='1.6.6'||url.searchParams.get('v')==='20261006-hotfix';
   const file=legacy?path.join('src/test/fixtures/release-cache-before',path.basename(url.pathname)):path.join('src/main/resources/static',url.pathname);
   res.setHeader('Content-Type',url.pathname.endsWith('.js')?'text/javascript':'text/css');res.setHeader('Cache-Control','public,max-age=31536000,immutable');return res.end(fs.readFileSync(file));
  }
  res.statusCode=404;res.end();
 });
 await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));origin='http://127.0.0.1:'+server.address().port;
});
after(async()=>{await browser?.close();await new Promise(resolve=>server.close(resolve));});
test('warm old browser cache cannot shadow the new Columns implementation on ordinary navigation',async()=>{
 const context=await browser.newContext({viewport:{width:390,height:844}});const page=await context.newPage();requests=[];
 try{
  await page.goto(origin+'/seed');assert.equal(requests.filter(r=>r==='/js/table-widget.js?v=1.6.6').length,1);
  await page.goto(origin+'/target');await page.locator('.table-column-control>summary').click();await page.waitForFunction(()=>document.querySelector('.table-column-panel')?.style.position==='fixed');
  const bounds=await page.locator('.table-column-panel').boundingBox();assert.ok(bounds.x>=12&&bounds.x+bounds.width<=378,JSON.stringify(bounds));assert.ok(bounds.y>=12&&bounds.y+bounds.height<=832,JSON.stringify(bounds));
  assert.equal(await page.locator('.table-column-control').count(),1,'Shared duplicate script tags must not initialize duplicate controls');
  const fresh=assetUrl('marketplace-skus.html','/js/table-widget.js');assert.equal(requests.filter(r=>r===fresh).length,1,'Fresh canonical URL loaded once');assert.equal(requests.filter(r=>r==='/js/table-widget.js?v=1.6.6').length,1,'Old cached URL is not requested by the new page');
  await page.locator('.table-column-control>summary').press('Escape');assert.equal(await page.locator('.table-column-control>summary').evaluate(e=>e===document.activeElement),true);
 }finally{await context.close();}
});

test('negative control reproduces a cached old head script shadowing the shared new script',async()=>{
 const context=await browser.newContext({viewport:{width:390,height:844}}),page=await context.newPage();requests=[];
 try{
  await page.goto(origin+'/seed');await page.goto(origin+'/legacy-target');await page.locator('.table-column-control>summary').click();await page.waitForFunction(()=>document.querySelector('.table-column-panel')?.style.position==='fixed');
  const bounds=await page.locator('.table-column-panel').boundingBox();assert.ok(bounds.x<0,JSON.stringify(bounds));assert.equal(requests.filter(r=>r==='/js/table-widget.js?v=1.6.6').length,1,'Old first script is reused from real browser HTTP cache');
 }finally{await context.close();}
});


test('tooltip hotfix uses a fresh canonical URL without repinning unchanged assets',()=>{
 assert.equal(assetUrl('fragments/ux-components.html','/js/platform-tooltips.js'),'/js/platform-tooltips.js?v=1.7.3');
 assert.equal(assetUrl('fragments/sidebar-user.html','/css/product-thumbnails.css'),'/css/product-thumbnails.css?v=1.7.2');
});
