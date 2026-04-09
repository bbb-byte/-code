package com.ecommerce.analysis.sql;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertTrue;

class PublicMetricSchemaTest {

    @Test
    void initSqlShouldCreateDedicatedMappingAndMetricTables() throws Exception {
        String content = new String(Files.readAllBytes(Paths.get(
                "src/main/resources/sql/init.sql")), StandardCharsets.UTF_8);

        assertTrue(content.contains("CREATE TABLE product_public_mapping"));
        assertTrue(content.contains("CREATE TABLE product_public_metric"));
        assertTrue(content.contains("source_platform VARCHAR(32) NOT NULL COMMENT '公网来源平台(jd)'"));
        assertTrue(content.contains("positive_rate"));
        assertTrue(content.contains("review_count"));
        assertTrue(content.contains("shop_score"));
    }

    @Test
    void upgradeSqlShouldAddDedicatedPublicMetricTables() throws Exception {
        String content = new String(Files.readAllBytes(Paths.get(
                "src/main/resources/sql/upgrade_archive_dataset.sql")), StandardCharsets.UTF_8);

        assertTrue(content.contains("CREATE TABLE IF NOT EXISTS product_public_mapping"));
        assertTrue(content.contains("CREATE TABLE IF NOT EXISTS product_public_metric"));
    }
}

