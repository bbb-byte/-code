# Product Public Mapping Scoring Guide

本说明用于把内部 `item_id` 半自动映射到公网商品页，目标是减少人工工作量，同时控制误配风险。

## 适用边界

- `archive` 仍然是正式用户行为主数据源。
- 本流程只为 `product_public_mapping` 生成候选映射，不自动生成用户行为。
- 当前仓库内部商品名很多是占位名，如 `brand #item_id` 或 `商品#item_id`，不能把 `product.name` 当成强检索键。

## Kaggle / archive 原始文件怎么用

工作台的“商品快照”输入本质上需要这 4 类字段：

- `item_id`，或兼容别名 `product_id/goods_id`
- `brand`
- `category_name`，或兼容别名 `category_code`
- `price`，或兼容别名 `unit_price/amount`

如果 Kaggle 原始 CSV 本身就包含这些列，技术上可以直接把原始文件路径填到“商品快照”里。

但更推荐先把事件级原始文件整理成商品级快照，再送入召回/评分流程。原因是原始行为日志里同一个商品会重复很多行，直接使用虽然能跑，但商品品牌、类目、价格可能取到覆盖后的脏值。

推荐脚本：

```bash
python3 crawler/build_internal_products_snapshot.py \
  --input archive/2019-Oct.csv \
  --output crawler/output/internal_products.from-archive.csv
```

然后在工作台里把“商品快照”路径改成：

```text
crawler/output/internal_products.from-archive.csv
```

## 推荐流程

1. 从内部商品快照提取候选检索条件：
   - `item_id`
   - `brand`
   - `category_name` / `category_code`
   - `price`
2. 用 `brand + category + price` 在目标平台召回候选商品。
3. 对每个候选商品计算匹配分数。
4. 按阈值分层：
   - `>= 0.90`：高置信度，允许快速人工确认后入库
   - `0.75 - 0.89`：中置信度，必须人工复核
   - `< 0.75`：拒绝，不入映射表
5. 复核通过后写入 `product_public_mapping`。

可配合脚本：

```bash
python3 crawler/mapping_candidate_recall.py \
  --products crawler/mappings/internal_products.sample.csv \
  --fixture-dir crawler/fixtures \
  --output crawler/output/recalled_candidates.csv \
  --top-k 5

python3 crawler/mapping_scorer.py \
  --products crawler/mappings/internal_products.sample.csv \
  --candidates crawler/output/recalled_candidates.csv \
  --output crawler/output/recalled_candidate_scores.csv
```

第一步先召回公网候选商品，第二步再打分并给出复核建议。

## 打分字段

总分建议为 `1.00`，按如下加权：

| 维度 | 权重 | 判定规则 |
| --- | --- | --- |
| 品牌一致 | 0.35 | 品牌完全一致给满分；同义写法或大小写差异给 0.25；不一致给 0 |
| 类目一致 | 0.20 | 主类目一致给满分；相邻类目给 0.10；明显不一致给 0 |
| 价格接近 | 0.20 | 相对误差 <= 10% 给满分；<= 20% 给 0.10；超过 20% 给 0 |
| 标题关键词 | 0.15 | 标题包含品牌与核心品类词给满分；仅命中其一给 0.08；都不命中给 0 |
| 页面证据完整 | 0.10 | 页面能看到品牌、价格、品类三项中的两项及以上给满分；只有一项给 0.05；都没有给 0 |

建议分数公式：

```text
score = brand_score + category_score + price_score + title_score + evidence_score
```

## 规则细化

### 品牌一致

- 完全相同：`0.35`
- 忽略大小写、空格、连字符后相同：`0.30`
- 常见同义写法相同，例如中英文品牌别名：`0.25`
- 不一致：`0`

### 类目一致

- 内部 `category_name/category_code` 与商品页主类目一致：`0.20`
- 只匹配到上级类目：`0.10`
- 完全不一致：`0`

### 价格接近

相对误差定义：

```text
price_gap_ratio = abs(public_price - internal_price) / internal_price
```

- `<= 0.10`：`0.20`
- `<= 0.20`：`0.10`
- `> 0.20`：`0`
- 内部价格缺失：该项记 `0.05`，但整体置信度不得超过 `0.85`

### 标题关键词

可从内部字段生成关键词：

- `brand`
- `category_name` 的核心词
- 若后续补充了型号、规格、容量，可加入关键词列表

打分建议：

- 品牌词 + 核心品类词都命中：`0.15`
- 只命中其中一类：`0.08`
- 都不命中：`0`

### 页面证据完整

用于评估候选页是否足够可核验：

- 页面可见品牌、价格、类目中的至少两项：`0.10`
- 仅可见一项：`0.05`
- 信息过少：`0`

## 入库策略

### 高置信度

- 分数 `>= 0.90`
- 仍建议人工快速确认一次
- `mapping_confidence` 可写 `0.95` 或 `reviewed`

### 中置信度

- 分数 `0.75 - 0.89`
- 必须人工复核
- `verification_note` 必须说明通过依据
- `mapping_confidence` 建议写具体分数，如 `0.82`

### 低置信度

- 分数 `< 0.75`
- 不写入 `product_public_mapping`
- 可保留在候选结果表中，等待后续补充更强特征

## 建议保存的核验信息

当前表结构已经支持以下字段，建议都用起来：

- `item_id`
- `source_platform`
- `source_product_id`
- `source_url`
- `verified_title`
- `mapping_confidence`
- `verification_note`
- `evidence_note`
- `verified_at`

推荐填写规范：

- `verified_title`：保存人工确认时看到的公网商品标题
- `verification_note`：写明品牌/类目/价格为何匹配
- `evidence_note`：补充截图日期、页面特征、异常说明
- `verified_at`：保存核验时间

## 推荐 CSV 模板

```csv
item_id,source_platform,source_product_id,source_url,verified_title,mapping_confidence,verification_note,evidence_note,verified_at
44600062,jd,100012043978,https://item.jd.com/100012043978.html,SHISEIDO XXX,0.93,品牌一致且价格误差小于10%,2026-04-09人工复核通过,2026-04-09T12:00:00
```

## 论文表述建议

可以使用下面这段方法说明：

“本研究采用候选召回与人工复核相结合的商品映射策略。首先依据内部商品的品牌、类目和价格信息在目标电商平台召回候选商品，再从品牌一致性、类目一致性、价格接近度、标题关键词匹配和页面证据完整性等维度计算匹配分数。仅当候选商品通过人工复核后，才建立内部 `item_id` 与公网商品页面的映射关系，并据此采集公开评价指标。” 

## 实施建议

- 先只覆盖高购买量或高金额商品，控制样本规模。
- 先做单平台 `jd`，不要同时混用多平台。
- 没有强标识时，不要把高分候选直接当最终结果。
- 如果后续补到真实标题、型号、规格、条码，可再提升自动化比例。
