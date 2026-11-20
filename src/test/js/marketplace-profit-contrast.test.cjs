const {test,before,after}=require('node:test');
const assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path');
const {chromium}=require('playwright');
let browser;
const rendered=fs.readFileSync('/tmp/marketplace-organization-review/marketplace.html','utf8');
const css=[...rendered.matchAll(/<link[^>]+href="(\/css\/[^"?]+)(?:\?[^"]*)?"/g)]
 .map(match=>fs.readFileSync(path.join('src/main/resources/static',match[1]),'utf8')).join('\n');
before(async()=>{browser=await chromium.launch({headless:true,channel:process.env.TEST_BROWSER_CHANNEL||'chrome'});});
after(async()=>browser?.close());
function rgba(value){
 const values=(value.match(/[\d.]+/g)||[]).map(Number);
 assert.ok(value.startsWith('rgb')||value.startsWith('color(srgb'),`Unsupported computed color ${value}`);
 return [...values.slice(0,3).map(v=>value.startsWith('color(srgb')?v:v/255),values[3]??1];
}
function contrast(styles){
 let background=[1,1,1];
 for(const value of styles.backgrounds.toReversed()){
  const [r,g,b,a]=rgba(value);background=[r,g,b].map((v,i)=>v*a+background[i]*(1-a));
 }
 const foreground=rgba(styles.color).slice(0,3);
 const luminance=rgb=>rgb.map(v=>v<=.04045?v/12.92:((v+.055)/1.055)**2.4).reduce((sum,v,i)=>sum+v*[.2126,.7152,.0722][i],0);
 const [low,high]=[luminance(foreground),luminance(background)].sort((a,b)=>a-b);
 return (high+.05)/(low+.05);
}
for(const width of [390,1024,1764])for(const theme of ['light','dark'])test(`Marketplace profit text contrast and scope ${width} ${theme}`,async()=>{
 const page=await browser.newPage({viewport:{width,height:900},reducedMotion:'reduce'});
 await page.route('**/*',route=>route.abort());
 const rows=['sku-active','sku-inactive','sku-unmapped','sku-out_of_stock'].map(tone=>`<tr class="${tone}"><td data-label="Record">Record</td><td data-label="Product details">Synthetic product</td><td data-label="Status">Status</td><td data-label="Price (+ shipping)">$30</td><td data-label="Profit"><button class="profit-open"><span>$12.34</span><small>Est. / SKU</small></button><button class="profit-open profit-missing"><span>Missing costs</span><small>Est. / SKU</small></button><button class="profit-open profit-loss"><span>-$2.00</span><small>Est. / SKU</small></button></td><td data-label="Available">10</td><td data-label="4w sales">2</td></tr>`).join('');
 await page.setContent(`<!doctype html><html data-theme="${theme}"><head><meta name="viewport" content="width=device-width,initial-scale=1"><style>${css}</style></head><body class="app-page"><aside class="sidebar"></aside><main class="workspace sku-workspace"><section class="data-card sku-card"><table class="sku-table table-widget"><tbody>${rows}</tbody></table></section></main><section class="orders-workspace"><button class="profit-open" id="orders-control"><span>$12.34</span></button></section></body></html>`);
 const stable=()=>page.evaluate(()=>Promise.all(document.getAnimations().filter(a=>Number.isFinite(a.effect?.getComputedTiming().endTime)).map(a=>a.finished.catch(()=>{}))));
 const read=element=>element.evaluate(e=>({color:getComputedStyle(e).color,fontSize:getComputedStyle(e).fontSize,backgrounds:Array.from((function*(n){while(n){yield n;n=n.parentElement}})(e)).map(n=>getComputedStyle(n).backgroundColor)}));
 await stable();
 const values=page.locator('.sku-workspace .profit-open:not(.profit-loss)>span');
 for(const value of await values.all()){
  const style=await read(value);assert.ok(contrast(style)>=4.5,`${width} ${theme}: ${style.color} / ${style.fontSize} contrast ${contrast(style).toFixed(2)}`);
  if(theme==='dark')assert.equal(style.color,'rgb(105, 161, 255)');
 }
 const button=page.locator('.sku-workspace .profit-open:not(.profit-loss)').first();
 await button.hover();await stable();assert.ok(contrast(await read(button.locator('span')))>=4.5);
 await button.focus();await stable();assert.ok(contrast(await read(button.locator('span')))>=4.5);
 for(const loss of await page.locator('.profit-loss>span').all())assert.equal((await read(loss)).color,'rgb(207, 62, 73)');
 assert.equal((await read(page.locator('#orders-control>span'))).color,'rgb(46, 107, 230)','Orders retains its existing color');
 if(theme==='light')assert.equal((await read(values.first())).color,'rgb(46, 107, 230)','Light theme retains its existing color');
 await page.close();
});
