# JD Public Satisfaction Crawler

该目录下的爬虫只服务一个 v1 source family：`JD public product satisfaction data`。

边界：
- `archive` 仍是正式用户行为与分群主数据源。
- 本爬虫只补充京东商品公开评价摘要，不生成 `buy/fav/cart/pv`，也不补商品价格。
- 输出的是商品侧公开满意度指标，用于解释 RFM 和商品分析结果。

当前输出字段：
- `positive_rate`
- `review_count`
- `shop_score`
- `rating_text`
- `crawl_status`
- `crawl_message`
- `crawled_at`

运行方式：

```bash
python3 crawler/ecommerce_crawler.py \
  --mapping crawler/mappings/product_public_mapping.jd.sample.csv \
  --output-json crawler/output/jd_product_public_metrics.json \
  --output-csv crawler/output/jd_product_public_metrics.csv
```

映射前置条件：
- 不能直接用系统里的 `product.name` 自动搜索公网商品。
- 必须先人工维护 `item_id -> source_product_id/source_url` 的映射文件。
- v1 只允许 `jd`，不支持多平台混用。
- `mapping_confidence` 支持人工标签（`manual/reviewed/high`）或 `0-1` 数值。

## 后端接口示例

抓取已映射商品的公网满意度指标：

```bash
curl -X POST 'http://localhost:8080/api/data/crawl?mappingPath=crawler/mappings/product_public_mapping.jd.sample.csv&outputDir=crawler/output&fixtureDir=crawler/fixtures' \
  -H 'Authorization: Bearer <token>'
```

如果你已经有商品快照 CSV，直接做候选召回：

```bash
curl -X POST 'http://localhost:8080/api/data/public-mapping/recall?productPath=crawler/mappings/internal_products.sample.csv&outputPath=crawler/output/recalled_candidates.csv&topK=5' \
  -H 'Authorization: Bearer <token>'
```

如果你只有 Kaggle/archive 原始行为文件，让后端先自动生成商品快照再继续召回：

```bash
curl -X POST 'http://localhost:8080/api/data/public-mapping/recall?sourceDataPath=archive/2019-Oct.csv&generatedProductPath=crawler/output/internal_products.auto.csv&productPath=crawler/mappings/internal_products.sample.csv&outputPath=crawler/output/recalled_candidates.csv&topK=5' \
  -H 'Authorization: Bearer <token>'
```

说明：
- 传了 `sourceDataPath` 时，后端会先执行 `build_internal_products_snapshot.py`
- 不传 `sourceDataPath` 时，后端直接使用 `productPath`
- `productPath` 现在既可以是 sample 文件，也可以是你自己生成的商品快照文件
- `fixtureDir` 默认留空，只有离线调试/测试解析逻辑时才传 `crawler/fixtures`
