package com.ecommerce.analysis.service.impl;

import com.ecommerce.analysis.entity.Product;
import com.ecommerce.analysis.entity.UserBehavior;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;

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
    void shouldParseLegacyRow() {
        DataImportServiceImpl.ParsedRow parsed = service.parseLegacyCsvLine(
                "831122896845,4820710,1101,buy,1281965426,28.0,2");

        assertNotNull(parsed);
        assertNotNull(parsed.behavior);
        assertNull(parsed.product);

        UserBehavior behavior = parsed.behavior;
        assertEquals(Long.valueOf(831122896845L), behavior.getUserId());
        assertEquals(Long.valueOf(4820710L), behavior.getItemId());
        assertEquals("buy", behavior.getBehaviorType());
        assertEquals(new BigDecimal("28.0"), behavior.getUnitPrice());
        assertEquals(Integer.valueOf(2), behavior.getQty());
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

        assertNull(parsed);
    }
}
