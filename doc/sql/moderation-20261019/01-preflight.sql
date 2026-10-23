-- READ ONLY. Select the intended schema explicitly in the client; never use an admin/source default.
SELECT DATABASE() AS target_schema,VERSION() AS server_version;
SELECT table_name,engine FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name IN ('book','book_comment','book_comment_reply');
SELECT 'invalid_comment_status' issue,COUNT(*) affected FROM book_comment WHERE audit_status IS NULL OR audit_status NOT IN(0,1,2)
UNION ALL SELECT 'invalid_reply_status',COUNT(*) FROM book_comment_reply WHERE audit_status IS NULL OR audit_status NOT IN(0,1,2)
UNION ALL SELECT 'orphan_comment_book',COUNT(*) FROM book_comment c LEFT JOIN book b ON b.id=c.book_id WHERE b.id IS NULL
UNION ALL SELECT 'orphan_reply_parent',COUNT(*) FROM book_comment_reply r LEFT JOIN book_comment c ON c.id=r.comment_id WHERE c.id IS NULL
UNION ALL SELECT 'missing_comment_owner',COUNT(*) FROM book_comment c LEFT JOIN user u ON u.id=c.create_user_id WHERE u.id IS NULL
UNION ALL SELECT 'missing_reply_owner',COUNT(*) FROM book_comment_reply r LEFT JOIN user u ON u.id=r.create_user_id WHERE u.id IS NULL;
SELECT book_id,create_user_id,COUNT(*) duplicates FROM book_comment GROUP BY book_id,create_user_id HAVING COUNT(*)>1;
SELECT index_name,non_unique,GROUP_CONCAT(column_name ORDER BY seq_in_index) indexed_columns FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='book_comment' GROUP BY index_name,non_unique;
SELECT b.id,b.comment_count old_count,COALESCE(c.n,0) approved_count FROM book b LEFT JOIN (SELECT book_id,COUNT(*) n FROM book_comment WHERE audit_status=1 GROUP BY book_id)c ON c.book_id=b.id WHERE NOT(b.comment_count <=> COALESCE(c.n,0));
SELECT c.id,c.reply_count old_count,COALESCE(r.n,0) approved_count FROM book_comment c LEFT JOIN (SELECT comment_id,COUNT(*) n FROM book_comment_reply WHERE audit_status=1 GROUP BY comment_id)r ON r.comment_id=c.id WHERE NOT(c.reply_count <=> COALESCE(r.n,0));
