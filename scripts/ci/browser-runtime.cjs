const fs=require('node:fs'),assert=require('node:assert/strict'),{chromium}=require('playwright');
const lock=require('../../package-lock.json'),version=lock.packages['node_modules/playwright'].version;
assert.equal(require('playwright/package.json').version,version);
assert.ok(fs.existsSync(chromium.executablePath()),'Prepared image must contain the locked Chromium binary');
(async()=>{
  // Both are actual launch contracts used by the unchanged browser suite.
  for(const [name,options] of [['Chromium',{headless:true}],['Google Chrome',{headless:true,channel:'chrome'}]]){
    const browser=await chromium.launch(options);
    console.log(`${name} actual launch passed: ${browser.version()} (Playwright ${version})`);
    await browser.close();
  }
})().catch(error=>{console.error(error);process.exitCode=1;});
