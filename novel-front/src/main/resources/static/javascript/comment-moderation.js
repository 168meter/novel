/* Comment content is plain text. Author-private state never changes public totals. */
var ModerationUI = {
    escape: function(value) {return String(value == null ? '' : value).replace(/[&<>"']/g,function(c){return {'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c];});},
    status: function(status) {return ['待审核','已通过','未通过'][status] || '状态未知';},
    edit: function(reply,id,version,content,done) {
        layer.prompt({title:'修改后将重新审核（最多512字）',formType:2,value:content},function(value,index){
            if(!value.trim() || Array.from(value).length>512) {layer.alert('请输入1至512字');return;}
            $.ajax({type:'POST',url:'/book/'+(reply?'editReply':'editComment'),data:{id:id,version:version,content:value},dataType:'json',
                success:function(result){if(result.code===200){layer.close(index);layer.alert('已提交，审核后展示');done();}else{layer.alert(result.msg);}},
                error:function(){layer.alert('网络异常，请刷新确认状态后重试');}});
        });
    }
};
