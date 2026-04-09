package com.ecommerce.analysis.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ecommerce.analysis.entity.ProductPublicMetric;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

/**
 * 商品公网满意度指标 Mapper。
 */
@Mapper
public interface ProductPublicMetricMapper extends BaseMapper<ProductPublicMetric> {

    @Insert("INSERT INTO product_public_metric " +
            "(item_id, source_platform, source_product_id, source_url, positive_rate, review_count, shop_score, rating_text, crawl_status, raw_payload, crawled_at) " +
            "VALUES (#{itemId}, #{sourcePlatform}, #{sourceProductId}, #{sourceUrl}, #{positiveRate}, #{reviewCount}, #{shopScore}, #{ratingText}, #{crawlStatus}, #{rawPayload}, #{crawledAt}) " +
            "ON DUPLICATE KEY UPDATE " +
            "source_product_id = VALUES(source_product_id), " +
            "source_url = VALUES(source_url), " +
            "positive_rate = VALUES(positive_rate), " +
            "review_count = VALUES(review_count), " +
            "shop_score = VALUES(shop_score), " +
            "rating_text = VALUES(rating_text), " +
            "crawl_status = VALUES(crawl_status), " +
            "raw_payload = VALUES(raw_payload), " +
            "crawled_at = VALUES(crawled_at)")
    int upsertLatest(ProductPublicMetric metric);

    @Select("SELECT ub.item_id, " +
            "COALESCE(NULLIF(MAX(p.name), ''), CONCAT('商品#', ub.item_id)) AS product_name, " +
            "MAX(p.brand) AS brand, " +
            "MAX(p.category_name) AS category_name, " +
            "MAX(p.price) AS price, " +
            "SUM(CASE WHEN ub.behavior_type = 'pv' THEN 1 ELSE 0 END) AS view_count, " +
            "SUM(CASE WHEN ub.behavior_type = 'buy' THEN 1 ELSE 0 END) AS buy_count, " +
            "MAX(pm.source_platform) AS source_platform, " +
            "MAX(pm.source_url) AS source_url, " +
            "MAX(pm.positive_rate) AS positive_rate, " +
            "MAX(pm.review_count) AS review_count, " +
            "MAX(pm.shop_score) AS shop_score, " +
            "MAX(pm.crawl_status) AS crawl_status, " +
            "MAX(pm.crawled_at) AS crawled_at, " +
            "CASE WHEN MAX(ppm.id) IS NULL THEN 0 ELSE 1 END AS has_mapping, " +
            "CASE WHEN MAX(pm.id) IS NULL THEN 0 ELSE 1 END AS has_public_metric " +
            "FROM user_behavior ub " +
            "LEFT JOIN product p ON ub.item_id = p.item_id " +
            "LEFT JOIN product_public_mapping ppm ON ub.item_id = ppm.item_id AND ppm.source_platform = #{sourcePlatform} " +
            "LEFT JOIN product_public_metric pm ON ub.item_id = pm.item_id AND pm.source_platform = #{sourcePlatform} " +
            "GROUP BY ub.item_id " +
            "ORDER BY buy_count DESC, view_count DESC " +
            "LIMIT #{limit}")
    List<Map<String, Object>> getHotProductsWithPublicMetrics(@Param("limit") int limit,
            @Param("sourcePlatform") String sourcePlatform);

    @Delete("DELETE FROM product_public_metric WHERE item_id = #{itemId} AND source_platform = #{sourcePlatform}")
    int deleteByItemAndPlatform(@Param("itemId") Long itemId, @Param("sourcePlatform") String sourcePlatform);
}
