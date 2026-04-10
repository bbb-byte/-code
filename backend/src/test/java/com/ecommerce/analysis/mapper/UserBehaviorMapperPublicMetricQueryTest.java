package com.ecommerce.analysis.mapper;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertTrue;

class UserBehaviorMapperPublicMetricQueryTest {

    @Test
    void shouldExposeDedicatedQueryForDedicatedPublicMetricMapper() throws Exception {
        String content = new String(Files.readAllBytes(Paths.get(
                "src/main/java/com/ecommerce/analysis/mapper/ProductPublicMetricMapper.java")), StandardCharsets.UTF_8);

        assertTrue(content.contains("getHotProductsWithPublicMetrics"));
        assertTrue(content.contains("FROM user_behavior ub"));
        assertTrue(content.contains("LEFT JOIN product_public_metric pm ON ub.item_id = pm.item_id AND pm.source_platform = #{sourcePlatform}"));
        assertTrue(content.contains("<if test='onlyWithMetrics'>AND pm.id IS NOT NULL</if>"));
        assertTrue(content.contains("LEFT JOIN product_public_mapping ppm ON hot.item_id = ppm.item_id AND ppm.source_platform = #{sourcePlatform}"));
        assertTrue(content.contains("LEFT JOIN product_public_metric pm ON hot.item_id = pm.item_id AND pm.source_platform = #{sourcePlatform}"));
        assertTrue(content.contains("positive_rate"));
        assertTrue(content.contains("review_count"));
        assertTrue(content.contains("shop_score"));
    }
}
