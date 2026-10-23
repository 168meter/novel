const fs=require('fs'),path=require('path'),vm=require('vm'),assert=require('assert/strict');
const root=path.resolve(__dirname,'../..');let tests=0;
for(const prefix of ['novel-front/src/main/resources','templates/green','templates/orange']){
 const base=prefix.startsWith('novel-')?'templates':'html';
 const html=fs.readFileSync(path.join(root,prefix,base,'user/comment.html'),'utf8');
 const script=html.match(/<script>\s*var showReplies[\s\S]*?<\/script>/)[0].replace(/^<script>|<\/script>$/g,'');
 let requests=[],renders=0;
 const stub={empty(){renders++;return this},text(){return this},appendTo(){return this},on(){return this}};
 const $=()=>Object.create(stub);$.ajax=opts=>requests.push(opts);
 const ctx={$,$: $,layer:{alert(){}},ModerationUI:{status(){return ''},edit(){}},layui:{use(name,cb){cb()},laypage:{render(){}}}};
 vm.createContext(ctx);vm.runInContext(script,ctx);
 vm.runInContext('showReplies=true;search(1,5)',ctx);
 requests[0].success({code:200,data:{list:[{id:1,moderationVersion:0,commentContent:'comment',auditStatus:0}],total:1,pageNum:1,pageSize:5}});
 assert.equal(renders,0,'stale comment response must not render as reply in '+prefix);tests++;
 requests[1].success({code:200,data:{list:[{id:1,moderationVersion:0,replyContent:'reply',auditStatus:0,parentAuditStatus:1}],total:1,pageNum:1,pageSize:5}});
 assert.equal(renders,1);tests++;
 const js=fs.readFileSync(path.join(root,prefix,'static/javascript/comment-moderation.js'),'utf8');const helpers={};vm.createContext(helpers);vm.runInContext(js,helpers);
 assert.equal(helpers.ModerationUI.escape('<img src=x onerror="x">'),'&lt;img src=x onerror=&quot;x&quot;&gt;');tests++;
 new vm.Script(fs.readFileSync(path.join(root,prefix,'static/javascript/bookdetail.js'),'utf8'));tests++;
}
new vm.Script(fs.readFileSync(path.join(root,'novel-admin/src/main/resources/static/js/appjs/novel/bookComment/bookComment.js'),'utf8'));tests++;
process.stdout.write('PASS: '+tests+' moderation UI checks (Node VM, not browser acceptance)\n');
