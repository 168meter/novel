-- NOT EXECUTED. Roll back counts only while ALL interaction writers remain stopped.
-- Refuse if ids, bodies, ownership, statuses, versions or migrated counts changed. Keep version columns and backups.
DELIMITER $$
CREATE PROCEDURE cv_moderation_rollback_20261019()
BEGIN
  DECLARE EXIT HANDLER FOR SQLEXCEPTION BEGIN ROLLBACK; RESIGNAL; END;
  IF @moderation_expected_database IS NULL OR DATABASE() IS NULL OR BINARY DATABASE()<>BINARY @moderation_expected_database OR COALESCE(@moderation_writes_paused,0)<>1 THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Explicit schema identity and maintenance write-pause acknowledgement required';
  END IF;
  IF VERSION() NOT LIKE '8.%' THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='MySQL 8 required'; END IF;
  IF (SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name IN('book','book_comment','book_comment_reply') AND engine='InnoDB')<>3 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='All interaction tables must be InnoDB'; END IF;
  IF NOT EXISTS(SELECT 1 FROM cv_mod_marker_20261019 WHERE BINARY schema_name=BINARY DATABASE() AND state='RECOUNTED') THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='No completed recount for this schema'; END IF;
  IF (SELECT COUNT(*) FROM book)<>(SELECT COUNT(*) FROM cv_mod_book_backup_20261019)
    OR (SELECT COUNT(*) FROM book_comment)<>(SELECT COUNT(*) FROM cv_mod_comment_backup_20261019)
    OR (SELECT COUNT(*) FROM book_comment_reply)<>(SELECT COUNT(*) FROM cv_mod_reply_backup_20261019)
    OR EXISTS(SELECT 1 FROM book b LEFT JOIN cv_mod_book_backup_20261019 s ON s.id=b.id WHERE s.id IS NULL OR NOT(b.comment_count <=> s.new_count))
    OR EXISTS(SELECT 1 FROM book_comment c LEFT JOIN cv_mod_comment_backup_20261019 s ON s.id=c.id WHERE s.id IS NULL OR NOT(c.reply_count <=> s.new_count) OR s.row_hash<>SHA2(JSON_ARRAY(c.id,c.book_id,c.create_user_id,c.audit_status,c.moderation_version,COALESCE(c.comment_content,''),COALESCE(c.location,''),COALESCE(CAST(c.create_time AS CHAR),'')),256))
    OR EXISTS(SELECT 1 FROM book_comment_reply r LEFT JOIN cv_mod_reply_backup_20261019 s ON s.id=r.id WHERE s.id IS NULL OR s.row_hash<>SHA2(JSON_ARRAY(r.id,r.comment_id,r.create_user_id,r.audit_status,r.moderation_version,COALESCE(r.reply_content,''),COALESCE(r.location,''),COALESCE(CAST(r.create_time AS CHAR),'')),256)) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Data changed since recount; automatic rollback refused';
  END IF;
  START TRANSACTION;
  UPDATE book b JOIN cv_mod_book_backup_20261019 s ON s.id=b.id SET b.comment_count=s.old_count;
  UPDATE book_comment c JOIN cv_mod_comment_backup_20261019 s ON s.id=c.id SET c.reply_count=s.old_count;
  UPDATE cv_mod_marker_20261019 SET state='ROLLED_BACK' WHERE schema_name=DATABASE();
  COMMIT;
END$$
CALL cv_moderation_rollback_20261019()$$
DROP PROCEDURE cv_moderation_rollback_20261019$$
DELIMITER ;
