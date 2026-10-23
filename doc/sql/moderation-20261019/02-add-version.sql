-- NOT EXECUTED. Back up the selected schema and stop old/new front/admin writes first.
-- In this same client session explicitly SET @moderation_expected_database='reviewed_schema'; SET @moderation_writes_paused=1;
-- MySQL DDL commits separately. If the second ALTER fails, retain the first and resolve manually; do not blindly rerun.
DELIMITER $$
CREATE PROCEDURE cv_moderation_add_version_20261019()
BEGIN
  IF @moderation_expected_database IS NULL OR DATABASE() IS NULL OR BINARY DATABASE()<>BINARY @moderation_expected_database OR COALESCE(@moderation_writes_paused,0)<>1 THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Explicit schema identity and maintenance write-pause acknowledgement required';
  END IF;
  IF VERSION() NOT LIKE '8.%' THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='MySQL 8 required'; END IF;
  IF (SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name IN('book','book_comment','book_comment_reply') AND engine='InnoDB')<>3 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='All interaction tables must be InnoDB'; END IF;
  IF EXISTS(SELECT 1 FROM book_comment WHERE audit_status IS NULL OR audit_status NOT IN(0,1,2) OR book_id IS NULL OR create_user_id IS NULL)
    OR EXISTS(SELECT 1 FROM book_comment_reply WHERE audit_status IS NULL OR audit_status NOT IN(0,1,2) OR comment_id IS NULL OR create_user_id IS NULL)
    OR EXISTS(SELECT 1 FROM book_comment c LEFT JOIN book b ON b.id=c.book_id WHERE b.id IS NULL)
    OR EXISTS(SELECT 1 FROM book_comment_reply r LEFT JOIN book_comment c ON c.id=r.comment_id WHERE c.id IS NULL)
    OR EXISTS(SELECT 1 FROM book_comment c LEFT JOIN user u ON u.id=c.create_user_id WHERE u.id IS NULL)
    OR EXISTS(SELECT 1 FROM book_comment_reply r LEFT JOIN user u ON u.id=r.create_user_id WHERE u.id IS NULL)
    OR EXISTS(SELECT 1 FROM book_comment GROUP BY book_id,create_user_id HAVING COUNT(*)>1) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Invalid status, owner, duplicate or orphan data; inspect preflight; no automatic repair';
  END IF;
  IF EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name IN('book_comment','book_comment_reply') AND column_name='moderation_version') THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Version column already exists; inspect partial/prior migration';
  END IF;
  IF NOT EXISTS(SELECT 1 FROM (SELECT index_name,non_unique,GROUP_CONCAT(column_name ORDER BY seq_in_index) cols FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='book_comment' GROUP BY index_name,non_unique) s WHERE non_unique=0 AND cols='book_id,create_user_id') THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Existing unique(book_id,create_user_id) required';
  END IF;
  ALTER TABLE book_comment ADD COLUMN moderation_version BIGINT NOT NULL DEFAULT 0, MODIFY audit_status TINYINT NOT NULL DEFAULT 0;
  ALTER TABLE book_comment_reply ADD COLUMN moderation_version BIGINT NOT NULL DEFAULT 0, MODIFY audit_status TINYINT NOT NULL DEFAULT 0;
END$$
CALL cv_moderation_add_version_20261019()$$
DROP PROCEDURE cv_moderation_add_version_20261019$$
DELIMITER ;
