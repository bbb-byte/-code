-- ========================================
-- 电商用户消费行为分析系统 - 数据库初始化脚本
-- ========================================

-- 创建数据库
CREATE DATABASE IF NOT EXISTS ecommerce_analysis 
    DEFAULT CHARACTER SET utf8mb4 
    DEFAULT COLLATE utf8mb4_unicode_ci;

USE ecommerce_analysis;

-- ----------------------------------------
-- 1. 系统用户表
-- ----------------------------------------
DROP TABLE IF EXISTS sys_user;
CREATE TABLE sys_user (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键ID',
    username VARCHAR(50) NOT NULL UNIQUE COMMENT '用户名',
    password VARCHAR(100) NOT NULL COMMENT '密码(加密)',
    email VARCHAR(100) COMMENT '邮箱',
    phone VARCHAR(20) COMMENT '手机号',
    real_name VARCHAR(50) COMMENT '真实姓名',
    avatar VARCHAR(255) COMMENT '头像URL',
    role VARCHAR(20) DEFAULT 'user' COMMENT '角色: admin/user',
    status TINYINT DEFAULT 1 COMMENT '状态: 1-启用, 0-禁用',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted TINYINT DEFAULT 0 COMMENT '逻辑删除: 0-未删除, 1-已删除',
    INDEX idx_username (username),
    INDEX idx_role (role)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='系统用户表';

-- 插入默认管理员账户 (密码: admin123)
INSERT INTO sys_user (username, password, email, role, real_name) VALUES 
('admin', '$2a$10$n8yMUVw2Lrj3Cq.vFUs7ieMYB4dFXSSv.cHx0TYmEyKpJz14q2ErC', 'admin@example.com', 'admin', '系统管理员');

-- ----------------------------------------
-- 2. 用户行为记录表 (存储 archive 多品类电商行为数据集的正式行为字段)
-- ----------------------------------------
DROP TABLE IF EXISTS user_behavior;
CREATE TABLE user_behavior (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键ID',
    event_id VARCHAR(64) COMMENT '事件唯一ID(用于去重)',
    user_id BIGINT NOT NULL COMMENT '用户ID',
    item_id BIGINT NOT NULL COMMENT '商品ID',
    category_id BIGINT NOT NULL COMMENT '商品类目ID',
    behavior_type VARCHAR(10) NOT NULL COMMENT '行为类型: pv/buy/cart/fav',
    behavior_time BIGINT NOT NULL COMMENT '行为时间戳(Unix)',
    behavior_date_time DATETIME COMMENT '行为日期时间',
    unit_price DECIMAL(10,2) DEFAULT 0.00 COMMENT '商品单价(来自 archive 正式数据集)',
    qty INT DEFAULT 1 COMMENT '购买数量(默认1)',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    UNIQUE KEY uk_event_id (event_id),
    INDEX idx_user_id (user_id),
    INDEX idx_item_id (item_id),
    INDEX idx_category_id (category_id),
    INDEX idx_behavior_type (behavior_type),
    INDEX idx_behavior_time (behavior_time),
    INDEX idx_behavior_date_time (behavior_date_time),
    INDEX idx_user_behavior_composite (user_id, behavior_type, behavior_time),
    INDEX idx_type_time_item (behavior_type, behavior_time, item_id),
    INDEX idx_type_time_user (behavior_type, behavior_time, user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户行为记录表';

-- ----------------------------------------
-- 3. 用户画像表
-- ----------------------------------------
DROP TABLE IF EXISTS user_profile;
CREATE TABLE user_profile (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键ID',
    user_id BIGINT NOT NULL UNIQUE COMMENT '用户ID',
    recency INT DEFAULT 0 COMMENT 'R-最近消费距今天数',
    frequency INT DEFAULT 0 COMMENT 'F-消费频率(购买次数)',
    monetary DECIMAL(12,2) DEFAULT 0 COMMENT 'M-消费金额',
    r_score INT DEFAULT 0 COMMENT 'R评分(1-5)',
    f_score INT DEFAULT 0 COMMENT 'F评分(1-5)',
    m_score INT DEFAULT 0 COMMENT 'M评分(1-5)',
    rfm_score INT DEFAULT 0 COMMENT 'RFM总分',
    user_group VARCHAR(50) COMMENT '用户分群标签',
    cluster_id INT COMMENT '聚类簇编号',
    total_views INT DEFAULT 0 COMMENT '总浏览次数',
    total_carts INT DEFAULT 0 COMMENT '总加购次数',
    total_favs INT DEFAULT 0 COMMENT '总收藏次数',
    total_buys INT DEFAULT 0 COMMENT '总购买次数',
    conversion_rate DECIMAL(5,4) DEFAULT 0 COMMENT '购买转化率',
    last_active_time DATETIME COMMENT '最后活跃时间',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    INDEX idx_user_id (user_id),
    INDEX idx_user_group (user_group),
    INDEX idx_cluster_id (cluster_id),
    INDEX idx_rfm_score (rfm_score)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户画像表';

-- ----------------------------------------
-- 4. 商品统计表
-- ----------------------------------------
DROP TABLE IF EXISTS product;
CREATE TABLE product (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键ID',
    item_id BIGINT NOT NULL UNIQUE COMMENT '商品ID',
    name VARCHAR(200) COMMENT '商品展示名称',
    brand VARCHAR(100) COMMENT '商品品牌',
    category_id BIGINT COMMENT '商品类目ID',
    category_name VARCHAR(100) COMMENT '商品类目编码/名称',
    price DECIMAL(10,2) DEFAULT NULL COMMENT '商品价格',
    view_count INT DEFAULT 0 COMMENT '浏览次数',
    cart_count INT DEFAULT 0 COMMENT '加购次数',
    fav_count INT DEFAULT 0 COMMENT '收藏次数',
    buy_count INT DEFAULT 0 COMMENT '购买次数',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    INDEX idx_item_id (item_id),
    INDEX idx_category_id (category_id),
    INDEX idx_buy_count (buy_count)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商品统计表';

-- ----------------------------------------
-- 5. 类目统计表
-- ----------------------------------------
DROP TABLE IF EXISTS category;
CREATE TABLE category (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键ID',
    category_id BIGINT NOT NULL UNIQUE COMMENT '类目ID',
    name VARCHAR(100) COMMENT '类目名称',
    view_count INT DEFAULT 0 COMMENT '浏览次数',
    cart_count INT DEFAULT 0 COMMENT '加购次数',
    fav_count INT DEFAULT 0 COMMENT '收藏次数',
    buy_count INT DEFAULT 0 COMMENT '购买次数',
    product_count INT DEFAULT 0 COMMENT '商品数量',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    INDEX idx_category_id (category_id),
    INDEX idx_buy_count (buy_count)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='类目统计表';

-- ----------------------------------------
-- 6. 分析报表缓存表
-- ----------------------------------------
DROP TABLE IF EXISTS analysis_cache;
CREATE TABLE analysis_cache (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键ID',
    cache_key VARCHAR(100) NOT NULL UNIQUE COMMENT '缓存键',
    cache_value LONGTEXT COMMENT '缓存值(JSON)',
    expire_time DATETIME COMMENT '过期时间',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    INDEX idx_cache_key (cache_key),
    INDEX idx_expire_time (expire_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='分析报表缓存表';

-- ----------------------------------------
-- 7. 系统日志表
-- ----------------------------------------
DROP TABLE IF EXISTS sys_log;
CREATE TABLE sys_log (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键ID',
    user_id BIGINT COMMENT '操作用户ID',
    username VARCHAR(50) COMMENT '操作用户名',
    operation VARCHAR(100) COMMENT '操作描述',
    method VARCHAR(200) COMMENT '请求方法',
    params TEXT COMMENT '请求参数',
    ip VARCHAR(50) COMMENT 'IP地址',
    duration BIGINT COMMENT '执行时长(ms)',
    status TINYINT DEFAULT 1 COMMENT '状态: 1-成功, 0-失败',
    error_msg TEXT COMMENT '错误信息',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    INDEX idx_user_id (user_id),
    INDEX idx_create_time (create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='系统日志表';

-- 显示创建结果
SELECT '数据库初始化完成!' AS message;
SHOW TABLES;
