USE ecommerce_analysis;

ALTER TABLE product
    ADD COLUMN IF NOT EXISTS brand VARCHAR(100) COMMENT '商品品牌' AFTER name,
    ADD COLUMN IF NOT EXISTS price DECIMAL(10,2) DEFAULT NULL COMMENT '商品价格' AFTER category_name;

ALTER TABLE user_behavior
    DROP COLUMN IF EXISTS category_code,
    DROP COLUMN IF EXISTS brand,
    DROP COLUMN IF EXISTS user_session;

CREATE TABLE IF NOT EXISTS product_public_mapping (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键ID',
    item_id BIGINT NOT NULL COMMENT '内部商品ID',
    source_platform VARCHAR(32) NOT NULL COMMENT '公网来源平台(jd)',
    source_product_id VARCHAR(64) NOT NULL COMMENT '公网商品ID',
    source_url VARCHAR(500) NOT NULL COMMENT '公网商品链接',
    verified_title VARCHAR(255) COMMENT '人工核验时记录的公网标题',
    mapping_confidence DECIMAL(4,2) DEFAULT 1.00 COMMENT '映射置信度',
    verification_note VARCHAR(255) NOT NULL COMMENT '人工校验说明',
    evidence_note VARCHAR(255) COMMENT '补充证据说明',
    verified_at DATETIME COMMENT '人工校验时间',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    UNIQUE KEY uk_item_platform (item_id, source_platform),
    UNIQUE KEY uk_source_platform_product (source_platform, source_product_id),
    INDEX idx_mapping_item_id (item_id),
    INDEX idx_source_platform (source_platform)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商品公网映射表';

CREATE TABLE IF NOT EXISTS product_public_metric (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键ID',
    item_id BIGINT NOT NULL COMMENT '内部商品ID',
    source_platform VARCHAR(32) NOT NULL COMMENT '公网来源平台(jd)',
    source_product_id VARCHAR(64) COMMENT '公网商品ID',
    source_url VARCHAR(500) COMMENT '公网商品链接',
    positive_rate DECIMAL(6,4) DEFAULT NULL COMMENT '好评率(百分比)',
    review_count BIGINT DEFAULT NULL COMMENT '评论总数',
    shop_score DECIMAL(6,2) DEFAULT NULL COMMENT '店铺评分',
    rating_text VARCHAR(100) COMMENT '平台原始评分文案',
    crawl_status VARCHAR(32) DEFAULT 'pending' COMMENT '抓取状态',
    raw_payload TEXT COMMENT '原始响应摘要',
    crawled_at DATETIME DEFAULT NULL COMMENT '最近抓取时间',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    UNIQUE KEY uk_metric_item_platform (item_id, source_platform),
    INDEX idx_metric_item_id (item_id),
    INDEX idx_metric_platform (source_platform),
    INDEX idx_metric_status (crawl_status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商品公网满意度指标表';
