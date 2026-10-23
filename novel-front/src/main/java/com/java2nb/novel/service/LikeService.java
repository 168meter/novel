package com.java2nb.novel.service;


/**
 * @author 11797
 */
public interface LikeService {
    /** Counts/membership at the Lua execution point; concurrent later writes may change them. */
    record ReactionState(long likesCount,long unLikesCount,boolean liked,boolean disliked) {}
    ReactionState setCommentReaction(Long commentId,Long userId,boolean like,boolean active);
    ReactionState setReplyReaction(Long replyId,Long userId,boolean like,boolean active);
    ReactionState getCommentReaction(Long commentId,Long userId);
    ReactionState getReplyReaction(Long replyId,Long userId);


    /**
     * 评论点赞或取消点赞
     * @param commentId 被点赞的评论ID
     * @param userId 用户ID
     * @return 返回点赞数量
     */
    @Deprecated(since="2026-10-19",forRemoval=false)
    public Long toggleCommentLike(Long commentId, Long userId);

    /**
     * 评论点踩或取消点踩
     * @param commentId 被点踩的评论ID
     * @param userId 用户ID
     * @return 返回点踩数量
     */
    @Deprecated(since="2026-10-19",forRemoval=false)
    public Long toggleCommentUnLike(Long commentId, Long userId);

    /**
     * 获取评论的点赞数量
     * @param commentId 评论ID
     * @return 点赞数
     */
    public Long getCommentLikesCount(Long commentId);

    /**
     * 获取评论的点踩赞数量
     * @param commentId 评论ID
     * @return 点踩数
     */
    public Long getCommentUnLikesCount(Long commentId);

    /**
     * 回复点赞或取消点赞
     * @param replyId 被点赞的回复ID
     * @param userId 用户ID
     * @return 返回点赞数量
     */
    @Deprecated(since="2026-10-19",forRemoval=false)
    public Long toggleReplyLike(Long replyId, Long userId);

    /**
     * 回复点踩或取消点踩
     * @param replyId 被点踩的回复ID
     * @param userId 用户ID
     * @return 返回点踩数量
     */
    @Deprecated(since="2026-10-19",forRemoval=false)
    public Long toggleReplyUnLike(Long replyId, Long userId);

    /**
     * 获取回复的点赞数量
     * @param replyId 回复ID
     * @return 点赞数
     */
    public Long getReplyLikesCount(Long replyId);

    /**
     * 获取回复的点踩数量
     * @param replyId 回复ID
     * @return 点踩数
     */
    public Long getReplyUnLikesCount(Long replyId);

}
