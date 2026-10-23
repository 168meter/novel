const assert=require('assert/strict'),fs=require('fs'),vm=require('vm'),path=require('path');
const file=process.argv[2]||path.join(__dirname,'Test-BrowserInteraction.cjs');
const source=fs.readFileSync(file,'utf8');
const start=source.indexOf('async function captureLoginBeforeNavigation('),end=source.indexOf('// END LOGIN CAPTURE');
assert(start>=0&&end>start,'login response capture helper missing');
const ctx={};vm.createContext(ctx);vm.runInContext(source.slice(start,end),ctx);
(async()=>{
 let navigated=false;const events=[];
 const route={fetch:async()=>{events.push('server');return {json:async()=>{assert.equal(navigated,false,'response disposed after navigation');events.push('read');return {code:200};}};},fulfill:async()=>{navigated=true;events.push('navigation');}};
 const value=await ctx.captureLoginBeforeNavigation(route);assert.equal(value.code,200);assert.deepEqual(events,['server','read','navigation']);
 let fulfilled=false;await assert.rejects(()=>ctx.captureLoginBeforeNavigation({fetch:async()=>({json:async()=>{throw Error('unreadable');}}),fulfill:async()=>{fulfilled=true;}}),/unreadable/);assert.equal(fulfilled,false);
 console.log('PASS: login response read before navigation; unreadable body cannot report successful login (2 checks).');
})().catch(e=>{console.error(e.stack);process.exitCode=1;});

