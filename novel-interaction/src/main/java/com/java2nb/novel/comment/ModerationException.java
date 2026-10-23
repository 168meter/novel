package com.java2nb.novel.comment;
public class ModerationException extends RuntimeException {
    private final int code;
    public ModerationException(int code) {super(switch(code){case 1001 -> "请先登录";case 3001 -> "已评价过该书籍";case 3002 -> "正文或审核状态已变化，请刷新后重试";case 3003 -> "评论不存在或暂不可见";case 3004 -> "只能修改自己的评论或回复";default -> "请求参数或存量数据不符合审核约束";});this.code=code;}
    public int getCode(){return code;}
}
