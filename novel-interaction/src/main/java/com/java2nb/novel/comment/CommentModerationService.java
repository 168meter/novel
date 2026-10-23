package com.java2nb.novel.comment;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

/** All interaction writes lock book -> comment -> reply, including count changes. */
@Service
@Transactional(rollbackFor=Exception.class)
public class CommentModerationService {
    private final CommentModerationMapper mapper;
    public CommentModerationService(CommentModerationMapper mapper) { this.mapper=mapper; }

    public CommentRecord createComment(Long userId,Long bookId,String content,String location) {
        login(userId); text(content); positive(bookId);
        if(mapper.lockBook(bookId)==null) throw unavailable();
        if(mapper.existingComment(bookId,userId)>0) throw new ModerationException(3001);
        var row=new CommentRecord();row.setBookId(bookId);row.setCreateUserId(userId);
        row.setCommentContent(content);row.setLocation(location);
        try { changed(mapper.insertComment(row)); } catch(DuplicateKeyException duplicate) {
            throw new ModerationException(3001);
        }
        return mapper.findComment(row.getId());
    }
    public ReplyRecord createReply(Long userId,Long commentId,String content,String location) {
        login(userId);text(content);
        var parent=lockedComment(commentId);
        if(!approved(parent.getAuditStatus())) throw unavailable();
        var row=new ReplyRecord();row.setCommentId(commentId);row.setCreateUserId(userId);
        row.setReplyContent(content);row.setLocation(location);changed(mapper.insertReply(row));
        return mapper.findReply(row.getId());
    }
    public CommentRecord auditComment(Long id,Long version,int status) {
        status(status);var old=lockedComment(id);version(version,old.getModerationVersion());
        if(old.getAuditStatus().intValue()==status) return old;
        changed(mapper.auditComment(id,status,version));
        bookDelta(old.getBookId(),delta(old.getAuditStatus(),status));
        return mapper.findComment(id);
    }
    public ReplyRecord auditReply(Long id,Long version,int status) {
        status(status);var old=lockedReply(id);version(version,old.getModerationVersion());
        if(old.getAuditStatus().intValue()==status) return old;
        changed(mapper.auditReply(id,status,version));
        replyDelta(old.getCommentId(),delta(old.getAuditStatus(),status));
        return mapper.findReply(id);
    }
    /** ownerId=null is an admin-only call; controllers must enforce admin edit permissions. */
    public CommentRecord editComment(Long id,Long ownerId,Long version,String content) {
        text(content);var old=lockedComment(id);owner(ownerId,old.getCreateUserId());
        version(version,old.getModerationVersion());changed(mapper.editComment(id,content,version));
        bookDelta(old.getBookId(),delta(old.getAuditStatus(),0));return mapper.findComment(id);
    }
    public ReplyRecord editReply(Long id,Long ownerId,Long version,String content) {
        text(content);var old=lockedReply(id);owner(ownerId,old.getCreateUserId());
        version(version,old.getModerationVersion());changed(mapper.editReply(id,content,version));
        replyDelta(old.getCommentId(),delta(old.getAuditStatus(),0));return mapper.findReply(id);
    }
    public int removeComment(Long id) {
        if(id==null) return 0;
        var initial=mapper.findComment(id);if(initial==null) return 0;
        if(mapper.lockBook(initial.getBookId())==null) throw unavailable();
        var old=mapper.lockComment(id);if(old==null) return 0;
        mapper.removeReplies(id);int removed=mapper.removeComment(id);changed(removed);
        bookDelta(old.getBookId(),delta(old.getAuditStatus(),0));return removed;
    }
    public int removeReply(Long id) {
        if(id==null || mapper.findReply(id)==null) return 0;
        // Parent deletion may win before locks are acquired. That is already a successful removal.
        var initial=mapper.findReply(id);if(initial==null) return 0;
        var parent=mapper.findComment(initial.getCommentId());if(parent==null) return 0;
        if(mapper.lockBook(parent.getBookId())==null) throw unavailable();
        if(mapper.lockComment(parent.getId())==null) return 0;
        var old=mapper.lockReply(id);if(old==null) return 0;
        changed(mapper.removeReply(id));replyDelta(old.getCommentId(),delta(old.getAuditStatus(),0));return 1;
    }
    public int batchRemove(Long[] ids) {
        if(ids==null) return 0;
        var ordered=Arrays.stream(ids).filter(Objects::nonNull).distinct().sorted().toList();
        if(ordered.isEmpty()) return 0;
        // Pre-lock all books in global order, rather than assuming comment ids imply book order.
        for(Long bookId:mapper.booksForComments(ordered)) if(mapper.lockBook(bookId)==null) throw unavailable();
        int n=0;for(Long id:ordered) n+=removeComment(id);return n;
    }
    @Transactional(readOnly=true)
    public CommentRecord publicComment(Long id) {
        if(id==null || id<=0) return null;
        var row=mapper.findComment(id);return row!=null && approved(row.getAuditStatus())?row:null;
    }
    private CommentRecord lockedComment(Long id) {
        positive(id);var initial=mapper.findComment(id);if(initial==null) throw unavailable();
        if(mapper.lockBook(initial.getBookId())==null) throw unavailable();
        var row=mapper.lockComment(id);if(row==null) throw unavailable();validState(row.getAuditStatus());return row;
    }
    private ReplyRecord lockedReply(Long id) {
        positive(id);var initial=mapper.findReply(id);if(initial==null) throw unavailable();
        lockedComment(initial.getCommentId());var row=mapper.lockReply(id);if(row==null) throw unavailable();
        validState(row.getAuditStatus());return row;
    }
    private void bookDelta(Long id,int delta) { if(delta!=0) changed(mapper.changeBookCount(id,delta)); }
    private void replyDelta(Long id,int delta) { if(delta!=0) changed(mapper.changeReplyCount(id,delta)); }
    private static int delta(Byte old,int next) {validState(old);return (next==1?1:0)-(approved(old)?1:0);}
    private static boolean approved(Byte value) {return value!=null && value==1;}
    private static void validState(Byte value) {if(value==null || value<0 || value>2) throw new ModerationException(400);}
    private static void status(int value) {if(value<0 || value>2) throw new ModerationException(400);}
    private static void version(Long expected,Long actual) {
        if(expected==null || expected<0 || !Objects.equals(expected,actual)) throw new ModerationException(3002);
    }
    private static void owner(Long expected,Long actual) {if(expected!=null && !Objects.equals(expected,actual)) throw new ModerationException(3004);}
    public static void login(Long id) {if(id==null || id<=0) throw new ModerationException(1001);}
    private static void positive(Long id) {if(id==null || id<=0) throw new ModerationException(400);}
    private static void text(String s) {if(s==null || s.isBlank() || s.codePointCount(0,s.length())>512) throw new ModerationException(400);}
    private static void changed(int n) {if(n!=1) throw new ModerationException(3002);}
    private static ModerationException unavailable() {return new ModerationException(3003);}
}
