// Keep user-facing notes in the same tested artifact as the deployed application.
const fs=require('node:fs');
const assert=require('node:assert/strict');
const pom=fs.readFileSync('pom.xml','utf8').replace(/<parent>[\s\S]*?<\/parent>/,'');
const version=pom.match(/<version>([^<]+)<\/version>/)?.[1];
const history=JSON.parse(fs.readFileSync('src/main/resources/releases/history.json','utf8'));
assert.ok(version&&history.length,'A version and release notes are required');
assert.equal(history[0].version,version,'Add user-facing notes for the version being deployed');
const seen=new Set();
for(const release of history){
 assert.match(release.version,/^\d+\.\d+\.\d+$/);assert.ok(!seen.has(release.version),'Duplicate release');seen.add(release.version);
 assert.ok(release.title.trim()&&release.sections.length,'Each release needs a title and notes');
 for(const section of release.sections)assert.ok(section.title.trim()&&section.items.length&&section.items.every(item=>typeof item==='string'&&item.trim()),'Release sections must contain readable notes');
}
console.log(`Release notes verified for v${version}; ${history.length} documented deployments retained.`);
