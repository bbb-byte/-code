# 电商用户消费行为分析系统 - ER图

本文档包含系统各模块的 ER 图（实体关系图），使用 Mermaid 语法绘制。

---

## 一、系统整体 ER 图

```mermaid
erDiagram
    sys_user ||--o{ sys_log : "产生日志"
    user_behavior }o--|| user_profile : "聚合生成"
    user_behavior }o--|| product : "统计来源"
    user_behavior }o--|| category : "统计来源"
    product }o--|| category : "所属类目"

    sys_user {
        bigint id PK
        varchar username UK
        varchar password
        varchar role
        tinyint status
    }

    sys_log {
        bigint id PK
        bigint user_id FK
        varchar operation
        bigint duration
    }

    user_behavior {
        bigint id PK
        varchar event_id UK
        bigint user_id
        bigint item_id
        bigint category_id
        varchar behavior_type
        decimal unit_price
        int qty
        datetime behavior_date_time
    }

    user_profile {
        bigint id PK
        bigint user_id UK
        decimal monetary
        int rfm_score
        varchar user_group
        int cluster_id
    }

    product {
        bigint id PK
        bigint item_id UK
        bigint category_id FK
        int view_count
        int buy_count
    }

    category {
        bigint id PK
        bigint category_id UK
        varchar name
        int product_count
    }

    analysis_cache {
        bigint id PK
        varchar cache_key UK
        longtext cache_value
        datetime expire_time
    }
```

---

## 二、用户行为与画像模块

```mermaid
erDiagram
    user_behavior }o--|| user_profile : "聚合计算"

    user_behavior {
        bigint id PK
        varchar event_id UK "事件唯一ID(去重)"
        bigint user_id "用户ID"
        bigint item_id "商品ID"
        bigint category_id "商品类目ID"
        varchar behavior_type "行为类型(pv/buy/cart/fav)"
        bigint behavior_time "行为时间戳"
        datetime behavior_date_time "行为日期时间"
        decimal unit_price "商品单价(仅buy有值)"
        int qty "购买数量(默认1)"
        datetime create_time "创建时间"
    }

    user_profile {
        bigint id PK
        bigint user_id UK "用户ID"
        int recency "R-最近消费距今天数"
        int frequency "F-消费频率"
        decimal monetary "M-消费金额(Σunit_price*qty)"
        int r_score "R评分(1-5)"
        int f_score "F评分(1-5)"
        int m_score "M评分(1-5)"
        int rfm_score "RFM总分"
        varchar user_group "用户分群标签"
        int cluster_id "K-Means聚类簇ID(-1=未转化)"
        int total_views "总浏览次数"
        int total_carts "总加购次数"
        int total_favs "总收藏次数"
        int total_buys "总购买次数"
        decimal conversion_rate "购买转化率"
    }
```

---

## 三、商品与类目统计模块

```mermaid
erDiagram
    category ||--o{ product : "包含"
    user_behavior }o--|| product : "行为统计"
    user_behavior }o--|| category : "行为统计"

    product {
        bigint id PK
        bigint item_id UK
        varchar name
        bigint category_id FK
        varchar category_name
        int view_count
        int cart_count
        int fav_count
        int buy_count
    }

    category {
        bigint id PK
        bigint category_id UK
        varchar name
        int view_count
        int cart_count
        int fav_count
        int buy_count
        int product_count
    }
```

---

## 四、系统管理模块

```mermaid
erDiagram
    sys_user ||--o{ sys_log : "产生操作日志"

    sys_user {
        bigint id PK
        varchar username UK
        varchar password
        varchar email
        varchar phone
        varchar real_name
        varchar role
        tinyint status
        tinyint deleted
    }

    sys_log {
        bigint id PK
        bigint user_id FK
        varchar username
        varchar operation
        varchar method
        bigint duration
        tinyint status
    }
```

---

## 五、数据表汇总

| 序号 | 表名 | 说明 | 核心字段 |
|:---:|:---|:---|:---|
| 1 | `sys_user` | 系统用户表 | username, password, role |
| 2 | `sys_log` | 系统操作日志表 | user_id, operation, duration |
| 3 | `user_behavior` | 用户行为记录表 | event_id, user_id, item_id, **unit_price**, **qty** |
| 4 | `user_profile` | 用户画像表 | user_id, rfm_score, user_group, cluster_id |
| 5 | `product` | 商品统计表 | item_id, category_id, buy_count |
| 6 | `category` | 类目统计表 | category_id, product_count, buy_count |
| 7 | `analysis_cache` | 分析报表缓存表 | cache_key, cache_value, expire_time |

---

## 六、索引设计

| 表名 | 索引名 | 索引字段 | 说明 |
|:---|:---|:---|:---|
| `user_behavior` | uk_event_id | event_id | 事件去重唯一索引 |
| `user_behavior` | idx_user_id | user_id | 用户维度查询 |
| `user_behavior` | idx_item_id | item_id | 商品维度查询 |
| `user_behavior` | idx_behavior_type | behavior_type | 行为类型过滤 |
| `user_behavior` | idx_user_behavior_composite | user_id, behavior_type, behavior_time | 复合索引（RFM计算） |
| `user_behavior` | idx_type_time_item | behavior_type, behavior_time, item_id | 热门商品/趋势查询 |
| `user_behavior` | idx_type_time_user | behavior_type, behavior_time, user_id | 用户行为时序查询 |
| `user_profile` | idx_user_id | user_id | 用户ID查询 |
| `user_profile` | idx_cluster_id | cluster_id | 聚类分析 |

---

## 七、字段语义说明

### M值（Monetary）计算公式

```
M = Σ(unit_price × qty)  其中 behavior_type = 'buy'
```

| 字段 | 语义 | 来源 | 说明 |
|:---|:---|:---|:---|
| `unit_price` | 商品单价 | 爬虫获取 | 仅购买行为有值，其他为0 |
| `qty` | 购买数量 | 默认1 | 可扩展支持多件购买 |
| `monetary` | 消费金额 | 聚合计算 | = Σ(unit_price × qty) |

> **论文说明建议**：本系统以行为日志与商品价格构造交易金额近似值用于RFM-M；若接入真实订单系统，可直接使用支付成功金额替换，提高精度。
