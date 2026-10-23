-- Additive migration only. Run once after inspecting SHOW INDEX on the target.
-- Existing UNIQUE(book_id, create_user_id) remains the concurrency constraint.
-- No data deletion or uniqueness change; review DDL locking before deployment.
CREATE INDEX idx_comment_book_page ON book_comment(book_id, create_time, id);
CREATE INDEX idx_comment_user_page ON book_comment(create_user_id, create_time, id);
CREATE INDEX idx_reply_comment_page ON book_comment_reply(comment_id, create_time, id);
CREATE INDEX idx_reply_user_page ON book_comment_reply(create_user_id, create_time, id);
