-- NOT EXECUTED. Same identity/write-pause guards as 02. Fixed backup names deliberately reject reruns.
-- Keep backups after success/rollback. DDL creates only backup tables; data snapshot + recount are one transaction.
DELIMITER $$
CREATE PROCEDURE cv_moderation_recount_20261019()
BEGIN
  DECLARE EXIT HANDLER FOR SQLEXCEPTION BEGIN ROLLBACK; RESIGNAL; END;
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
  IF (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name IN('book_comment','book_comment_reply') AND column_name='moderation_version' AND is_nullable='NO')<>2 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Install both version columns first'; END IF;
  CREATE TABLE cv_mod_book_backup_20261019(id BIGINT PRIMARY KEY,old_count INT NULL,new_count BIGINT NOT NULL) ENGINE=InnoDB;
  CREATE TABLE cv_mod_comment_backup_20261019(id BIGINT PRIMARY KEY,old_count INT NULL,new_count BIGINT NOT NULL,row_hash CHAR(64) NOT NULL) ENGINE=InnoDB;
  CREATE TABLE cv_mod_reply_backup_20261019(id BIGINT PRIMARY KEY,row_hash CHAR(64) NOT NULL) ENGINE=InnoDB;
  CREATE TABLE cv_mod_marker_20261019(schema_name VARCHAR(64) PRIMARY KEY,state VARCHAR(20) NOT NULL) ENGINE=InnoDB;
  START TRANSACTION;
  INSERT INTO cv_mod_book_backup_20261019 SELECT b.id,b.comment_count,(SELECT COUNT(*) FROM book_comment c WHERE c.book_id=b.id AND c.audit_status=1) FROM book b;
  INSERT INTO cv_mod_comment_backup_20261019 SELECT c.id,c.reply_count,(SELECT COUNT(*) FROM book_comment_reply r WHERE r.comment_id=c.id AND r.audit_status=1),SHA2(JSON_ARRAY(c.id,c.book_id,c.create_user_id,c.audit_status,c.moderation_version,COALESCE(c.comment_content,''),COALESCE(c.location,''),COALESCE(CAST(c.create_time AS CHAR),'')),256) FROM book_comment c;
  INSERT INTO cv_mod_reply_backup_20261019 SELECT r.id,SHA2(JSON_ARRAY(r.id,r.comment_id,r.create_user_id,r.audit_status,r.moderation_version,COALESCE(r.reply_content,''),COALESCE(r.location,''),COALESCE(CAST(r.create_time AS CHAR),'')),256) FROM book_comment_reply r;
  UPDATE book b JOIN cv_mod_book_backup_20261019 s ON s.id=b.id SET b.comment_count=s.new_count;
  UPDATE book_comment c JOIN cv_mod_comment_backup_20261019 s ON s.id=c.id SET c.reply_count=s.new_count;
  INSERT INTO cv_mod_marker_20261019 VALUES(DATABASE(),'RECOUNTED');
  COMMIT;
END$$
CALL cv_moderation_recount_20261019()$$
DROP PROCEDURE cv_moderation_recount_20261019$$
DELIMITER ;
