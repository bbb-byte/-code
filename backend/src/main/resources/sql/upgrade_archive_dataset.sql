USE ecommerce_analysis;

ALTER TABLE product
    ADD COLUMN IF NOT EXISTS brand VARCHAR(100) COMMENT '商品品牌' AFTER name,
    ADD COLUMN IF NOT EXISTS price DECIMAL(10,2) DEFAULT NULL COMMENT '商品价格' AFTER category_name;
