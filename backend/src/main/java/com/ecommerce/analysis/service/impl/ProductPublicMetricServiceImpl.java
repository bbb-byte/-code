package com.ecommerce.analysis.service.impl;

import com.ecommerce.analysis.entity.ProductPublicMapping;
import com.ecommerce.analysis.entity.ProductPublicMetric;
import com.ecommerce.analysis.mapper.ProductPublicMappingMapper;
import com.ecommerce.analysis.mapper.ProductPublicMetricMapper;
import com.ecommerce.analysis.service.ProductPublicMetricService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 商品公网满意度指标导入服务实现。
 */
@Service
public class ProductPublicMetricServiceImpl implements ProductPublicMetricService {

    private static final String SUPPORTED_PLATFORM = "jd";

    @Autowired
    private ProductPublicMetricMapper productPublicMetricMapper;

    @Autowired
    private ProductPublicMappingMapper productPublicMappingMapper;

    @Override
    public int importMappingsFromCsv(String filePath) throws IOException {
        int importedRows = 0;
        try (BufferedReader reader = new BufferedReader(new FileReader(filePath))) {
            String headerLine = reader.readLine();
            if (headerLine == null || headerLine.trim().isEmpty()) {
                return 0;
            }

            Map<String, Integer> headerIndex = parseHeader(headerLine);
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.trim().isEmpty()) {
                    continue;
                }

                ProductPublicMapping mapping = parseMapping(line, headerIndex);
                if (mapping == null) {
                    continue;
                }

                productPublicMappingMapper.upsert(mapping);
                importedRows++;
            }
        }
        return importedRows;
    }

    @Override
    public int importLatestMetricsFromCsv(String filePath) throws IOException {
        int importedRows = 0;
        try (BufferedReader reader = new BufferedReader(new FileReader(filePath))) {
            String headerLine = reader.readLine();
            if (headerLine == null || headerLine.trim().isEmpty()) {
                return 0;
            }

            Map<String, Integer> headerIndex = parseHeader(headerLine);
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.trim().isEmpty()) {
                    continue;
                }

                ProductPublicMetric metric = parseMetric(line, headerIndex);
                if (metric == null) {
                    continue;
                }

                productPublicMetricMapper.upsertLatest(metric);
                importedRows++;
            }
        }
        return importedRows;
    }

    private Map<String, Integer> parseHeader(String headerLine) {
        Map<String, Integer> headerIndex = new HashMap<>();
        String[] parts = parseCsvLine(headerLine);
        for (int i = 0; i < parts.length; i++) {
            headerIndex.put(parts[i].trim().toLowerCase(Locale.ROOT), i);
        }
        return headerIndex;
    }

    private ProductPublicMapping parseMapping(String line, Map<String, Integer> headerIndex) {
        String[] parts = parseCsvLine(line);
        Long itemId = parseLong(valueOf(parts, headerIndex, "item_id"));
        String sourcePlatform = normalizePlatform(valueOf(parts, headerIndex, "source_platform"));
        String sourceProductId = trimToNull(valueOf(parts, headerIndex, "source_product_id"));
        String sourceUrl = trimToNull(valueOf(parts, headerIndex, "source_url"));
        if (itemId == null || sourcePlatform == null || sourceProductId == null || sourceUrl == null) {
            return null;
        }

        ProductPublicMapping mapping = new ProductPublicMapping();
        mapping.setItemId(itemId);
        mapping.setSourcePlatform(sourcePlatform);
        mapping.setSourceProductId(sourceProductId);
        mapping.setSourceUrl(sourceUrl);
        mapping.setVerifiedTitle(trimToNull(valueOf(parts, headerIndex, "verified_title")));
        mapping.setMappingConfidence(parseMappingConfidence(valueOf(parts, headerIndex, "mapping_confidence")));
        mapping.setVerificationNote(trimToNull(valueOf(parts, headerIndex, "verification_note")));
        mapping.setEvidenceNote(trimToNull(valueOf(parts, headerIndex, "evidence_note")));
        mapping.setVerifiedAt(parseDateTime(valueOf(parts, headerIndex, "verified_at")));
        return mapping;
    }

    private ProductPublicMetric parseMetric(String line, Map<String, Integer> headerIndex) {
        String[] parts = parseCsvLine(line);
        Long itemId = parseLong(valueOf(parts, headerIndex, "item_id"));
        String sourcePlatform = normalizePlatform(valueOf(parts, headerIndex, "source_platform"));
        String sourceUrl = trimToNull(valueOf(parts, headerIndex, "source_url"));
        if (itemId == null || sourcePlatform == null || sourceUrl == null) {
            return null;
        }
        if (productPublicMappingMapper.countByItemAndPlatform(itemId, sourcePlatform) == 0) {
            return null;
        }

        ProductPublicMetric metric = new ProductPublicMetric();
        metric.setItemId(itemId);
        metric.setSourcePlatform(sourcePlatform);
        metric.setSourceProductId(trimToNull(valueOf(parts, headerIndex, "source_product_id")));
        metric.setSourceUrl(sourceUrl);
        metric.setPositiveRate(parsePercent(valueOf(parts, headerIndex, "positive_rate")));
        metric.setReviewCount(parseLong(valueOf(parts, headerIndex, "review_count")));
        metric.setShopScore(parseDecimal(valueOf(parts, headerIndex, "shop_score")));
        metric.setRatingText(trimToNull(valueOf(parts, headerIndex, "rating_text")));
        metric.setCrawlStatus(defaultIfBlank(valueOf(parts, headerIndex, "crawl_status"), "unknown"));
        metric.setRawPayload(trimToNull(valueOf(parts, headerIndex, "raw_payload")));
        metric.setCrawledAt(parseDateTime(valueOf(parts, headerIndex, "crawled_at")));
        return metric;
    }

    private String valueOf(String[] parts, Map<String, Integer> headerIndex, String key) {
        Integer index = headerIndex.get(key);
        if (index == null || index < 0 || index >= parts.length) {
            return null;
        }
        return parts[index];
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.startsWith("\"") && trimmed.endsWith("\"") && trimmed.length() >= 2) {
            trimmed = trimmed.substring(1, trimmed.length() - 1).replace("\"\"", "\"");
        }
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String defaultIfBlank(String value, String fallback) {
        String trimmed = trimToNull(value);
        return trimmed == null ? fallback : trimmed;
    }

    private Long parseLong(String value) {
        String trimmed = trimToNull(value);
        if (trimmed == null) {
            return null;
        }
        try {
            if (trimmed.endsWith("万")) {
                BigDecimal decimal = new BigDecimal(trimmed.substring(0, trimmed.length() - 1));
                return decimal.multiply(new BigDecimal("10000")).longValue();
            }
            return Long.valueOf(trimmed.replace(",", "").replace("+", ""));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private BigDecimal parseDecimal(String value) {
        String trimmed = trimToNull(value);
        if (trimmed == null) {
            return null;
        }
        try {
            return new BigDecimal(trimmed.replace("%", ""));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private BigDecimal parsePercent(String value) {
        BigDecimal decimal = parseDecimal(value);
        if (decimal == null) {
            return null;
        }
        if (trimToNull(value) != null && !trimToNull(value).endsWith("%") && decimal.compareTo(BigDecimal.ONE) <= 0) {
            decimal = decimal.multiply(new BigDecimal("100"));
        }
        return decimal;
    }

    private BigDecimal parseMappingConfidence(String value) {
        String trimmed = trimToNull(value);
        if (trimmed == null) {
            return null;
        }
        switch (trimmed.toLowerCase(Locale.ROOT)) {
            case "manual":
                return new BigDecimal("0.85");
            case "reviewed":
                return new BigDecimal("0.95");
            case "high":
                return BigDecimal.ONE;
            default:
                return parseDecimal(trimmed);
        }
    }

    private LocalDateTime parseDateTime(String value) {
        String trimmed = trimToNull(value);
        if (trimmed == null) {
            return null;
        }
        try {
            return OffsetDateTime.parse(trimmed).toLocalDateTime();
        } catch (DateTimeParseException ex) {
            try {
                return LocalDateTime.parse(trimmed);
            } catch (DateTimeParseException ignored) {
                try {
                    return LocalDateTime.parse(trimmed.replace(" ", "T"));
                } catch (DateTimeParseException ignoredAgain) {
                    return null;
                }
            }
        }
    }

    private String normalizePlatform(String value) {
        String trimmed = trimToNull(value);
        if (trimmed == null) {
            return null;
        }
        String normalized = trimmed.toLowerCase(Locale.ROOT);
        return SUPPORTED_PLATFORM.equals(normalized) ? normalized : null;
    }

    private String[] parseCsvLine(String line) {
        java.util.List<String> columns = new java.util.ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;

        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (ch == '"') {
                if (inQuotes && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    current.append('"');
                    i++;
                } else {
                    inQuotes = !inQuotes;
                }
            } else if (ch == ',' && !inQuotes) {
                columns.add(current.toString());
                current.setLength(0);
            } else {
                current.append(ch);
            }
        }
        columns.add(current.toString());
        return columns.toArray(new String[0]);
    }
}
