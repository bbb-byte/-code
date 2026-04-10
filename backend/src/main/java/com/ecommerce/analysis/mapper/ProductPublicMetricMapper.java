package com.ecommerce.analysis.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ecommerce.analysis.entity.ProductPublicMetric;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

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

    @Select("<script>" +
            "SELECT hot.item_id, " +
            "COALESCE(NULLIF(p.name, ''), CONCAT('product#', hot.item_id)) AS product_name, " +
            "p.brand AS brand, " +
            "p.category_name AS category_name, " +
            "p.price AS price, " +
            "hot.view_count AS view_count, " +
            "hot.buy_count AS buy_count, " +
            "pm.source_platform AS source_platform, " +
            "pm.source_url AS source_url, " +
            "pm.positive_rate AS positive_rate, " +
            "pm.review_count AS review_count, " +
            "pm.shop_score AS shop_score, " +
            "pm.crawl_status AS crawl_status, " +
            "pm.crawled_at AS crawled_at, " +
            "CASE WHEN ppm.id IS NULL THEN 0 ELSE 1 END AS has_mapping, " +
            "CASE WHEN pm.id IS NULL THEN 0 ELSE 1 END AS has_public_metric " +
            "FROM (" +
            "  SELECT ub.item_id, " +
            "         SUM(CASE WHEN behavior_type = 'pv' THEN 1 ELSE 0 END) AS view_count, " +
            "         SUM(CASE WHEN behavior_type = 'buy' THEN 1 ELSE 0 END) AS buy_count " +
            "  FROM user_behavior ub " +
            "  LEFT JOIN product_public_metric pm ON ub.item_id = pm.item_id AND pm.source_platform = #{sourcePlatform} " +
            "  WHERE behavior_type IN ('pv', 'buy') " +
            "  <if test='onlyWithMetrics'>AND pm.id IS NOT NULL</if> " +
            "  GROUP BY ub.item_id " +
            "  ORDER BY buy_count DESC, view_count DESC " +
            "  LIMIT #{offset}, #{limit}" +
            ") hot " +
            "LEFT JOIN product p ON hot.item_id = p.item_id " +
            "LEFT JOIN product_public_mapping ppm ON hot.item_id = ppm.item_id AND ppm.source_platform = #{sourcePlatform} " +
            "LEFT JOIN product_public_metric pm ON hot.item_id = pm.item_id AND pm.source_platform = #{sourcePlatform} " +
            "ORDER BY hot.buy_count DESC, hot.view_count DESC" +
            "</script>")
    List<Map<String, Object>> getHotProductsWithPublicMetrics(@Param("offset") int offset,
            @Param("limit") int limit,
            @Param("onlyWithMetrics") boolean onlyWithMetrics,
            @Param("sourcePlatform") String sourcePlatform);

    @Select("<script>" +
            "SELECT COUNT(DISTINCT ub.item_id) " +
            "FROM user_behavior ub " +
            "<if test='onlyWithMetrics'> " +
            "JOIN product_public_metric pm ON ub.item_id = pm.item_id AND pm.source_platform = #{sourcePlatform} " +
            "</if> " +
            "WHERE ub.behavior_type IN ('pv', 'buy')" +
            "</script>")
    long countHotProductsWithPublicMetrics(@Param("onlyWithMetrics") boolean onlyWithMetrics,
            @Param("sourcePlatform") String sourcePlatform);

    @Delete("DELETE FROM product_public_metric WHERE item_id = #{itemId} AND source_platform = #{sourcePlatform}")
    int deleteByItemAndPlatform(@Param("itemId") Long itemId, @Param("sourcePlatform") String sourcePlatform);
}
