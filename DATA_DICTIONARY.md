# 电商用户消费行为分析系统 - 数据字典

本文档定义系统核心指标的计算口径、去重规则和时间窗口，可直接用于毕业论文附录。

---

## 一、事件类型定义

| 事件代码 | 事件名称 | 业务含义 | 数据来源 | 是否计入M值 |
|:---:|:---|:---|:---|:---:|
| `pv` | 浏览 | 用户浏览商品详情页 | 行为日志 | ❌ |
| `cart` | 加购 | 用户将商品加入购物车 | 行为日志 | ❌ |
| `fav` | 收藏 | 用户收藏商品 | 行为日志 | ❌ |
| `buy` | 购买 | 用户完成购买行为 | 行为日志 | ✅ |

> **说明**：本系统以行为日志构造交易记录，`buy` 事件视为有效订单。若接入真实订单系统，可使用支付成功状态替代。

---

## 二、去重规则

### 2.1 事件去重

| 规则项 | 说明 |
|:---|:---|
| 去重标识 | `event_id` = MD5(user_id + item_id + behavior_type + timestamp) |
| 去重方式 | 数据库唯一索引 `uk_event_id`，重复记录自动拒绝 |
| 适用场景 | 防止爬虫/导入数据重复执行导致的数据膨胀 |

### 2.2 用户去重（指标计算）

| 指标类型 | 去重维度 | 说明 |
|:---|:---|:---|
| 漏斗各步骤用户数 | `user_id` | 每个用户在统计周期内只计1次 |
| RFM-R（最近消费） | `user_id` | 取用户最后一次购买时间 |
| RFM-F（消费频率） | `user_id` + `buy事件` | 统计用户购买行为总次数 |
| RFM-M（消费金额） | `user_id` + `buy事件` | 汇总用户所有购买金额 |

---

## 三、RFM 指标定义

### 3.1 基础定义

| 指标 | 全称 | 计算公式 | 单位 |
|:---:|:---|:---|:---:|
| R | Recency（最近消费） | `当前日期 - MAX(purchase_date)` | 天 |
| F | Frequency（消费频率） | `COUNT(buy事件)` | 次 |
| M | Monetary（消费金额） | `Σ(unit_price × qty)` 其中 behavior_type='buy' | 元 |

### 3.2 评分规则（五分制）

采用**五分位法**对 R/F/M 分别评分：

| 分位 | R评分 | F评分 | M评分 |
|:---:|:---:|:---:|:---:|
| 前20%（最优） | 5 | 5 | 5 |
| 20%-40% | 4 | 4 | 4 |
| 40%-60% | 3 | 3 | 3 |
| 60%-80% | 2 | 2 | 2 |
| 后20%（最差） | 1 | 1 | 1 |

> **注意**：R 值越小越好（最近消费），F/M 值越大越好。

### 3.3 用户分群规则

| 分群名称 | R评分 | F评分 | M评分 | 营销策略建议 |
|:---|:---:|:---:|:---:|:---|
| 高价值用户 | ≥4 | ≥4 | ≥4 | VIP服务、专属优惠 |
| 重点保持用户 | ≥4 | ≥4 | <4 | 提升客单价 |
| 重点发展用户 | ≥4 | <4 | ≥4 | 提升购买频次 |
| 潜力用户 | ≥4 | <4 | <4 | 培养消费习惯 |
| 需唤醒用户 | <4 | ≥4 | ≥4 | 召回营销 |
| 一般维持用户 | <4 | ≥4 | <4 | 常规运营 |
| 沉睡用户 | <4 | <4 | - | 低成本挽回 |
| 未转化用户 | - | 0 | 0 | 首购激励 |

---

## 四、转化漏斗定义

### 4.1 漏斗结构

```
浏览(pv) → 加购(cart) → 购买(buy)
```

### 4.2 序列约束规则

| 约束项 | 规则 | SQL实现 |
|:---|:---|:---|
| **用户子集约束** | 下一步用户必须属于上一步用户集合 | `user_id IN (SELECT ... FROM 上一步)` |
| **去重口径** | 按 `user_id` 去重，每用户只计1次 | `COUNT(DISTINCT user_id)` |
| **时间窗口** | 统计周期内全量数据 | 可扩展为时间范围过滤 |

### 4.3 转化率计算

| 转化环节 | 计算公式 |
|:---|:---|
| 浏览→加购转化率 | 加购用户数 / 浏览用户数 × 100% |
| 加购→购买转化率 | 购买用户数 / 加购用户数 × 100% |
| 整体转化率 | 购买用户数 / 浏览用户数 × 100% |

> **说明**：由于采用用户子集约束，转化率保证 ≤100%。

---

## 五、时间窗口定义

| 场景 | 窗口类型 | 说明 |
|:---|:---|:---|
| RFM计算 | 全量数据 | 使用历史全量行为数据 |
| 漏斗分析 | 全量数据 | 可扩展为自定义时间范围 |
| 趋势分析 | 按天聚合 | behavior_date_time 按日期分组 |
| 时段分析 | 按小时聚合 | 提取 behavior_time 的小时部分 |

---

## 六、数据质量规则

### 6.1 校验规则

| 规则编号 | 校验项 | 规则 | 处理方式 |
|:---:|:---|:---|:---|
| DQ-01 | 事件去重 | event_id 唯一 | 拒绝重复插入 |
| DQ-02 | 用户ID非空 | user_id IS NOT NULL | 过滤无效行 |
| DQ-03 | 商品ID非空 | item_id IS NOT NULL | 过滤无效行 |
| DQ-04 | 价格有效性 | unit_price >= 0 | 负值置0 |
| DQ-05 | 数量有效性 | qty >= 1 | 无效值置1 |
| DQ-06 | 行为类型有效 | behavior_type IN ('pv','cart','fav','buy') | 过滤无效行 |

### 6.2 校验SQL（可用于论文数据质量说明）

```sql
-- 统计各类数据质量问题
SELECT 
  SUM(CASE WHEN user_id IS NULL THEN 1 ELSE 0 END) AS null_user_count,
  SUM(CASE WHEN item_id IS NULL THEN 1 ELSE 0 END) AS null_item_count,
  SUM(CASE WHEN unit_price < 0 THEN 1 ELSE 0 END) AS invalid_price_count,
  SUM(CASE WHEN qty < 1 THEN 1 ELSE 0 END) AS invalid_qty_count,
  SUM(CASE WHEN behavior_type NOT IN ('pv','cart','fav','buy') THEN 1 ELSE 0 END) AS invalid_type_count,
  COUNT(*) AS total_records
FROM user_behavior;
```

---

## 七、索引设计说明

| 索引名 | 字段组合 | 优化场景 |
|:---|:---|:---|
| uk_event_id | event_id | 事件去重 |
| idx_user_behavior_composite | user_id, behavior_type, behavior_time | RFM计算 |
| idx_type_time_item | behavior_type, behavior_time, item_id | 热门商品/趋势 |
| idx_type_time_user | behavior_type, behavior_time, user_id | 用户行为时序 |

---

## 八、金额字段语义

| 字段 | 含义 | 来源 | 说明 |
|:---|:---|:---|:---|
| `unit_price` | 商品单价 | 爬虫获取 | 仅购买事件有值，其他事件为0 |
| `qty` | 购买数量 | 默认值1 | 可扩展支持多件购买场景 |
| `monetary` | 累计消费金额 | 聚合计算 | = Σ(unit_price × qty) |

> **论文说明**：本系统以行为日志与商品价格构造交易金额近似值用于RFM-M值计算。若接入真实订单系统（含 order_id、pay_status、pay_time），可直接使用支付成功金额替换，进一步提高数据精度。

---

## 九、K-Means 聚类可解释性

### 9.1 聚类中心特征（示例）

| 簇ID | 用户数 | 平均R | 平均F | 平均M | 平均RFM | 推荐标签 |
|:---:|:---:|:---:|:---:|:---:|:---:|:---|
| -1 | 434 | 4.80 | 1.00 | 1.00 | 6.80 | 未转化用户 |
| 0 | 2 | 5.00 | 3.50 | 3.50 | 12.00 | 活跃用户 |
| 1 | 5 | 5.00 | 3.20 | 3.40 | 11.60 | 稳定用户 |
| 2 | 2 | 5.00 | 3.50 | 4.50 | 13.00 | 高价值用户 |
| 3 | 3 | 1.00 | 2.67 | 2.67 | 6.33 | 流失风险 |
| 4 | 3 | 1.00 | 4.00 | 4.67 | 9.67 | 需唤醒用户 |

### 9.2 聚类标签命名规则

```sql
-- 聚类可解释性查询（可用于论文）
SELECT 
  cluster_id,
  COUNT(*) as user_count,
  ROUND(AVG(r_score), 2) as avg_r,
  ROUND(AVG(f_score), 2) as avg_f,
  ROUND(AVG(m_score), 2) as avg_m,
  ROUND(AVG(rfm_score), 2) as avg_rfm,
  CASE 
    WHEN cluster_id = -1 THEN '未转化用户'
    WHEN AVG(r_score) >= 4 AND AVG(f_score) >= 4 AND AVG(m_score) >= 4 THEN '高价值用户'
    WHEN AVG(r_score) >= 4 AND AVG(f_score) >= 3 THEN '活跃用户'
    WHEN AVG(r_score) < 3 AND AVG(f_score) >= 3 THEN '需唤醒用户'
    WHEN AVG(r_score) < 3 AND AVG(f_score) < 3 THEN '流失风险'
    ELSE '一般用户'
  END as cluster_label
FROM user_profile
GROUP BY cluster_id
ORDER BY cluster_id;
```

---

## 十、数据质量校验结果

### 10.1 校验执行结果

| 校验项 | 异常数量 | 总记录数 | 通过率 |
|:---|:---:|:---:|:---:|
| 用户ID为空 | 0 | 100,354 | 100% |
| 商品ID为空 | 0 | 100,354 | 100% |
| 价格无效 (< 0) | 0 | 100,354 | 100% |
| 数量无效 (< 1) | 0 | 100,354 | 100% |
| 行为类型无效 | 0 | 100,354 | 100% |

### 10.2 去重效果

| 指标 | 说明 |
|:---|:---|
| 唯一索引 | `uk_event_id` 基于 MD5(user_id + item_id + type + time) |
| 重复拒绝 | 数据库层面自动拒绝重复event_id的记录 |
| 数据一致性 | 保证同一用户对同一商品同一时间的同一行为只记录一次 |

