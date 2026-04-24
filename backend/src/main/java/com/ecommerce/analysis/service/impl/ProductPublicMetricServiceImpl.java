package com.ecommerce.analysis.service.impl;

import com.ecommerce.analysis.entity.ProductPublicMapping;
import com.ecommerce.analysis.entity.ProductPublicMetric;
import com.ecommerce.analysis.mapper.ProductPublicMappingMapper;
import com.ecommerce.analysis.mapper.ProductPublicMetricMapper;
import com.ecommerce.analysis.service.ProductPublicMetricService;
import com.ecommerce.analysis.vo.PublicMappingScorePreviewVO;
import com.ecommerce.analysis.vo.PublicMappingScoreRowVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 商品公网满意度指标导入服务实现。
 */
@Service
public class ProductPublicMetricServiceImpl implements ProductPublicMetricService {

    private static final int DEFAULT_SCORE_PREVIEW_SIZE = 50;
    private static final int MAX_SCORE_PREVIEW_SIZE = 200;

    private static final String SUPPORTED_PLATFORM = "jd";

    @Autowired
    private ProductPublicMetricMapper productPublicMetricMapper;

    @Autowired
    private ProductPublicMappingMapper productPublicMappingMapper;

    @Autowired
    private AnalysisCacheService analysisCacheService;

    /**
     * 导入人工确认后的公网映射 CSV，并按 item_id + 平台做 upsert。
     */
    @Override
    public int importMappingsFromCsv(String filePath) throws IOException {
        int importedRows = 0;
        try (BufferedReader reader = Files.newBufferedReader(Paths.get(filePath), StandardCharsets.UTF_8)) {
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
        if (importedRows > 0) {
            analysisCacheService.evictAnalysisCaches();
        }
        return importedRows;
    }

    /**
     * 导入最新抓取到的公网指标快照。
     */
    @Override
    public int importLatestMetricsFromCsv(String filePath) throws IOException {
        int importedRows = 0;
        try (BufferedReader reader = Files.newBufferedReader(Paths.get(filePath), StandardCharsets.UTF_8)) {
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
        if (importedRows > 0) {
            analysisCacheService.evictAnalysisCaches();
        }
        return importedRows;
    }

    /**
     * 分页预览评分文件，供管理员在正式确认前复核。
     */
    @Override
    public PublicMappingScorePreviewVO previewScoreRows(String filePath, int page, int pageSize) throws IOException {
        int safePage = Math.max(page, 1);
        int safePageSize = pageSize <= 0 ? DEFAULT_SCORE_PREVIEW_SIZE : Math.min(pageSize, MAX_SCORE_PREVIEW_SIZE);
        long skip = (long) (safePage - 1) * safePageSize;
        long total = 0L;
        List<PublicMappingScoreRowVO> rows = new ArrayList<>();

        try (BufferedReader reader = Files.newBufferedReader(Paths.get(filePath), StandardCharsets.UTF_8)) {
            String headerLine = reader.readLine();
            if (headerLine == null || headerLine.trim().isEmpty()) {
                PublicMappingScorePreviewVO result = new PublicMappingScorePreviewVO();
                result.setPage(safePage);
                result.setPageSize(safePageSize);
                result.setTotal(0);
                result.setRows(rows);
                return result;
            }

            Map<String, Integer> headerIndex = parseHeader(headerLine);
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.trim().isEmpty()) {
                    continue;
                }

                PublicMappingScoreRowVO row = parseScoreRow(line, headerIndex);
                if (row != null) {
                    total++;
                    if (total <= skip) {
                        continue;
                    }
                    if (rows.size() < safePageSize) {
                        rows.add(row);
                    }
                }
            }
        }

        PublicMappingScorePreviewVO result = new PublicMappingScorePreviewVO();
        result.setPage(safePage);
        result.setPageSize(safePageSize);
        result.setTotal(total);
        result.setRows(rows);
        return result;
    }

    /**
     * 批量确认人工审核通过的公网映射。
     */
    @Override
    public int confirmMappings(List<PublicMappingScoreRowVO> rows) {
        int confirmedRows = 0;
        if (rows == null) {
            return 0;
        }
        for (PublicMappingScoreRowVO row : rows) {
            ProductPublicMapping mapping = toConfirmedMapping(row);
            if (mapping == null) {
                continue;
            }
            // 同一个公网商品只能绑定一个内部商品，确认前先释放冲突绑定。
            releaseConflictingSourceProduct(mapping);
            productPublicMappingMapper.upsert(mapping);
            confirmedRows++;
        }
        if (confirmedRows > 0) {
            analysisCacheService.evictAnalysisCaches();
        }
        return confirmedRows;
    }

    /**
     * 读取最近确认过的映射记录。
     */
    @Override
    public List<ProductPublicMapping> listLatestMappings(String sourcePlatform, int limit) {
        String normalizedPlatform = normalizePlatform(sourcePlatform);
        if (normalizedPlatform == null) {
            normalizedPlatform = SUPPORTED_PLATFORM;
        }
        int normalizedLimit = limit <= 0 ? 10 : Math.min(limit, 100);
        return productPublicMappingMapper.selectLatestByPlatform(normalizedPlatform, normalizedLimit);
    }

    /**
     * 将当前映射表导出为运行时 CSV，供 Python 抓取脚本继续消费。
     */
    @Override
    public String exportMappingsToCsv(String sourcePlatform, String outputDir) throws IOException {
        String normalizedPlatform = normalizePlatform(sourcePlatform);
        if (normalizedPlatform == null) {
            normalizedPlatform = SUPPORTED_PLATFORM;
        }

        List<ProductPublicMapping> mappings = productPublicMappingMapper.selectAllByPlatform(normalizedPlatform);
        Path outputPath = Paths.get(outputDir, normalizedPlatform + "_product_public_mapping.runtime.csv");
        Files.createDirectories(outputPath.getParent());

        try (BufferedWriter writer = Files.newBufferedWriter(outputPath, StandardCharsets.UTF_8)) {
            writer.write("item_id,source_platform,source_product_id,source_url,verified_title,mapping_confidence,verification_note,evidence_note,verified_at");
            writer.newLine();
            for (ProductPublicMapping mapping : mappings) {
                if (mapping == null || mapping.getItemId() == null) {
                    continue;
                }
                writer.write(csv(mapping.getItemId()));
                writer.write(",");
                writer.write(csv(defaultIfBlank(mapping.getSourcePlatform(), normalizedPlatform)));
                writer.write(",");
                writer.write(csv(mapping.getSourceProductId()));
                writer.write(",");
                writer.write(csv(mapping.getSourceUrl()));
                writer.write(",");
                writer.write(csv(mapping.getVerifiedTitle()));
                writer.write(",");
                writer.write(csv(mapping.getMappingConfidence() == null ? "1.0" : mapping.getMappingConfidence().stripTrailingZeros().toPlainString()));
                writer.write(",");
                writer.write(csv(mapping.getVerificationNote()));
                writer.write(",");
                writer.write(csv(mapping.getEvidenceNote()));
                writer.write(",");
                writer.write(csv(mapping.getVerifiedAt()));
                writer.newLine();
            }
        }

        return outputPath.toString();
    }

    /**
     * 删除映射，并同步删除对应平台下的已落库公网指标。
     */
    @Override
    public boolean removeMapping(Long id) {
        if (id == null) {
            return false;
        }
        ProductPublicMapping mapping = productPublicMappingMapper.selectById(id);
        if (mapping == null) {
            return false;
        }
        String sourcePlatform = normalizePlatform(mapping.getSourcePlatform());
        if (sourcePlatform != null && mapping.getItemId() != null) {
            productPublicMetricMapper.deleteByItemAndPlatform(mapping.getItemId(), sourcePlatform);
        }
        boolean removed = productPublicMappingMapper.deleteById(id) > 0;
        if (removed) {
            analysisCacheService.evictAnalysisCaches();
        }
        return removed;
    }

    /**
     * 把 CSV 表头映射成列名到下标的索引表，后续按列名取值。
     */
    private Map<String, Integer> parseHeader(String headerLine) {
        Map<String, Integer> headerIndex = new HashMap<>();
        String[] parts = parseCsvLine(headerLine);
        for (int i = 0; i < parts.length; i++) {
            headerIndex.put(parts[i].trim().toLowerCase(Locale.ROOT), i);
        }
        return headerIndex;
    }

    /**
     * 解析单行映射 CSV，生成待 upsert 的映射实体。
     */
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

    /**
     * 解析单行公网指标 CSV；若内部尚未确认对应映射，则直接丢弃该行。
     */
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

    /**
     * 解析评分结果 CSV 中的一行，并补齐默认的审核说明字段。
     */
    private PublicMappingScoreRowVO parseScoreRow(String line, Map<String, Integer> headerIndex) {
        String[] parts = parseCsvLine(line);
        Long itemId = parseLong(valueOf(parts, headerIndex, "item_id"));
        String sourcePlatform = normalizePlatform(valueOf(parts, headerIndex, "source_platform"));
        String sourceProductId = trimToNull(valueOf(parts, headerIndex, "source_product_id"));
        String sourceUrl = trimToNull(valueOf(parts, headerIndex, "source_url"));
        if (itemId == null || sourcePlatform == null || sourceProductId == null || sourceUrl == null) {
            return null;
        }

        PublicMappingScoreRowVO row = new PublicMappingScoreRowVO();
        row.setItemId(itemId);
        row.setBrand(trimToNull(valueOf(parts, headerIndex, "brand")));
        row.setCategoryName(trimToNull(valueOf(parts, headerIndex, "category_name")));
        row.setInternalPrice(parseDecimal(valueOf(parts, headerIndex, "internal_price")));
        row.setSourcePlatform(sourcePlatform);
        row.setSourceProductId(sourceProductId);
        row.setSourceUrl(sourceUrl);
        row.setPublicTitle(trimToNull(valueOf(parts, headerIndex, "public_title")));
        row.setPublicBrand(trimToNull(valueOf(parts, headerIndex, "public_brand")));
        row.setPublicCategory(trimToNull(valueOf(parts, headerIndex, "public_category")));
        row.setPublicPrice(parseDecimal(valueOf(parts, headerIndex, "public_price")));
        row.setBrandScore(parseDecimal(valueOf(parts, headerIndex, "brand_score")));
        row.setCategoryScore(parseDecimal(valueOf(parts, headerIndex, "category_score")));
        row.setPriceScore(parseDecimal(valueOf(parts, headerIndex, "price_score")));
        row.setTitleScore(parseDecimal(valueOf(parts, headerIndex, "title_score")));
        row.setEvidenceScore(parseDecimal(valueOf(parts, headerIndex, "evidence_score")));
        row.setTotalScore(parseDecimal(valueOf(parts, headerIndex, "total_score")));
        row.setRecommendedAction(trimToNull(valueOf(parts, headerIndex, "recommended_action")));
        row.setScoreReason(trimToNull(valueOf(parts, headerIndex, "score_reason")));
        row.setVerifiedTitle(trimToNull(row.getPublicTitle()));
        row.setMappingConfidence(row.getTotalScore() == null ? new BigDecimal("0.50") : row.getTotalScore());
        row.setVerificationNote(buildVerificationNote(row));
        row.setEvidenceNote(buildEvidenceNote(row));
        return row;
    }

    /**
     * 把前端评分预览行转换成可入库的正式映射实体。
     */
    private ProductPublicMapping toConfirmedMapping(PublicMappingScoreRowVO row) {
        if (row == null || row.getItemId() == null) {
            return null;
        }
        String sourcePlatform = normalizePlatform(row.getSourcePlatform());
        String sourceProductId = trimToNull(row.getSourceProductId());
        String sourceUrl = trimToNull(row.getSourceUrl());
        if (sourcePlatform == null || sourceProductId == null || sourceUrl == null) {
            return null;
        }

        ProductPublicMapping mapping = new ProductPublicMapping();
        mapping.setItemId(row.getItemId());
        mapping.setSourcePlatform(sourcePlatform);
        mapping.setSourceProductId(sourceProductId);
        mapping.setSourceUrl(sourceUrl);
        mapping.setVerifiedTitle(defaultIfBlank(row.getVerifiedTitle(), row.getPublicTitle()));
        mapping.setMappingConfidence(row.getMappingConfidence() == null ? (row.getTotalScore() == null ? new BigDecimal("0.50") : row.getTotalScore()) : row.getMappingConfidence());
        mapping.setVerificationNote(defaultIfBlank(row.getVerificationNote(), buildVerificationNote(row)));
        mapping.setEvidenceNote(defaultIfBlank(row.getEvidenceNote(), buildEvidenceNote(row)));
        mapping.setVerifiedAt(LocalDateTime.now());
        return mapping;
    }

    /**
     * 释放同一个公网商品已经绑定到其他内部商品的旧映射，确保绑定关系唯一。
     */
    private void releaseConflictingSourceProduct(ProductPublicMapping mapping) {
        if (mapping == null || mapping.getItemId() == null) {
            return;
        }
        String sourcePlatform = normalizePlatform(mapping.getSourcePlatform());
        String sourceProductId = trimToNull(mapping.getSourceProductId());
        if (sourcePlatform == null || sourceProductId == null) {
            return;
        }

        ProductPublicMapping existing = productPublicMappingMapper.selectBySourcePlatformAndProductId(sourcePlatform, sourceProductId);
        if (existing == null || existing.getId() == null) {
            return;
        }
        if (mapping.getItemId().equals(existing.getItemId())) {
            return;
        }

        if (existing.getItemId() != null) {
            productPublicMetricMapper.deleteByItemAndPlatform(existing.getItemId(), sourcePlatform);
        }
        productPublicMappingMapper.deleteById(existing.getId());
    }

    /**
     * 根据评分行生成简短的审核备注。
     */
    private String buildVerificationNote(PublicMappingScoreRowVO row) {
        String scoreText = row.getTotalScore() == null ? "unknown" : row.getTotalScore().stripTrailingZeros().toPlainString();
        String reason = defaultIfBlank(row.getScoreReason(), "manual_confirm");
        return abbreviate("页面工作台确认入库; score=" + scoreText + "; reason=" + reason, 255);
    }

    /**
     * 拼接审核时需要展示的证据摘要。
     */
    private String buildEvidenceNote(PublicMappingScoreRowVO row) {
        StringBuilder builder = new StringBuilder();
        if (trimToNull(row.getPublicTitle()) != null) {
            builder.append("title=").append(row.getPublicTitle());
        }
        if (trimToNull(row.getPublicBrand()) != null) {
            appendWithSeparator(builder, "brand=" + row.getPublicBrand());
        }
        if (trimToNull(row.getPublicCategory()) != null) {
            appendWithSeparator(builder, "category=" + row.getPublicCategory());
        }
        return abbreviate(builder.toString(), 255);
    }

    /**
     * 对 CSV 字段做最小转义，避免逗号、引号和换行破坏导出格式。
     */
    private String csv(Object value) {
        if (value == null) {
            return "";
        }
        String text = String.valueOf(value);
        if (text.contains("\"") || text.contains(",") || text.contains("\n") || text.contains("\r")) {
            return "\"" + text.replace("\"", "\"\"") + "\"";
        }
        return text;
    }

    /**
     * 以统一分隔符拼接备注片段。
     */
    private void appendWithSeparator(StringBuilder builder, String value) {
        if (builder.length() > 0) {
            builder.append("; ");
        }
        builder.append(value);
    }

    /**
     * 控制备注长度，避免超出数据库字段限制。
     */
    private String abbreviate(String value, int maxLength) {
        String trimmed = trimToNull(value);
        if (trimmed == null || trimmed.length() <= maxLength) {
            return trimmed;
        }
        return trimmed.substring(0, Math.max(0, maxLength - 3)) + "...";
    }

    /**
     * 按列名安全读取 CSV 字段值。
     */
    private String valueOf(String[] parts, Map<String, Integer> headerIndex, String key) {
        Integer index = headerIndex.get(key);
        if (index == null || index < 0 || index >= parts.length) {
            return null;
        }
        return parts[index];
    }

    /**
     * 去掉首尾空白与包裹引号，并把空串统一转成 null。
     */
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

    /**
     * 字段为空时使用兜底值。
     */
    private String defaultIfBlank(String value, String fallback) {
        String trimmed = trimToNull(value);
        return trimmed == null ? fallback : trimmed;
    }

    /**
     * 解析整数，同时兼容带人工数量后缀的文本。
     */
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

    /**
     * 解析十进制数字。
     */
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

    /**
     * 解析百分比；若原始文本带百分号，则自动缩放为 0~1 区间。
     */
    private BigDecimal parsePercent(String value) {
        BigDecimal decimal = parseDecimal(value);
        if (decimal == null) {
            return null;
        }
        String trimmed = trimToNull(value);
        if (trimmed != null && trimmed.endsWith("%")) {
            decimal = decimal.divide(new BigDecimal("100"));
        }
        return decimal;
    }

    /**
     * 解析映射置信度，兼容枚举型人工标记与数值型分数。
     */
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

    /**
     * 宽松解析多种时间格式。
     */
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

    /**
     * 规范化平台标识；当前仅接受 jd。
     */
    private String normalizePlatform(String value) {
        String trimmed = trimToNull(value);
        if (trimmed == null) {
            return null;
        }
        String normalized = trimmed.toLowerCase(Locale.ROOT);
        return SUPPORTED_PLATFORM.equals(normalized) ? normalized : null;
    }

    /**
     * 逐字符解析 CSV 行，兼容带引号和转义双引号的字段。
     */
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
