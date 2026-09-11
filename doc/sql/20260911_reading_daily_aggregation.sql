CREATE TABLE IF NOT EXISTS `book_reading_daily` (
  `stat_date` date NOT NULL COMMENT 'Asia/Shanghai 统计日期',
  `book_id` bigint NOT NULL COMMENT '书籍 ID',
  `credited_seconds` bigint unsigned NOT NULL DEFAULT 0 COMMENT '有效阅读秒数',
  `heartbeat_count` bigint unsigned NOT NULL DEFAULT 0 COMMENT '已计入心跳数',
  `first_event_at` datetime(3) NOT NULL COMMENT '首次事件时间',
  `last_event_at` datetime(3) NOT NULL COMMENT '最近事件时间',
  `create_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `update_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
      ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`stat_date`, `book_id`),
  KEY `idx_book_reading_daily_book_date` (`book_id`, `stat_date`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='书籍每日有效阅读聚合';

CREATE TABLE IF NOT EXISTS `reading_event_dedup` (
  `event_id` char(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `event_fingerprint` binary(32) NOT NULL COMMENT '规范化业务载荷 SHA-256',
  `batch_token` char(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `stat_date` date NOT NULL,
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`event_id`),
  KEY `idx_reading_event_dedup_batch` (`batch_token`),
  KEY `idx_reading_event_dedup_created` (`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='阅读事件短期幂等登记';
