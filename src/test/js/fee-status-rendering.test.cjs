const {test,before,after}=require('node:test'),assert=require('node:assert/strict');
const fs=require('node:fs'),path=require('node:path'),{chromium}=require('playwright');
// Exact Thymeleaf output, checked against the real template by TemplateRenderingTest.
const rendered=JSON.parse(fs.readFileSync('src/test/resources/fee-status-rendered.json','utf8'));
let browser;
before(async()=>{browser=await chromium.launch({headless:true,...(process.env.TEST_BROWSER_CHANNEL?{channel:process.env.TEST_BROWSER_CHANNEL}:{})});});
after(async()=>{await browser?.close();});
for(const [state,html] of Object.entries(rendered))test(`fee-only ${state}: truthful state, static terminal output and phone bounds`,async()=>{
 for(const [width,theme] of [[1280,'light'],[320,'light'],[390,'dark']]){
  const context=await browser.newContext({viewport:{width,height:844}}),page=await context.newPage();
  try{
   await page.route('**/*',r=>r.request().url()==='http://fee-status.test/'?r.fulfill({contentType:'text/html; charset=utf-8',body:html}):r.abort());
   await page.goto('http://fee-status.test/');await page.evaluate(theme=>{document.documentElement.dataset.theme=theme;},theme);
   for(const name of ['app.css','brand.css','access.css'])await page.addStyleTag({path:path.resolve('src/main/resources/static/css',name)});
   await page.addStyleTag({content:'body{margin:0;padding:16px}'});
   const text=await page.locator('body').innerText(),active=state==='RUNNING';
   assert.match(text,/Amazon fee check/i);assert.match(text,/18 passed validation; 2 unsupported/);assert.match(text,/Comparable per-unit FBA fees: 0 of 18/);assert.match(text,/Estimates unchanged/);
   assert.doesNotMatch(text,/Amazon initialization|Initial 30-day import|Not available for this store|â€“|âœ/);
   const contrasts=await page.locator('.onboarding-title p,.sync-checklist small,footer p,footer span').evaluateAll(nodes=>{
    const rgba=c=>(c.match(/[\d.]+/g)||[]).map(Number),blend=(fg,bg)=>{const a=fg[3]??1;return fg.slice(0,3).map((v,i)=>v*a+bg[i]*(1-a));};
    const background=n=>{const chain=[];for(let p=n;p;p=p.parentElement)chain.unshift(p);return chain.reduce((bg,p)=>blend(rgba(getComputedStyle(p).backgroundColor),bg),[255,255,255]);};
    const light=rgb=>rgb.map(v=>{v/=255;return v<=.04045?v/12.92:((v+.055)/1.055)**2.4;}).reduce((sum,v,i)=>sum+v*[.2126,.7152,.0722][i],0);
    return nodes.filter(n=>n.getBoundingClientRect().height>0).map(n=>{let opacity=1;for(let p=n;p;p=p.parentElement)opacity*=Number(getComputedStyle(p).opacity);const bg=background(n),fg=rgba(getComputedStyle(n).color);fg[3]=(fg[3]??1)*opacity;const a=light(blend(fg,bg)),b=light(bg);return{text:n.textContent,ratio:(Math.max(a,b)+.05)/(Math.min(a,b)+.05)};});
   });
   assert.ok(contrasts.every(c=>c.ratio>=4.5),JSON.stringify(contrasts));
   if(!active){
    assert.match(text,/This fee check has ended/);assert.equal(await page.locator('.sync-loader,.job-spinner,[role=progressbar]').count(),0);
    const labels={COMPLETED_WITH_WARNINGS:'Completed with warnings',FAILED:'Failed',CANCELLED:'Cancelled'};
    assert.equal(await page.locator('.onboarding-progress-copy strong').textContent(),labels[state]);
    assert.deepEqual(await page.locator('section *').evaluateAll(nodes=>nodes.filter(n=>getComputedStyle(n).animationName!=='none').map(n=>n.className)),[]);
   }else assert.match(text,/Checking fee quotes/);
   assert.ok(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),`${state} ${width} ${theme} clips horizontally`);
   if(process.env.FEE_STATUS_EVIDENCE_DIR){fs.mkdirSync(process.env.FEE_STATUS_EVIDENCE_DIR,{recursive:true});await page.screenshot({path:path.join(process.env.FEE_STATUS_EVIDENCE_DIR,`${state}-${width}-${theme}.png`),fullPage:true});}
  }finally{await context.close();}
 }
});
