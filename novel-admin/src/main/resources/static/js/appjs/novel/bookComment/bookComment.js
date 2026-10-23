var prefix='/novel/bookComment',replyMode=/\/replies$/.test(location.pathname),reviewRows=[];
function esc(value){return $('<div>').text(value==null?'':value).html();}
$(function(){ $('#exampleTable').bootstrapTable({url:prefix+(replyMode?'/replyList':'/list'),sidePagination:'server',pagination:true,pageSize:10,
 queryParams:function(p){var q={offset:p.offset,limit:p.limit},v=$('#auditFilter').val();if(v!=='')q[replyMode?'status':'auditStatus']=v;return q;},
 responseHandler:function(r){if(r.code!==0){layer.alert(r.msg);return {rows:[],total:0};}reviewRows=r.data.rows;return r.data;},
 columns:[{field:'id',title:'ID'},{field:replyMode?'commentId':'bookName',title:replyMode?'主评论ID':'书籍',escape:true},
 {field:replyMode?'replyContent':'commentContent',title:'正文',escape:true},
 {field:'auditStatus',title:'状态',formatter:function(v){return ['待审核','通过','拒绝'][v]||'未知';}},
 {field:'moderationVersion',title:'版本'},
 {title:'操作',formatter:function(v,row,i){var h='';if(mayEdit)h='<button onclick="review('+i+',1)">通过</button> <button onclick="review('+i+',2)">拒绝</button> <button onclick="editBody('+i+')">编辑正文</button>';if(mayRemove)h+=' <button onclick="removeRow('+i+')">删除</button>';return h;}}]});});
function reLoad(){$('#exampleTable').bootstrapTable('refresh');}
function send(route,data){$.ajax({type:'POST',url:prefix+route,data:data,dataType:'json',success:function(r){if(r.code===0){reLoad();}else{layer.alert(r.msg);if(r.code===3002)reLoad();}},error:function(){layer.alert('网络异常，请刷新后确认结果');}});}
function review(i,status){var row=reviewRows[i];layer.confirm('确认'+(status===1?'通过':'拒绝')+'以下正文？<pre>'+esc(replyMode?row.replyContent:row.commentContent)+'</pre>',function(index){layer.close(index);send(replyMode?'/replyAudit':'/audit',{id:row.id,version:row.moderationVersion,status:status});});}
function editBody(i){var row=reviewRows[i];layer.prompt({title:'编辑后重新进入待审核（最多512字）',formType:2,value:replyMode?row.replyContent:row.commentContent},function(value,index){if(!value.trim()||Array.from(value).length>512){layer.alert('请输入1至512字');return;}layer.close(index);var d={id:row.id};if(replyMode){d.version=row.moderationVersion;d.content=value;}else{d.moderationVersion=row.moderationVersion;d.commentContent=value;}send(replyMode?'/replyEdit':'/update',d);});}
function removeRow(i){var row=reviewRows[i];layer.confirm('确定删除？删除主评论将同时删除其回复。',function(index){layer.close(index);send(replyMode?'/replyRemove':'/remove',{id:row.id});});}
