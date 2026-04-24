package com.ecommerce.analysis.service.impl;

import com.ecommerce.analysis.entity.ProductPublicMapping;
import com.ecommerce.analysis.entity.ProductPublicMetric;
import com.ecommerce.analysis.mapper.ProductPublicMappingMapper;
import com.ecommerce.analysis.mapper.ProductPublicMetricMapper;
import com.ecommerce.analysis.vo.PublicMappingScorePreviewVO;
import com.ecommerce.analysis.vo.PublicMappingScoreRowVO;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.File;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProductPublicMetricServiceImplTest {

    @Test
    void shouldImportLatestMetricsFromCsv() throws Exception {
        ProductPublicMetricMapper mapper = mock(ProductPublicMetricMapper.class);
        ProductPublicMappingMapper mappingMapper = mock(ProductPublicMappingMapper.class);
        AnalysisCacheService analysisCacheService = mock(AnalysisCacheService.class);
        when(mapper.upsertLatest(any(ProductPublicMetric.class))).thenReturn(1);
        when(mappingMapper.countByItemAndPlatform(44600062L, "jd")).thenReturn(1);

        ProductPublicMetricServiceImpl service = new ProductPublicMetricServiceImpl();
        ReflectionTestUtils.setField(service, "productPublicMetricMapper", mapper);
        ReflectionTestUtils.setField(service, "productPublicMappingMapper", mappingMapper);
        ReflectionTestUtils.setField(service, "analysisCacheService", analysisCacheService);

        File tempFile = File.createTempFile("product-public-metric", ".csv");
        try (Writer writer = new OutputStreamWriter(Files.newOutputStream(tempFile.toPath()), StandardCharsets.UTF_8)) {
            writer.write("item_id,source_platform,source_product_id,source_url,positive_rate,review_count,shop_score,rating_text,crawl_status,raw_payload,crawled_at\n");
            writer.write("44600062,jd,100001,https://item.jd.com/100001.html,97.2700,1280,4.80,rating 97%,success,\"{\"\"commentCount\"\":1280,\"\"goodRateShow\"\":97.27}\",2026-04-09T04:00:00Z\n");
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

    @Test
    void shouldPreviewScoreRowsFromCsv() throws Exception {
        ProductPublicMetricServiceImpl service = new ProductPublicMetricServiceImpl();

        File tempFile = File.createTempFile("product-public-score", ".csv");
        try (Writer writer = new OutputStreamWriter(Files.newOutputStream(tempFile.toPath()), StandardCharsets.UTF_8)) {
            writer.write("item_id,brand,category_name,internal_price,source_platform,source_product_id,source_url,public_title,public_brand,public_category,public_price,brand_score,category_score,price_score,title_score,evidence_score,total_score,recommended_action,score_reason\n");
            writer.write("44600062,shiseido,beauty/skincare,35.79,jd,100012043978,https://item.jd.com/100012043978.html,Shiseido skincare essence,shiseido,beauty/skincare,36.50,0.35,0.20,0.20,0.15,0.10,1.00,fast_review,\"brand,category,price,title,evidence\"\n");
        }

        PublicMappingScorePreviewVO preview = service.previewScoreRows(tempFile.getAbsolutePath(), 1, 50);
        List<PublicMappingScoreRowVO> rows = preview.getRows();

        assertEquals(1, rows.size());
        assertEquals(1L, preview.getTotal());
        assertEquals(1, preview.getPage());
        assertEquals(50, preview.getPageSize());
        PublicMappingScoreRowVO row = rows.get(0);
        assertEquals(Long.valueOf(44600062L), row.getItemId());
        assertEquals("jd", row.getSourcePlatform());
        assertEquals(new BigDecimal("1.00"), row.getTotalScore());
        assertEquals("fast_review", row.getRecommendedAction());
    }

    @Test
    void shouldConfirmMappingsIntoMappingTable() {
        ProductPublicMappingMapper mappingMapper = mock(ProductPublicMappingMapper.class);
        ProductPublicMetricMapper metricMapper = mock(ProductPublicMetricMapper.class);
        AnalysisCacheService analysisCacheService = mock(AnalysisCacheService.class);
        ProductPublicMetricServiceImpl service = new ProductPublicMetricServiceImpl();
        ReflectionTestUtils.setField(service, "productPublicMappingMapper", mappingMapper);
        ReflectionTestUtils.setField(service, "productPublicMetricMapper", metricMapper);
        ReflectionTestUtils.setField(service, "analysisCacheService", analysisCacheService);

        PublicMappingScoreRowVO row = new PublicMappingScoreRowVO();
        row.setItemId(44600062L);
        row.setSourcePlatform("jd");
        row.setSourceProductId("100012043978");
        row.setSourceUrl("https://item.jd.com/100012043978.html");
        row.setPublicTitle("Shiseido skincare essence");
        row.setPublicBrand("shiseido");
        row.setPublicCategory("beauty/skincare");
        row.setTotalScore(new BigDecimal("1.00"));
        row.setScoreReason("brand,category,price,title,evidence");
        row.setVerifiedTitle("Shiseido custom title");
        row.setMappingConfidence(new BigDecimal("0.91"));
        row.setVerificationNote("manual review");
        row.setEvidenceNote("title and price checked");

        when(mappingMapper.selectBySourcePlatformAndProductId("jd", "100012043978")).thenReturn(null);

        int confirmed = service.confirmMappings(Collections.singletonList(row));

        assertEquals(1, confirmed);

        ArgumentCaptor<ProductPublicMapping> captor = ArgumentCaptor.forClass(ProductPublicMapping.class);
        verify(mappingMapper, times(1)).upsert(captor.capture());

        ProductPublicMapping mapping = captor.getValue();
        assertEquals(Long.valueOf(44600062L), mapping.getItemId());
        assertEquals("jd", mapping.getSourcePlatform());
        assertEquals("100012043978", mapping.getSourceProductId());
        assertEquals("Shiseido custom title", mapping.getVerifiedTitle());
        assertEquals(new BigDecimal("0.91"), mapping.getMappingConfidence());
        assertEquals("manual review", mapping.getVerificationNote());
        assertEquals("title and price checked", mapping.getEvidenceNote());
        assertNotNull(mapping.getVerifiedAt());
        verify(metricMapper, times(0)).deleteByItemAndPlatform(any(), any());
    }

    @Test
    void shouldImportVerifiedTitleFromMappingsCsv() throws Exception {
        ProductPublicMappingMapper mappingMapper = mock(ProductPublicMappingMapper.class);
        AnalysisCacheService analysisCacheService = mock(AnalysisCacheService.class);
        ProductPublicMetricServiceImpl service = new ProductPublicMetricServiceImpl();
        ReflectionTestUtils.setField(service, "productPublicMappingMapper", mappingMapper);
        ReflectionTestUtils.setField(service, "analysisCacheService", analysisCacheService);

        File tempFile = File.createTempFile("product-public-mapping", ".csv");
        try (Writer writer = new OutputStreamWriter(Files.newOutputStream(tempFile.toPath()), StandardCharsets.UTF_8)) {
            writer.write("item_id,source_platform,source_product_id,source_url,verified_title,mapping_confidence,verification_note,evidence_note,verified_at\n");
            writer.write("44600062,jd,100012043978,https://item.jd.com/100012043978.html,Shiseido skincare essence,0.91,manual,evidence,2026-04-22T08:30:00\n");
        }

        int imported = service.importMappingsFromCsv(tempFile.getAbsolutePath());

        assertEquals(1, imported);

        ArgumentCaptor<ProductPublicMapping> captor = ArgumentCaptor.forClass(ProductPublicMapping.class);
        verify(mappingMapper, times(1)).upsert(captor.capture());

        ProductPublicMapping mapping = captor.getValue();
        assertEquals(Long.valueOf(44600062L), mapping.getItemId());
        assertEquals("Shiseido skincare essence", mapping.getVerifiedTitle());
        assertEquals(LocalDateTime.of(2026, 4, 22, 8, 30, 0), mapping.getVerifiedAt());
    }

    @Test
    void shouldExportVerifiedTitleAndVerifiedAtToRuntimeCsv() throws Exception {
        ProductPublicMappingMapper mappingMapper = mock(ProductPublicMappingMapper.class);
        ProductPublicMetricServiceImpl service = new ProductPublicMetricServiceImpl();
        ReflectionTestUtils.setField(service, "productPublicMappingMapper", mappingMapper);

        ProductPublicMapping mapping = new ProductPublicMapping();
        mapping.setItemId(44600062L);
        mapping.setSourcePlatform("jd");
        mapping.setSourceProductId("100012043978");
        mapping.setSourceUrl("https://item.jd.com/100012043978.html");
        mapping.setVerifiedTitle("Shiseido skincare essence");
        mapping.setMappingConfidence(new BigDecimal("0.91"));
        mapping.setVerificationNote("manual");
        mapping.setEvidenceNote("evidence");
        mapping.setVerifiedAt(LocalDateTime.of(2026, 4, 22, 8, 30, 0));
        when(mappingMapper.selectAllByPlatform("jd")).thenReturn(Collections.singletonList(mapping));

        File outputDir = Files.createTempDirectory("product-public-runtime").toFile();
        String exportedPath = service.exportMappingsToCsv("jd", outputDir.getAbsolutePath());
        String content = Files.readString(new File(exportedPath).toPath(), StandardCharsets.UTF_8);

        assertTrue(content.contains("verified_title"));
        assertTrue(content.contains("verified_at"));
        assertTrue(content.contains("Shiseido skincare essence"));
        assertTrue(content.contains("2026-04-22T08:30"));
    }

    @Test
    void shouldReleaseExistingSourceProductConflictBeforeConfirm() {
        ProductPublicMappingMapper mappingMapper = mock(ProductPublicMappingMapper.class);
        ProductPublicMetricMapper metricMapper = mock(ProductPublicMetricMapper.class);
        AnalysisCacheService analysisCacheService = mock(AnalysisCacheService.class);
        ProductPublicMetricServiceImpl service = new ProductPublicMetricServiceImpl();
        ReflectionTestUtils.setField(service, "productPublicMappingMapper", mappingMapper);
        ReflectionTestUtils.setField(service, "productPublicMetricMapper", metricMapper);
        ReflectionTestUtils.setField(service, "analysisCacheService", analysisCacheService);

        PublicMappingScoreRowVO row = new PublicMappingScoreRowVO();
        row.setItemId(44600062L);
        row.setSourcePlatform("jd");
        row.setSourceProductId("100012043978");
        row.setSourceUrl("https://item.jd.com/100012043978.html");
        row.setPublicTitle("Shiseido skincare essence");
        row.setTotalScore(new BigDecimal("0.91"));

        ProductPublicMapping existing = new ProductPublicMapping();
        existing.setId(9L);
        existing.setItemId(1001588L);
        existing.setSourcePlatform("jd");
        existing.setSourceProductId("100012043978");
        when(mappingMapper.selectBySourcePlatformAndProductId("jd", "100012043978")).thenReturn(existing);

        int confirmed = service.confirmMappings(Collections.singletonList(row));

        assertEquals(1, confirmed);
        verify(metricMapper, times(1)).deleteByItemAndPlatform(1001588L, "jd");
        verify(mappingMapper, times(1)).deleteById(9L);
        verify(mappingMapper, times(1)).upsert(any(ProductPublicMapping.class));
    }

    @Test
    void shouldListLatestMappingsByPlatform() {
        ProductPublicMappingMapper mappingMapper = mock(ProductPublicMappingMapper.class);
        ProductPublicMetricServiceImpl service = new ProductPublicMetricServiceImpl();
        ReflectionTestUtils.setField(service, "productPublicMappingMapper", mappingMapper);

        ProductPublicMapping mapping = new ProductPublicMapping();
        mapping.setItemId(44600062L);
        mapping.setSourcePlatform("jd");
        mapping.setSourceProductId("100012043978");
        when(mappingMapper.selectLatestByPlatform("jd", 5)).thenReturn(Collections.singletonList(mapping));

        List<ProductPublicMapping> rows = service.listLatestMappings("jd", 5);

        assertEquals(1, rows.size());
        assertEquals("100012043978", rows.get(0).getSourceProductId());
        verify(mappingMapper, times(1)).selectLatestByPlatform("jd", 5);
    }

    @Test
    void shouldRemoveMappingAndRelatedMetricSnapshot() {
        ProductPublicMappingMapper mappingMapper = mock(ProductPublicMappingMapper.class);
        ProductPublicMetricMapper metricMapper = mock(ProductPublicMetricMapper.class);
        AnalysisCacheService analysisCacheService = mock(AnalysisCacheService.class);
        ProductPublicMetricServiceImpl service = new ProductPublicMetricServiceImpl();
        ReflectionTestUtils.setField(service, "productPublicMappingMapper", mappingMapper);
        ReflectionTestUtils.setField(service, "productPublicMetricMapper", metricMapper);
        ReflectionTestUtils.setField(service, "analysisCacheService", analysisCacheService);

        ProductPublicMapping mapping = new ProductPublicMapping();
        mapping.setId(9L);
        mapping.setItemId(44600062L);
        mapping.setSourcePlatform("jd");
        when(mappingMapper.selectById(9L)).thenReturn(mapping);
        when(mappingMapper.deleteById(9L)).thenReturn(1);

        boolean removed = service.removeMapping(9L);

        assertTrue(removed);
        verify(metricMapper, times(1)).deleteByItemAndPlatform(44600062L, "jd");
        verify(mappingMapper, times(1)).deleteById(9L);
    }
}
