package com.ecommerce.analysis.service.impl;

import com.ecommerce.analysis.entity.Product;
import com.ecommerce.analysis.entity.UserBehavior;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DataImportServiceImplTest {

    private final DataImportServiceImpl service = new DataImportServiceImpl();

    @Test
    void shouldDetectArchiveFormatFromHeader() {
        DataImportServiceImpl.DatasetFormat format = service.detectFormat(
                "event_time,event_type,product_id,category_id,category_code,brand,price,user_id,user_session");

        assertEquals(DataImportServiceImpl.DatasetFormat.ARCHIVE, format);
    }

    @Test
    void shouldDetectArchiveFormatFromDataLine() {
        DataImportServiceImpl.DatasetFormat format = service.detectFormat(
                "2019-10-01 00:00:00 UTC,view,44600062,2103807459595387724,,shiseido,35.79,541312140,session-1");

        assertEquals(DataImportServiceImpl.DatasetFormat.ARCHIVE, format);
    }

    @Test
    void shouldDetectCrawledFormatFromDataLine() {
        DataImportServiceImpl.DatasetFormat format = service.detectFormat(
                "831122896845,4820710,1101,buy,1281965426,28.0,1");

        assertEquals(DataImportServiceImpl.DatasetFormat.CRAWLED, format);
    }

    @Test
    void shouldDetectManualFormatFromFlexibleHeader() {
        DataImportServiceImpl.DatasetFormat format = service.detectFormat(
                "userId,product_id,category_id,action,time,price,quantity,brand,category_code,session_id");

        assertEquals(DataImportServiceImpl.DatasetFormat.MANUAL, format);
    }

    @Test
    void shouldRejectUnsupportedDatasetFormat() {
        assertThrows(IllegalArgumentException.class, () -> service.detectFormat(
                "foo,bar,baz"));
    }

    @Test
    void shouldParseArchiveRowAndBuildProductMetadata() {
        DataImportServiceImpl.ParsedRow parsed = service.parseArchiveCsvLine(
                "2019-10-01 00:00:00 UTC,view,44600062,2103807459595387724,,shiseido,35.79,541312140,72d76fde-8bb3-4e00-8c23-a032dfed738c");

        assertNotNull(parsed);
        assertNotNull(parsed.behavior);
        assertNotNull(parsed.product);

        UserBehavior behavior = parsed.behavior;
        Product product = parsed.product;

        assertEquals("pv", behavior.getBehaviorType());
        assertEquals(LocalDateTime.of(2019, 10, 1, 8, 0, 0), behavior.getBehaviorDateTime());
        assertEquals(new BigDecimal("35.79"), behavior.getUnitPrice());

        assertEquals(Long.valueOf(44600062L), product.getItemId());
        assertEquals("shiseido", product.getBrand());
        assertEquals("shiseido #44600062", product.getName());
        assertEquals(new BigDecimal("35.79"), product.getPrice());
    }

    @Test
    void shouldSkipUnsupportedArchiveEventType() {
        DataImportServiceImpl.ParsedRow parsed = service.parseArchiveCsvLine(
                "2019-10-01 00:00:00 UTC,remove_from_cart,44600062,2103807459595387724,,shiseido,35.79,541312140,session-1");

        assertNotNull(parsed);
        assertFalse(parsed.isValid());
        assertEquals(DataImportServiceImpl.ParseFailureReason.UNSUPPORTED_BEHAVIOR, parsed.failureReason);
    }

    @Test
    void shouldParseCrawledRowWithImportTimePreprocessing() {
        DataImportServiceImpl.ParsedRow parsed = service.parseCrawledCsvLine(
                "831122896845,4820710,1101, purchase ,1281965426,28.0,0");

        assertNotNull(parsed);
        assertNotNull(parsed.behavior);
        assertNotNull(parsed.product);

        UserBehavior behavior = parsed.behavior;
        Product product = parsed.product;

        assertEquals(Long.valueOf(831122896845L), behavior.getUserId());
        assertEquals("buy", behavior.getBehaviorType());
        assertEquals(new BigDecimal("28.0"), behavior.getUnitPrice());
        assertEquals(1, behavior.getQty());
        assertEquals(LocalDateTime.of(2010, 8, 16, 21, 30, 26), behavior.getBehaviorDateTime());

        assertEquals(Long.valueOf(4820710L), product.getItemId());
        assertEquals(Long.valueOf(1101L), product.getCategoryId());
        assertEquals("商品#4820710", product.getName());
        assertEquals(new BigDecimal("28.0"), product.getPrice());
        assertTrue(parsed.preprocessed);
    }

    @Test
    void shouldRejectCrawledRowWhenCoreIdsAreMissing() {
        DataImportServiceImpl.ParsedRow parsed = service.parseCrawledCsvLine(
                ",4820710,1101,pv,1281961826,,");

        assertNotNull(parsed);
        assertFalse(parsed.isValid());
        assertEquals(DataImportServiceImpl.ParseFailureReason.INVALID_VALUE, parsed.failureReason);
    }

    @Test
    void shouldParseManualRowWithAutomaticPreprocessing() {
        DataImportServiceImpl.FlexibleColumnMapping mapping = service.resolveFlexibleColumnMapping(
                "userId,product_id,category_id,action,time,price,quantity,brand,category_code,session_id");

        DataImportServiceImpl.ParsedRow parsed = service.parseManualCsvLine(
                "\"831122896845\",\"4820710\",\"1101\",\" Purchase \",\"2010-08-16T13:30:26Z\",\"\",\"0\",\"Kindle\",\"books\",\"session-1\"",
                mapping);

        assertNotNull(parsed);
        assertTrue(parsed.isValid());
        assertTrue(parsed.preprocessed);
        assertTrue(parsed.defaultedPrice);
        assertTrue(parsed.defaultedQty);

        UserBehavior behavior = parsed.behavior;
        Product product = parsed.product;

        assertEquals(Long.valueOf(831122896845L), behavior.getUserId());
        assertEquals("buy", behavior.getBehaviorType());
        assertEquals(BigDecimal.ZERO, behavior.getUnitPrice());
        assertEquals(1, behavior.getQty());
        assertEquals(LocalDateTime.of(2010, 8, 16, 21, 30, 26), behavior.getBehaviorDateTime());

        assertEquals("Kindle", product.getBrand());
        assertEquals("books", product.getCategoryName());
        assertEquals("Kindle #4820710", product.getName());
    }

    @Test
    void shouldAllocateSamplingQuotaAcrossMoreDates() {
        Map<String, Long> dateRowCounts = new LinkedHashMap<>();
        dateRowCounts.put("2019-10-01", 900L);
        dateRowCounts.put("2019-10-02", 50L);
        dateRowCounts.put("2019-10-03", 50L);

        DataImportServiceImpl.SamplingPlan plan = DataImportServiceImpl.SamplingPlan.fromDateCounts(dateRowCounts, 6);

        assertEquals(6L, plan.getPlannedRows());
        assertTrue(plan.getTargetRowsForDate("2019-10-01") >= 1L);
        assertTrue(plan.getTargetRowsForDate("2019-10-02") >= 1L);
        assertTrue(plan.getTargetRowsForDate("2019-10-03") >= 1L);
    }

    @Test
    void shouldSampleRowsEvenlyWithinEachDate() {
        Map<String, Long> dateRowCounts = new LinkedHashMap<>();
        dateRowCounts.put("2019-10-01", 4L);
        dateRowCounts.put("2019-10-02", 4L);

        DataImportServiceImpl.SamplingPlan plan = DataImportServiceImpl.SamplingPlan.fromDateCounts(dateRowCounts, 4);

        long firstDaySelected = 0;
        long secondDaySelected = 0;
        for (int i = 0; i < 4; i++) {
            if (plan.shouldImport("2019-10-01")) {
                firstDaySelected++;
            }
            if (plan.shouldImport("2019-10-02")) {
                secondDaySelected++;
            }
        }

        assertEquals(2L, firstDaySelected);
        assertEquals(2L, secondDaySelected);
    }
}
