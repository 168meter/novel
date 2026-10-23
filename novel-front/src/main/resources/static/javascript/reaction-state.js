/* Explicit intent over HTTP; one in-flight operation per kind/id. No cross-tab global ordering. */
var ReactionUI=(function(){
  function createCoordinator(io,paint,notify,login){
    var entries=Object.create(null);
    function entry(kind,id){var key=kind+':'+String(id);return entries[key]||(entries[key]={kind:kind,id:String(id),token:0,state:null,busy:false,login:false,hidden:false});}
    function draw(e){paint(e.kind,e.id,e.state,e.busy,e.hidden);}
    function count(value){return typeof value==='string'?/^(0|[1-9]\d*)$/.test(value):Number.isSafeInteger(value)&&value>=0;}
    function valid(s){return s&&count(s.likesCount)&&count(s.unLikesCount)&&typeof s.liked==='boolean'&&typeof s.disliked==='boolean';}
    function read(e){
      e.busy=true;e.hidden=false;var token=++e.token;draw(e);
      io({method:'GET',kind:e.kind,id:e.id},function(result){
        if(token!==e.token)return;
        result=result||{};e.busy=false;
        if(result.code===200&&valid(result.data)){e.state=result.data;e.login=false;draw(e);return;}
        e.state=null;e.login=result.code===1001;e.hidden=result.code===3003||result.code===400;draw(e);
        if(!e.login)notify(result.msg||'读取赞踩状态失败，请重试读取');
      },function(){if(token!==e.token)return;e.busy=false;e.state=null;draw(e);notify('赞踩状态暂不可用，下次点击将仅重新读取');});
    }
    return {
      mount:function(kind,id){var e=entry(kind,id);if(e.busy){draw(e);return;}read(e);},
      click:function(kind,id,reaction){
        var e=entry(kind,id);if(e.busy||e.hidden)return;if(e.login){login();return;}
        // Unknown result is recovered by GET only; never infer/replay an earlier write intention.
        if(!e.state){read(e);return;}
        var active=!(reaction==='like'?e.state.liked:e.state.disliked);
        e.busy=true;var token=++e.token;draw(e);
        io({method:'POST',kind:e.kind,id:e.id,reaction:reaction,active:active},function(result){
          if(token!==e.token)return;
          result=result||{};if(result.code===200&&valid(result.data)){e.busy=false;e.state=result.data;draw(e);return;}
          if(result.code===1001){e.busy=false;e.state=null;e.login=true;draw(e);login();return;}
          notify(result.msg||'写入结果未确认，正在重新读取');read(e);
        },function(){if(token!==e.token)return;notify('写入结果未确认，正在重新读取');read(e);});
      }
    };
  }
  var api={createCoordinator:createCoordinator};
  if(typeof jQuery!=='undefined'){
    var $=jQuery;
    var coordinator=createCoordinator(function(request,ok,fail){
      var reply=request.kind==='reply',params={};params[reply?'replyId':'commentId']=request.id;
      var route=reply?'replyReaction':'commentReaction';
      if(request.method==='POST'){route=(reply?'setReply':'setComment')+(request.reaction==='like'?'Like':'UnLike');params.active=request.active;}
      $.ajax({type:request.method,url:'/book/'+route,data:params,dataType:'json',cache:false,success:ok,error:fail});
    },function(kind,id,state,busy,hidden){
      $('.reaction-control').filter(function(){return $(this).attr('data-reaction-kind')===kind&&$(this).attr('data-reaction-id')===id;}).each(function(){
        var button=$(this),like=button.attr('data-reaction-choice')==='like',selected=!!state&&(like?state.liked:state.disliked),disabled=busy||hidden;
        button.toggleClass('reaction-selected',selected).attr('aria-pressed',String(selected)).attr('aria-disabled',String(disabled))
          .css('pointer-events',disabled?'none':'').css('opacity',disabled?'.55':'').css('color',selected?'#e65c00':'').css('font-weight',selected?'bold':'');
        if(state)button.find('i.num').text('('+(like?state.likesCount:state.unLikesCount)+')');
        else if(hidden)button.find('i.num').text('(—)');
      });
    },function(message){layer.alert(message);},function(){location.href='/user/login.html?originUrl='+encodeURIComponent(location.href);});
    api.click=coordinator.click;
    api.mount=function(kind){
      var ids=Object.create(null);
      $('i[id^="likeCount"],i[id^="unLikeCount"]').each(function(){
        var like=this.id.indexOf('likeCount')===0,id=this.id.substring(like?9:11);
        if(!/^\d+$/.test(id))return;
        $(this).closest('a').addClass('reaction-control').attr('data-reaction-kind',kind).attr('data-reaction-id',id).attr('data-reaction-choice',like?'like':'dislike');ids[id]=true;
      });
      Object.keys(ids).forEach(function(id){coordinator.mount(kind,id);});
    };
  }
  return api;
})();
