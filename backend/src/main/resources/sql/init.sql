-- Initial schema for ecommerce_analysis

CREATE DATABASE IF NOT EXISTS ecommerce_analysis
    DEFAULT CHARACTER SET utf8mb4
    DEFAULT COLLATE utf8mb4_unicode_ci;

USE ecommerce_analysis;

DROP TABLE IF EXISTS sys_user;
CREATE TABLE sys_user (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT 'primary key',
    username VARCHAR(50) NOT NULL UNIQUE COMMENT 'username',
    password VARCHAR(100) NOT NULL COMMENT 'encrypted password',
    email VARCHAR(100) COMMENT 'email',
    phone VARCHAR(20) COMMENT 'phone',
    real_name VARCHAR(50) COMMENT 'real name',
    avatar VARCHAR(255) COMMENT 'avatar url',
    role VARCHAR(20) DEFAULT 'user' COMMENT 'admin or user',
    status TINYINT DEFAULT 1 COMMENT '1 enabled, 0 disabled',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT 'created at',
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT 'updated at',
    deleted TINYINT DEFAULT 0 COMMENT 'logical delete flag',
    INDEX idx_username (username),
    INDEX idx_role (role)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='system users';

INSERT INTO sys_user (username, password, email, role, real_name) VALUES
('admin', '$2a$10$n8yMUVw2Lrj3Cq.vFUs7ieMYB4dFXSSv.cHx0TYmEyKpJz14q2ErC', 'admin@example.com', 'admin', 'System Admin');

DROP TABLE IF EXISTS user_behavior;
CREATE TABLE user_behavior (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT 'primary key',
    event_id VARCHAR(64) COMMENT 'event unique id',
    user_id BIGINT NOT NULL COMMENT 'user id',
    item_id BIGINT NOT NULL COMMENT 'item id',
    category_id BIGINT NOT NULL COMMENT 'category id',
    behavior_type VARCHAR(10) NOT NULL COMMENT 'pv buy cart fav',
    behavior_time BIGINT NOT NULL COMMENT 'unix timestamp',
    behavior_date_time DATETIME COMMENT 'behavior datetime',
    unit_price DECIMAL(10,2) DEFAULT 0.00 COMMENT 'unit price',
    qty INT DEFAULT 1 COMMENT 'quantity',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT 'created at',
    UNIQUE KEY uk_event_id (event_id),
    INDEX idx_user_id (user_id),
    INDEX idx_item_id (item_id),
    INDEX idx_category_id (category_id),
    INDEX idx_behavior_type (behavior_type),
    INDEX idx_behavior_time (behavior_time),
    INDEX idx_behavior_date_time (behavior_date_time),
    INDEX idx_user_behavior_composite (user_id, behavior_type, behavior_time),
    INDEX idx_behavior_type_item (behavior_type, item_id),
    INDEX idx_behavior_type_user (behavior_type, user_id),
    INDEX idx_type_time_item (behavior_type, behavior_time, item_id),
    INDEX idx_type_time_user (behavior_type, behavior_time, user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='user behaviors';

DROP TABLE IF EXISTS user_profile;
CREATE TABLE user_profile (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT 'primary key',
    user_id BIGINT NOT NULL UNIQUE COMMENT 'user id',
    recency INT DEFAULT 0 COMMENT 'recency',
    frequency INT DEFAULT 0 COMMENT 'frequency',
    monetary DECIMAL(12,2) DEFAULT 0 COMMENT 'monetary',
    r_score INT DEFAULT 0 COMMENT 'r score',
    f_score INT DEFAULT 0 COMMENT 'f score',
    m_score INT DEFAULT 0 COMMENT 'm score',
    rfm_score INT DEFAULT 0 COMMENT 'rfm score',
    user_group VARCHAR(50) COMMENT 'user group',
    cluster_id INT COMMENT 'cluster id',
    total_views INT DEFAULT 0 COMMENT 'total views',
    total_carts INT DEFAULT 0 COMMENT 'total carts',
    total_favs INT DEFAULT 0 COMMENT 'total favs',
    total_buys INT DEFAULT 0 COMMENT 'total buys',
    conversion_rate DECIMAL(5,4) DEFAULT 0 COMMENT 'conversion rate',
    last_active_time DATETIME COMMENT 'last active time',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT 'created at',
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT 'updated at',
    INDEX idx_user_id (user_id),
    INDEX idx_user_group (user_group),
    INDEX idx_cluster_id (cluster_id),
    INDEX idx_rfm_score (rfm_score),
    INDEX idx_group_rfm_buy (user_group, rfm_score, total_buys)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='user profiles';

DROP TABLE IF EXISTS product;
CREATE TABLE product (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT 'primary key',
    item_id BIGINT NOT NULL UNIQUE COMMENT 'item id',
    name VARCHAR(200) COMMENT 'display name',
    brand VARCHAR(100) COMMENT 'brand',
    category_id BIGINT COMMENT 'category id',
    category_name VARCHAR(100) COMMENT 'category name',
    price DECIMAL(10,2) DEFAULT NULL COMMENT 'price',
    view_count INT DEFAULT 0 COMMENT 'view count',
    cart_count INT DEFAULT 0 COMMENT 'cart count',
    fav_count INT DEFAULT 0 COMMENT 'fav count',
    buy_count INT DEFAULT 0 COMMENT 'buy count',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT 'created at',
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT 'updated at',
    INDEX idx_item_id (item_id),
    INDEX idx_category_id (category_id),
    INDEX idx_buy_count (buy_count)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='products';

DROP TABLE IF EXISTS category;
CREATE TABLE category (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT 'primary key',
    category_id BIGINT NOT NULL UNIQUE COMMENT 'category id',
    name VARCHAR(100) COMMENT 'name',
    view_count INT DEFAULT 0 COMMENT 'view count',
    cart_count INT DEFAULT 0 COMMENT 'cart count',
    fav_count INT DEFAULT 0 COMMENT 'fav count',
    buy_count INT DEFAULT 0 COMMENT 'buy count',
    product_count INT DEFAULT 0 COMMENT 'product count',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT 'created at',
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT 'updated at',
    INDEX idx_category_id (category_id),
    INDEX idx_buy_count (buy_count)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='categories';

DROP TABLE IF EXISTS product_public_mapping;
CREATE TABLE product_public_mapping (
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

DROP TABLE IF EXISTS product_public_metric;
CREATE TABLE product_public_metric (
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

DROP TABLE IF EXISTS analysis_cache;
CREATE TABLE analysis_cache (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT 'primary key',
    cache_key VARCHAR(100) NOT NULL UNIQUE COMMENT 'cache key',
    cache_value LONGTEXT COMMENT 'cache value',
    expire_time DATETIME COMMENT 'expire time',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT 'created at',
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT 'updated at',
    INDEX idx_cache_key (cache_key),
    INDEX idx_expire_time (expire_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='analysis cache';

DROP TABLE IF EXISTS sys_log;
CREATE TABLE sys_log (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT 'primary key',
    user_id BIGINT COMMENT 'user id',
    username VARCHAR(50) COMMENT 'username',
    operation VARCHAR(100) COMMENT 'operation',
    method VARCHAR(200) COMMENT 'request method',
    params TEXT COMMENT 'request params',
    ip VARCHAR(50) COMMENT 'ip address',
    duration BIGINT COMMENT 'duration ms',
    status TINYINT DEFAULT 1 COMMENT '1 success, 0 failure',
    error_msg TEXT COMMENT 'error message',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT 'created at',
    INDEX idx_user_id (user_id),
    INDEX idx_create_time (create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='system logs';

SELECT 'database initialized' AS message;
SHOW TABLES;
