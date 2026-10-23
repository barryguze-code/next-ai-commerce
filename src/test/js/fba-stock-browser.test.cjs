// Uses rendered synthetic fixtures only. Never accesses a signed-in browser or account database.
const {test}=require('node:test'),assert=require('node:assert/strict');
const http=require('node:http'),fs=require('node:fs'),path=require('node:path');
test('FBA stock dialog fits laptops, closes with Escape and restores focus',{
  skip:process.env.FBA_BROWSER_TEST!=='1'&&process.env.FBA_PREVIEW_SERVER!=='1'
},async()=>{
  const fixture=fs.readFileSync('.local/fba-preview/stock.html','utf8');
  const server=http.createServer((req,res)=>{
    const pathname=new URL(req.url,'http://localhost').pathname;
    if(pathname==='/'){
      res.setHeader('Content-Type','text/html; charset=utf-8');
      res.end(fixture.replace('</head>','</head>').replace('<body>','<body><link rel="stylesheet" href="/css/app.css"><link rel="stylesheet" href="/css/fba.css"><script defer src="/js/fba-stock.js"></script>'));return;
    }
    if(/^\/(css|js)\/[a-z0-9-]+\.(css|js)$/.test(pathname)){
      const file=path.join('src/main/resources/static',pathname);
      if(fs.existsSync(file)){res.setHeader('Content-Type',pathname.endsWith('.js')?'text/javascript; charset=utf-8':'text/css; charset=utf-8');res.end(fs.readFileSync(file));return;}
    }
    res.writeHead(404).end();
  });
  await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
  if(process.env.FBA_PREVIEW_SERVER==='1'){
    console.log('Synthetic FBA preview: http://127.0.0.1:'+server.address().port);
    return;
  }
  let browser;
  try{
    browser=await require('playwright').chromium.launch({headless:true});
    const page=await browser.newPage();
    for(const width of [1280,1440,390]){
      await page.setViewportSize({width,height:800});
      await page.goto('http://127.0.0.1:'+server.address().port);
      await page.locator('summary').click();
      const dialog=page.locator('dialog[open]');await dialog.waitFor();
      const box=await dialog.boundingBox();assert.ok(box.x>=0&&box.x+box.width<=width);
      assert.match(await dialog.innerText(),/26.23/);
      assert.match(await dialog.innerText(),/Not imported/);
      await page.keyboard.press('Escape');assert.equal(await page.locator('dialog').count(),0);
      assert.equal(await page.evaluate(()=>document.activeElement.tagName),'SUMMARY');
      await page.locator('summary').click();await page.getByRole('button',{name:'Done',exact:true}).click();
      assert.equal(await page.locator('dialog').count(),0);
    }
  }finally{await browser?.close();await new Promise(resolve=>server.close(resolve));}
});
