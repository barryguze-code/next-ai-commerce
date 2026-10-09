const {test}=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const vm=require('node:vm');
const source=fs.readFileSync('src/main/resources/static/js/table-data-tools.js','utf8');
const start=source.indexOf('function paginationTotal('),end=source.indexOf('// One pager presentation',start);
const context=vm.createContext({});vm.runInContext(source.slice(start,end),context);
test('server footer totals count records, never page numbers',()=>{
 assert.equal(context.paginationTotal('7 documents · Page 1 of 1','receiving-documents'),'Total: 7 documents');
 assert.equal(context.paginationTotal('Showing 1–25 of 2781 movements','ledger'),'Total: 2,781 movements');
 assert.equal(context.paginationTotal('Showing 1–25 of 3426','orders'),'Total: 3,426 orders');
 assert.equal(context.paginationTotal('No movements','ledger'),'Total: 0 movements');
 assert.equal(context.paginationTotal('Page 1 of 7','other'),'Page 1 of 7');
});
