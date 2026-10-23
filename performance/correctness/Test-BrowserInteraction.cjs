async function captureLoginBeforeNavigation(route) {
 const response=await route.fetch();
 const data=await response.json();
 await route.fulfill({response});
 return data;
}
// END LOGIN CAPTURE
const fs=require('fs'),path=require('path'),assert=require('assert/strict'),crypto=require('crypto');
const {createRequire}=require('module');
const req=createRequire(path.join(process.env.JOINT_NODE_MODULES,'package.json'));
const {chromium}=req('playwright');
const [base,run,variant,source]=process.argv.slice(2),fixture=JSON.parse(fs.readFileSync(path.join(run,'browser-fixture.json'),'utf8'));
let browser,page;const checks=[],errors=[],network=[],scriptBodies=[];
const sha=b=>crypto.createHash('sha256').update(b).digest('hex');
const report={variant,status:'RUNNING',checks,errors,network,boundary:'Real browser with real password login/HTTP; network abort and stale callback are controlled fault injection. No cross-tab ordering guarantee.'};
const save=()=>fs.writeFileSync(path.join(run,'browser-'+variant+'.json'),JSON.stringify(report,null,2));
async function check(name,fn){await fn();checks.push(name);save();}
async function selected(button,value){await page.waitForFunction(({id,value})=>{const b=document.querySelector(id);return b&&b.getAttribute('aria-disabled')==='false'&&b.getAttribute('aria-pressed')===String(value);},{id:button,value});}
(async()=>{
 const candidates=[chromium.executablePath(),path.join(process.env['ProgramFiles(x86)']||'C:/Program Files (x86)','Microsoft/Edge/Application/msedge.exe'),path.join(process.env.ProgramFiles||'C:/Program Files','Google/Chrome/Application/chrome.exe')];
 const executablePath=candidates.find(p=>fs.existsSync(p));assert(executablePath,'No existing Chromium/Edge executable; browser acceptance NOT_AVAILABLE');report.executable=executablePath;
 browser=await chromium.launch({headless:true,executablePath});const context=await browser.newContext({serviceWorkers:'block'});page=await context.newPage();page.setDefaultTimeout(20000);
 page.on('pageerror',e=>errors.push(e.message));page.on('response',r=>{if(new URL(r.url()).pathname==='/javascript/reaction-state.js')scriptBodies.push(r.body().then(b=>sha(b)));});page.on('response',res=>{if(/\/(setComment|commentReaction)/.test(res.url()))network.push({method:res.request().method(),path:new URL(res.url()).pathname,status:res.status()});});
 await check('password login page and real authenticated cookie',async()=>{
  const r=await page.goto(base+'/user/login.html?to=pc&originUrl='+encodeURIComponent('/book/'+fixture.bookId+'.html?to=pc'));assert.equal(r.status(),200);
  const account=(await page.locator('#loginAccount').count())?'#loginAccount':'#txtUName', password=(await page.locator('#loginPassword').count())?'#loginPassword':'#txtPassword';
  await page.locator(account).fill(fixture.username);await page.locator(password).fill(process.env.JOINT_BROWSER_PASSWORD);
  // Hold renderer delivery until the response is read; login JS navigates immediately.
  let resolveLogin,rejectLogin;const loginResult=new Promise((ok,fail)=>{resolveLogin=ok;rejectLogin=fail;});
  const capture=async route=>{try{resolveLogin(await captureLoginBeforeNavigation(route));}catch(e){rejectLogin(e);await route.abort().catch(()=>{});}};
  await page.route('**/user/login',capture);const timeout=setTimeout(()=>rejectLogin(Error('Login response was not captured before timeout')),20000);let auth;try{await page.locator('#btnLogin').click();auth=await loginResult;}finally{clearTimeout(timeout);}assert.equal(auth.code,200);
  await page.unroute('**/user/login',capture);
  await page.waitForURL('**/book/'+fixture.bookId+'.html*');assert((await context.cookies()).some(c=>c.name==='Authorization'&&c.value));
 });
 await check('deployed JS exact SHA256 and template page',async()=>{
  const remote=await context.request.get(base+'/javascript/reaction-state.js?joint='+Date.now());assert.equal(remote.status(),200);
  const rel=variant==='builtin'?'novel-front/src/main/resources/static/javascript/reaction-state.js':'templates/'+variant+'/static/javascript/reaction-state.js';
  assert.equal(sha(await remote.body()),sha(fs.readFileSync(path.join(source,rel))));report.reactionSha256=sha(await remote.body());report.loadedReactionHashes=await Promise.all(scriptBodies);assert(report.loadedReactionHashes.includes(report.reactionSha256),'HTML did not load the verified current reaction script');
  await page.waitForFunction(()=>typeof ReactionUI!=='undefined'&&document.querySelectorAll('.reaction-control').length>0);
  await page.screenshot({path:path.join(run,'browser-'+variant+'-initial.png'),fullPage:true});
 });
 const id=await page.locator('.reaction-control[data-reaction-choice="like"]').first().getAttribute('data-reaction-id');report.commentId=id;
 const button='.reaction-control[data-reaction-id="'+id+'"][data-reaction-choice="like"]';
 await selected(button,false);
 await check('selected like and refresh restoration',async()=>{
  await page.locator(button).first().click();await selected(button,true);await page.reload({waitUntil:'domcontentloaded'});await selected(button,true);
  await page.locator(button).first().click();await selected(button,false);
 });
 await check('same target double click submits one explicit request',async()=>{
  let held,release;const gate=new Promise(r=>release=r);let requests=0;
  const handler=async route=>{requests++;const response=await route.fetch();held=true;await gate;await route.fulfill({response});};
  await page.route('**/book/setCommentLike',handler);
  await page.locator(button).first().click();await page.waitForFunction(sel=>document.querySelector(sel)?.getAttribute('aria-disabled')==='true',button);
  // Actual DOM event pair while the original HTTP request is held in flight.
  await page.locator(button).first().evaluate(el=>{el.click();el.click();});
  assert.equal(requests,1);release();await selected(button,true);await page.unroute('**/book/setCommentLike',handler);
  await page.locator(button).first().click();await selected(button,false);
 });
 await check('write reached server but response lost triggers GET reconciliation only',async()=>{
  const before=network.length;let writes=0;
  const handler=async route=>{writes++;await route.fetch();await route.abort('failed');};
  await page.route('**/book/setCommentLike',handler);await page.locator(button).first().click();await selected(button,true);await page.unroute('**/book/setCommentLike',handler);
  assert.equal(writes,1);assert(network.slice(before).some(n=>n.method==='GET'&&n.path==='/book/commentReaction'));
  await page.evaluate(()=>{if(window.layer)layer.closeAll();});
 });
 await check('late earlier success callback cannot overwrite later cancellation',async()=>{
  await page.evaluate(()=>{window.jointOldSuccess=null;window.jointOldPayload=null;const original=$.ajax;window.jointAjax=original;$.ajax=function(o){if(o.url==='/book/setCommentLike'&&o.type==='POST'){const cb=o.success;o.success=function(v){if(!window.jointOldSuccess){window.jointOldSuccess=cb;window.jointOldPayload=v;}return cb(v);};}return original.apply(this,arguments);};});
  await page.locator(button).first().click();await selected(button,false); // first captured cancellation
  await page.locator(button).first().click();await selected(button,true); // newer intention
  await page.evaluate(()=>window.jointOldSuccess(window.jointOldPayload));await selected(button,true);
  await page.locator(button).first().click();await selected(button,false);await page.evaluate(()=>{$.ajax=window.jointAjax;});
 });
 await check('actual AJAX pagination and hydrated state',async()=>{
  const response=await page.goto(base+'/book/comment-'+fixture.bookId+'.html?to=pc',{waitUntil:'domcontentloaded'});assert.equal(response.status(),200);
  await page.waitForFunction(()=>document.querySelector('#commentPanel .reaction-control[aria-disabled="false"]')&&document.querySelector('#commentPage .layui-laypage-curr'));
  await page.evaluate(()=>{(typeof searchComments==='function'?searchComments:loadCommentList)(1,2);});
  await page.waitForFunction(()=>document.querySelectorAll('#commentPanel .reaction-control[data-reaction-choice="like"]').length===2);
  const old=await page.locator('#commentPanel .reaction-control').first().getAttribute('data-reaction-id');
  // Exercise actual page widget populated with two items per page.
  const next=page.locator('#commentPage .layui-laypage-next');await next.click();
  await page.waitForFunction(previous=>{const c=document.querySelector('#commentPanel .reaction-control');return c&&c.getAttribute('data-reaction-id')!==previous&&c.getAttribute('aria-disabled')==='false';},old);
 });
 await check('approved reply page selected state and refresh',async()=>{const r=await page.goto(base+'/book/reply-'+fixture.commentId+'.html?to=pc',{waitUntil:'domcontentloaded'});assert.equal(r.status(),200);const control='.reaction-control[data-reaction-kind="reply"][data-reaction-id="'+fixture.replyId+'"][data-reaction-choice="like"]';await selected(control,false);await page.locator(control).first().click();await selected(control,true);await page.reload({waitUntil:'domcontentloaded'});await selected(control,true);await page.locator(control).first().click();await selected(control,false);});
 await check('author private review-state page uses authenticated session',async()=>{await page.goto(base+'/user/comment.html?to=pc',{waitUntil:'domcontentloaded'});await page.waitForFunction(()=>document.body.innerText.includes('browser-own-pending')&&document.body.innerText.includes('待审核'));});
 await page.screenshot({path:path.join(run,'browser-'+variant+'-final.png'),fullPage:true});
 // Fail script exceptions instead of silently calling a loaded-but-broken page accepted.
 assert.equal(errors.length,0,'Browser page errors: '+errors.join('; '));report.status='PASSED';save();console.log(JSON.stringify(report));
})().catch(e=>{report.status=/No existing Chromium/.test(e.message)?'NOT_AVAILABLE':'FAILED';report.failure=e.stack||String(e);save();console.error(report.failure);process.exitCode=1;}).finally(async()=>{if(browser)await browser.close();});
