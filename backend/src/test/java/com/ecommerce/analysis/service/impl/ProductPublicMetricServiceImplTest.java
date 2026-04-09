package com.ecommerce.analysis.service.impl;

import com.ecommerce.analysis.entity.ProductPublicMetric;
import com.ecommerce.analysis.mapper.ProductPublicMappingMapper;
import com.ecommerce.analysis.mapper.ProductPublicMetricMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.File;
import java.io.FileWriter;
import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProductPublicMetricServiceImplTest {

    @Test
    void shouldImportLatestMetricsFromCsv() throws Exception {
        ProductPublicMetricMapper mapper = mock(ProductPublicMetricMapper.class);
        ProductPublicMappingMapper mappingMapper = mock(ProductPublicMappingMapper.class);
        when(mapper.upsertLatest(org.mockito.ArgumentMatchers.any(ProductPublicMetric.class))).thenReturn(1);
        when(mappingMapper.countByItemAndPlatform(44600062L, "jd")).thenReturn(1);

        ProductPublicMetricServiceImpl service = new ProductPublicMetricServiceImpl();
        ReflectionTestUtils.setField(service, "productPublicMetricMapper", mapper);
        ReflectionTestUtils.setField(service, "productPublicMappingMapper", mappingMapper);

        File tempFile = File.createTempFile("product-public-metric", ".csv");
        try (FileWriter writer = new FileWriter(tempFile)) {
            writer.write("item_id,source_platform,source_product_id,source_url,positive_rate,review_count,shop_score,rating_text,crawl_status,raw_payload,crawled_at\n");
            writer.write("44600062,jd,100001,https://item.jd.com/100001.html,97.2700,1280,4.80,好评率 97%,success,\"{\"\"commentCount\"\":1280,\"\"goodRateShow\"\":97.27}\",2026-04-09T04:00:00Z\n");
        }

        int imported = service.importLatestMetricsFromCsv(tempFile.getAbsolutePath());

        assertEquals(1, imported);

        ArgumentCaptor<ProductPublicMetric> captor = ArgumentCaptor.forClass(ProductPublicMetric.class);
        verify(mapper, times(1)).upsertLatest(captor.capture());

        ProductPublicMetric metric = captor.getValue();
        assertEquals(Long.valueOf(44600062L), metric.getItemId());
        assertEquals("jd", metric.getSourcePlatform());
        assertEquals("100001", metric.getSourceProductId());
        assertEquals("https://item.jd.com/100001.html", metric.getSourceUrl());
        assertEquals(new BigDecimal("97.2700"), metric.getPositiveRate());
        assertEquals(Long.valueOf(1280L), metric.getReviewCount());
        assertEquals(new BigDecimal("4.80"), metric.getShopScore());
        assertNotNull(metric.getRatingText());
        assertTrue(metric.getRatingText().contains("97"));
        assertEquals("success", metric.getCrawlStatus());
        assertTrue(metric.getRawPayload().contains("goodRateShow"));
    }
}
