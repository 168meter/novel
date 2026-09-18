-- Run against the selected application database before deploying authentication changes.
-- Re-runnable after interruption; never overwrite a password or relabel a migrated user.
ALTER TABLE `user` MODIFY COLUMN `password` varchar(255) NOT NULL,
    MODIFY COLUMN `username` varchar(50) NULL;

SET @auth_sql = (SELECT IF(COUNT(*) = 0,
    'ALTER TABLE `user` ADD COLUMN `password_algorithm` varchar(20) NULL', 'SELECT 1')
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'user' AND COLUMN_NAME = 'password_algorithm');
PREPARE auth_statement FROM @auth_sql;
EXECUTE auth_statement;
DEALLOCATE PREPARE auth_statement;

UPDATE `user` SET `password_algorithm` = 'MD5' WHERE `password_algorithm` IS NULL;
ALTER TABLE `user` MODIFY COLUMN `password_algorithm` varchar(20) NOT NULL DEFAULT 'ARGON2ID';

SET @auth_sql = (SELECT IF(COUNT(*) = 0,
    'ALTER TABLE `user` ADD COLUMN `email` varchar(254) NULL', 'SELECT 1')
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'user' AND COLUMN_NAME = 'email');
PREPARE auth_statement FROM @auth_sql;
EXECUTE auth_statement;
DEALLOCATE PREPARE auth_statement;

SET @auth_sql = (SELECT IF(COUNT(*) = 0,
    'ALTER TABLE `user` ADD COLUMN `token_version` bigint NOT NULL DEFAULT 0', 'SELECT 1')
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'user' AND COLUMN_NAME = 'token_version');
PREPARE auth_statement FROM @auth_sql;
EXECUTE auth_statement;
DEALLOCATE PREPARE auth_statement;

SET @auth_sql = (SELECT IF(COUNT(*) = 0,
    'ALTER TABLE `user` ADD COLUMN `email_verified_at` datetime NULL', 'SELECT 1')
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'user' AND COLUMN_NAME = 'email_verified_at');
PREPARE auth_statement FROM @auth_sql;
EXECUTE auth_statement;
DEALLOCATE PREPARE auth_statement;

-- A conflicting existing index must fail closed without replacing or deleting it.
-- The no-FROM diagnostic SELECT deliberately fails with an unknown-column error.
-- It works at PREPARE time without routines, delimiters, or extra database privileges.
SET @auth_sql = (SELECT IF(COUNT(*) = 0,
    'ALTER TABLE `user` ADD UNIQUE INDEX `key_uq_email` (`email`)',
    IF(COUNT(*) = 1 AND SUM(NON_UNIQUE = 0 AND COLUMN_NAME = 'email'
        AND SEQ_IN_INDEX = 1 AND SUB_PART IS NULL) = 1,
        'SELECT 1', 'SELECT `AUTH_SCHEMA_ERROR_key_uq_email_must_be_single_full_email_unique`'))
    FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'user' AND INDEX_NAME = 'key_uq_email');
PREPARE auth_statement FROM @auth_sql;
EXECUTE auth_statement;
DEALLOCATE PREPARE auth_statement;
