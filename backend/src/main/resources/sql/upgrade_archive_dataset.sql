USE ecommerce_analysis;

SET @ddl = IF (
    EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_schema = 'ecommerce_analysis'
          AND table_name = 'product'
          AND column_name = 'brand'
    ),
    'SELECT 1',
    'ALTER TABLE product ADD COLUMN brand VARCHAR(100) COMMENT ''brand'' AFTER name'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @ddl = IF (
    EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_schema = 'ecommerce_analysis'
          AND table_name = 'product'
          AND column_name = 'price'
    ),
    'SELECT 1',
    'ALTER TABLE product ADD COLUMN price DECIMAL(10,2) DEFAULT NULL COMMENT ''price'' AFTER category_name'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @ddl = IF (
    EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_schema = 'ecommerce_analysis'
          AND table_name = 'user_behavior'
          AND column_name = 'category_code'
    ),
    'ALTER TABLE user_behavior DROP COLUMN category_code',
    'SELECT 1'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @ddl = IF (
    EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_schema = 'ecommerce_analysis'
          AND table_name = 'user_behavior'
          AND column_name = 'brand'
    ),
    'ALTER TABLE user_behavior DROP COLUMN brand',
    'SELECT 1'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @ddl = IF (
    EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_schema = 'ecommerce_analysis'
          AND table_name = 'user_behavior'
          AND column_name = 'user_session'
    ),
    'ALTER TABLE user_behavior DROP COLUMN user_session',
    'SELECT 1'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @ddl = IF (
    EXISTS (
        SELECT 1
        FROM information_schema.statistics
        WHERE table_schema = 'ecommerce_analysis'
          AND table_name = 'user_behavior'
          AND index_name = 'idx_behavior_type_item'
    ),
    'SELECT 1',
    'ALTER TABLE user_behavior ADD INDEX idx_behavior_type_item (behavior_type, item_id)'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @ddl = IF (
    EXISTS (
        SELECT 1
        FROM information_schema.statistics
        WHERE table_schema = 'ecommerce_analysis'
          AND table_name = 'user_behavior'
          AND index_name = 'idx_behavior_type_user'
    ),
    'SELECT 1',
    'ALTER TABLE user_behavior ADD INDEX idx_behavior_type_user (behavior_type, user_id)'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @ddl = IF (
    EXISTS (
        SELECT 1
        FROM information_schema.statistics
        WHERE table_schema = 'ecommerce_analysis'
          AND table_name = 'user_profile'
          AND index_name = 'idx_group_rfm_buy'
    ),
    'SELECT 1',
    'ALTER TABLE user_profile ADD INDEX idx_group_rfm_buy (user_group, rfm_score, total_buys)'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

CREATE TABLE IF NOT EXISTS product_public_mapping (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT 'primary key',
    item_id BIGINT NOT NULL COMMENT 'internal item id',
    source_platform VARCHAR(32) NOT NULL COMMENT 'source platform',
    source_product_id VARCHAR(64) NOT NULL COMMENT 'source product id',
    source_url VARCHAR(500) NOT NULL COMMENT 'source product url',
    verified_title VARCHAR(255) COMMENT 'verified public title',
    mapping_confidence DECIMAL(4,2) DEFAULT 1.00 COMMENT 'mapping confidence',
    verification_note VARCHAR(255) NOT NULL COMMENT 'verification note',
    evidence_note VARCHAR(255) COMMENT 'evidence note',
    verified_at DATETIME COMMENT 'verified at',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT 'created at',
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT 'updated at',
    UNIQUE KEY uk_item_platform (item_id, source_platform),
    UNIQUE KEY uk_source_platform_product (source_platform, source_product_id),
    INDEX idx_mapping_item_id (item_id),
    INDEX idx_source_platform (source_platform)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='public product mapping';

CREATE TABLE IF NOT EXISTS product_public_metric (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT 'primary key',
    item_id BIGINT NOT NULL COMMENT 'internal item id',
    source_platform VARCHAR(32) NOT NULL COMMENT 'source platform',
    source_product_id VARCHAR(64) COMMENT 'source product id',
    source_url VARCHAR(500) COMMENT 'source product url',
    positive_rate DECIMAL(6,4) DEFAULT NULL COMMENT 'positive rate',
    review_count BIGINT DEFAULT NULL COMMENT 'review count',
    shop_score DECIMAL(6,2) DEFAULT NULL COMMENT 'shop score',
    rating_text VARCHAR(100) COMMENT 'rating text',
    crawl_status VARCHAR(32) DEFAULT 'pending' COMMENT 'crawl status',
    raw_payload TEXT COMMENT 'raw payload',
    crawled_at DATETIME DEFAULT NULL COMMENT 'crawled at',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT 'created at',
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT 'updated at',
    UNIQUE KEY uk_metric_item_platform (item_id, source_platform),
    INDEX idx_metric_item_id (item_id),
    INDEX idx_metric_platform (source_platform),
    INDEX idx_metric_status (crawl_status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='public product metrics';
